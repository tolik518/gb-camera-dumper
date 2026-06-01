//! gbxcam: Dump and extract all Game Boy Camera photos via GBxCart RW.
//!
//! USAGE:
//!   termux-usb -l
//!   termux-usb -r -e ./gbxcam /dev/bus/usb/001/002
//!   termux-usb -r -e "./gbxcam --erase" /dev/bus/usb/001/002
//!   gbxcam GAMEBOYCAMERA.sav

use gbxcam_core::{
    apply_album_delete, apply_album_rebuild_from_nonblank_slots, apply_album_recover,
    apply_album_reorder, write_photos_to_dir, CartridgeReport, GbcamSave, ValidationSeverity,
    MAPPER_MAC_GBD, SAVE_SIZE, WRITE_CHUNK,
};
use gbxcam_usb::{GbxCartInfo, Progress, UsbDev};
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::io::{IntoRawFd, RawFd};
use std::path::Path;
use std::process;

const VID_WCH: u16 = 0x1A86;
const PID_CH340: u16 = 0x7523;

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

    if args.len() == 2 && args[0] == "--check" && args[1].ends_with(".sav") {
        let data = fs::read(&args[1])?;
        println!("Checking {} ({} KiB)...", args[1], data.len() / 1024);
        check_save(&data)?;
        return Ok(());
    }

    if args.len() == 4 && args[0] == "--delete-save" {
        edit_save_file(&args[1], &args[2], &args[3], apply_album_delete)?;
        return Ok(());
    }

    if args.len() == 4 && args[0] == "--recover" {
        edit_save_file(&args[1], &args[2], &args[3], apply_album_recover)?;
        return Ok(());
    }

    if args.len() == 4 && args[0] == "--reorder" {
        edit_save_file(&args[1], &args[2], &args[3], apply_album_reorder)?;
        return Ok(());
    }

    if args.len() == 3 && args[0] == "--clear-album" {
        let data = fs::read(&args[1])?;
        let updated = apply_album_reorder(&data, &[])?;
        fs::write(&args[2], updated)?;
        println!("Wrote cleared album state vector: {}", args[2]);
        return Ok(());
    }

    if args.len() == 3 && args[0] == "--rebuild-nonblank" {
        let data = fs::read(&args[1])?;
        let updated = apply_album_rebuild_from_nonblank_slots(&data)?;
        fs::write(&args[2], updated)?;
        println!("Wrote rebuilt album state vector: {}", args[2]);
        return Ok(());
    }

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

fn check_save(save: &[u8]) -> Result<(), Box<dyn std::error::Error>> {
    let save = GbcamSave::new(save)?;
    let state = save.state_vector_report();
    println!("Album state vector:");
    println!(
        "  primary: magic={}, checksum={}, stored={:02X?}, computed={:02X?}",
        state.primary.magic_valid,
        state.primary.checksum_valid(),
        state.primary.stored_checksum,
        state.primary.computed_checksum
    );
    println!(
        "  echo:    magic={}, checksum={}, stored={:02X?}, computed={:02X?}",
        state.echo.magic_valid,
        state.echo.checksum_valid(),
        state.echo.stored_checksum,
        state.echo.computed_checksum
    );
    println!("  selected: {:?}", state.selected);
    println!("  copies match: {}", state.copies_match());

    let settings = save.settings_block();
    println!("Settings/minigames block:");
    println!(
        "  primary: magic={}, checksum={}, stored={:02X?}, computed={:02X?}",
        settings.primary.magic_valid,
        settings.primary.checksum_valid(),
        settings.primary.stored_checksum,
        settings.primary.computed_checksum
    );
    println!(
        "  echo:    magic={}, checksum={}, stored={:02X?}, computed={:02X?}",
        settings.echo.magic_valid,
        settings.echo.checksum_valid(),
        settings.echo.stored_checksum,
        settings.echo.computed_checksum
    );
    println!("  copies match: {}", settings.copies_match);
    println!(
        "  counters raw: taken={:02X?}, erased={:02X?}, transferred={:02X?}, printed={:02X?}, received={:02X?}",
        settings.counters.pictures_taken_raw,
        settings.counters.pictures_erased_raw,
        settings.counters.pictures_transferred_raw,
        settings.counters.pictures_printed_raw,
        settings.counters.pictures_received_raw
    );
    println!(
        "  scores raw: space_fever_ii={:02X?}, ball={:02X?}, run_run_run={:02X?}",
        settings.scores.space_fever_ii_raw,
        settings.scores.ball_raw,
        settings.scores.run_run_run_raw
    );
    println!("  print intensity: 0x{:02X}", settings.printing_intensity);

    let report = save.validate();
    if report.findings.is_empty() {
        println!("No validation findings.");
    } else {
        println!("Validation findings:");
        for finding in &report.findings {
            let severity = match finding.severity {
                ValidationSeverity::Info => "info",
                ValidationSeverity::Warning => "warning",
                ValidationSeverity::Error => "error",
            };
            match finding.offset {
                Some(offset) => println!("  {severity} @ 0x{offset:05X}: {}", finding.message),
                None => println!("  {severity}: {}", finding.message),
            }
        }
    }

    if report.is_valid() {
        Ok(())
    } else {
        Err("save validation failed".into())
    }
}

fn edit_save_file(
    input: &str,
    output: &str,
    csv: &str,
    edit: fn(&[u8], &[usize]) -> Result<Vec<u8>, gbxcam_core::GbcamCoreError>,
) -> Result<(), Box<dyn std::error::Error>> {
    let data = fs::read(input)?;
    let slots = parse_slot_csv(csv)?;
    let updated = edit(&data, &slots)?;
    fs::write(output, updated)?;
    println!("Wrote updated save: {output}");
    Ok(())
}

fn parse_slot_csv(csv: &str) -> Result<Vec<usize>, Box<dyn std::error::Error>> {
    let mut slots = Vec::new();
    for part in csv
        .split(',')
        .map(str::trim)
        .filter(|part| !part.is_empty())
    {
        slots.push(part.parse()?);
    }
    Ok(slots)
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
            let Ok(data) = fs::read(&path) else {
                continue;
            };
            if data.len() >= 12 && usb_descriptor_matches_gbxcart(&data) {
                return Some(path.to_string_lossy().into_owned());
            }
        }
    }
    None
}

fn usb_descriptor_matches_gbxcart(data: &[u8]) -> bool {
    if data.len() < 12 {
        return false;
    }
    let vid = u16::from_le_bytes([data[8], data[9]]);
    let pid = u16::from_le_bytes([data[10], data[11]]);
    vid == VID_WCH && pid == PID_CH340
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn usb_descriptor_matches_gbxcart_ch340() {
        let mut descriptor = vec![0u8; 18];
        descriptor[8..10].copy_from_slice(&VID_WCH.to_le_bytes());
        descriptor[10..12].copy_from_slice(&PID_CH340.to_le_bytes());

        assert!(usb_descriptor_matches_gbxcart(&descriptor));
    }

    #[test]
    fn usb_descriptor_rejects_other_wch_products() {
        let mut descriptor = vec![0u8; 18];
        descriptor[8..10].copy_from_slice(&VID_WCH.to_le_bytes());
        descriptor[10..12].copy_from_slice(&0x5523u16.to_le_bytes());

        assert!(!usb_descriptor_matches_gbxcart(&descriptor));
    }
}
