//! gbxcam: Dump and extract all Game Boy Camera photos via GBxCart RW.
//!
//! USAGE:
//!   termux-usb -l
//!   termux-usb -r -e ./gbxcam /dev/bus/usb/001/002
//!   termux-usb -r -e "./gbxcam --erase" /dev/bus/usb/001/002
//!   gbxcam GAMEBOYCAMERA.sav

use gbxcam_core::{write_photos_to_dir, CartridgeReport, MAPPER_MAC_GBD, SAVE_SIZE, WRITE_CHUNK};
use gbxcam_usb::{GbxCartInfo, Progress, UsbDev};
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::io::{IntoRawFd, RawFd};
use std::path::Path;
use std::process;

struct CliProgress;

impl Progress for CliProgress {
    fn message(&mut self, message: &str) {
        println!("{}", message);
    }

    fn cartridge_header(&mut self, report: &CartridgeReport) {
        println!("  Title:  \"{}\"", report.title);
        println!(
            "  Mapper: 0x{:02X} ({})",
            report.mapper,
            if report.mapper == MAPPER_MAC_GBD {
                "GB Camera ok"
            } else {
                "UNKNOWN - not a GB Camera"
            }
        );
    }

    fn sram_progress(&mut self, done_bytes: usize, total_bytes: usize) {
        print!("\r  {}/{} KiB", done_bytes / 1024, total_bytes / 1024);
        io::stdout().flush().ok();
        if done_bytes == total_bytes {
            println!();
        }
    }
}

fn main() {
    if let Err(e) = run() {
        eprintln!("{}", e);
        if e.to_string().contains("timed out") {
            eprintln!("The GBxCart RW is not responding.");
            eprintln!("Unplug and replug the GBxCart RW, then try again.");
        }
        process::exit(1);
    }
}

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().skip(1).collect();

    if args.len() == 1 && args[0].ends_with(".sav") {
        let data = fs::read(&args[0])?;
        println!("Extracting from {} ({} KiB)...", args[0], data.len() / 1024);
        extract_photos(&data)?;
        return Ok(());
    }

    let erase = args.iter().any(|a| a == "--erase");
    let usb_fd = resolve_usb_fd(&args)?;

    let (bulk_size, ctrl_size, usize_size) = UsbDev::ioctl_layout();
    eprintln!(
        "[debug] BulkTransfer={} bytes, CtrlTransfer={} bytes, usize={} bytes",
        bulk_size, ctrl_size, usize_size
    );

    let mut progress = CliProgress;
    let (dev, info) = UsbDev::connect(usb_fd, &mut progress)?;
    print_connected(&info);

    let save = match dev.dump_save(&mut progress) {
        Ok(save) => save,
        Err(e) => {
            dev.finish_operation(false, &mut progress);
            return Err(e.into());
        }
    };
    match fs::write("GAMEBOYCAMERA.sav", &save) {
        Ok(()) => println!("Saved raw dump: GAMEBOYCAMERA.sav"),
        Err(e) => eprintln!("Warning: could not write .sav: {}", e),
    }

    if erase {
        println!();
        println!(
            "Erasing {} KiB SRAM in {}-byte chunks...",
            SAVE_SIZE / 1024,
            WRITE_CHUNK
        );
        println!("Preserving Game Boy Camera calibration ranges from the just-created backup.");
        if let Err(e) = dev.erase_save(&save, &mut progress) {
            dev.finish_operation(false, &mut progress);
            return Err(e.into());
        }
        println!("Erase complete - camera memory should be ready to shoot again.");
    } else {
        println!(
            "\nTip: run with --erase to wipe camera memory after extracting. Calibration ranges are preserved."
        );
    }
    dev.finish_operation(true, &mut progress);

    println!("\nExtracting photos...");
    extract_photos(&save)?;

    Ok(())
}

fn extract_photos(save: &[u8]) -> Result<(), Box<dyn std::error::Error>> {
    let names = write_photos_to_dir(save, Path::new("."))?;
    for name in &names {
        println!("  {}", name);
    }
    println!("\nExtracted {} image(s).", names.len());
    Ok(())
}

fn print_connected(info: &GbxCartInfo) {
    match (&info.name, info.cfw_ver) {
        (Some(name), cfw) if cfw >= 12 => println!(
            "Connected: {} (PCB v{}, OFW R{}, CFW L{})",
            name, info.pcb_ver, info.ofw_ver, info.cfw_ver
        ),
        (_, cfw) if cfw > 0 => println!(
            "Connected: GBxCart RW v{} (OFW R{}, CFW L{})",
            info.pcb_ver, info.ofw_ver, info.cfw_ver
        ),
        _ => println!(
            "Connected: GBxCart RW v{} (OFW R{})",
            info.pcb_ver, info.ofw_ver
        ),
    }
}

fn resolve_usb_fd(args: &[String]) -> Result<RawFd, Box<dyn std::error::Error>> {
    if let Ok(val) = std::env::var("TERMUX_USB_FD") {
        let fd = val.trim().parse::<RawFd>()?;
        println!("Using fd {} from TERMUX_USB_FD.", fd);
        return Ok(fd);
    }

    if let Some(fd_str) = args.iter().find(|a| a.parse::<RawFd>().is_ok()) {
        let fd = fd_str.parse::<RawFd>()?;
        println!("Using fd {} from argument.", fd);
        return Ok(fd);
    }

    if let Some(path) = args.iter().find(|a| a.starts_with("/dev/")) {
        println!("Opening {} directly...", path);
        return try_open_direct(path).ok_or_else(|| {
            format!(
                "Permission denied opening {}.\n\nUse termux-usb to get permission:\n  termux-usb -r -e ./gbxcam {}",
                path, path
            )
            .into()
        });
    }

    println!("Scanning /dev/bus/usb for GBxCart RW...");
    if let Some(path) = find_gbxcart() {
        println!("Found: {}", path);
        return try_open_direct(&path).ok_or_else(|| {
            format!(
                "Permission denied. Use termux-usb:\n  termux-usb -r -e ./gbxcam {}",
                path
            )
            .into()
        });
    }

    Err("No device found. Check USB OTG connection.\n\nUsage:\n  termux-usb -l\n  termux-usb -r -e ./gbxcam /dev/bus/usb/002/002\n  termux-usb -r -e \"./gbxcam --erase\" /dev/bus/usb/002/002\n\nOr from an existing save file:\n  ./gbxcam GAMEBOYCAMERA.sav".into())
}

fn try_open_direct(path: &str) -> Option<RawFd> {
    match OpenOptions::new()
        .read(true)
        .write(true)
        .custom_flags(libc::O_CLOEXEC)
        .open(path)
    {
        Ok(f) => Some(f.into_raw_fd()),
        Err(e) => {
            eprintln!("Cannot open {} directly: {}", path, e);
            None
        }
    }
}

fn find_gbxcart() -> Option<String> {
    for bus in fs::read_dir("/dev/bus/usb").ok()?.flatten() {
        for dev in fs::read_dir(bus.path()).ok()?.flatten() {
            let path = dev.path();
            let data = fs::read(&path).ok()?;
            if data.len() >= 12 {
                let vid = u16::from_le_bytes([data[8], data[9]]);
                if vid == 0x1A86 {
                    return Some(path.to_string_lossy().into_owned());
                }
            }
        }
    }
    None
}
