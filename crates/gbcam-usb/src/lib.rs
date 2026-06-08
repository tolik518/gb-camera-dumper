use gbxcam_core::{
    album_order_after_delete, apply_album_recover, apply_album_reorder,
    cartridge_report_from_header, make_erase_chunk, order_table_checksum, shifted_header_hint,
    title_from_header, CartridgeReport, BANK_SIZE, DMG_READ_METHOD_A15, DMG_READ_METHOD_SLOW_A15,
    MAPPER_MAC_GBD, ORDER_CHECKSUM_OFFSET, ORDER_COUNT, ORDER_ECHO_CHECKSUM_OFFSET, ORDER_OFFSET,
    ORDER_OFFSET_PRIMARY, READ_CHUNK, SAVE_SIZE, SRAM_BANKS, WRITE_CHUNK,
};
use std::cell::RefCell;
use std::io;
use std::os::unix::io::RawFd;
use std::time::{Duration, Instant};

#[repr(C)]
struct CtrlTransfer {
    request_type: u8,
    request: u8,
    value: u16,
    index: u16,
    length: u16,
    timeout: u32,
    data: usize,
}

#[repr(C)]
struct BulkTransfer {
    ep: u32,
    len: u32,
    timeout: u32,
    data: usize,
}

#[derive(Debug, thiserror::Error)]
pub enum GbcamUsbError {
    #[error("USB error during {context}: {source}")]
    Usb {
        context: &'static str,
        #[source]
        source: io::Error,
    },
    #[error("{0}")]
    Protocol(String),
    #[error("core error: {0}")]
    Core(#[from] gbxcam_core::GbcamCoreError),
}

pub type Result<T> = std::result::Result<T, GbcamUsbError>;

#[derive(Debug, Clone)]
pub struct GbxCartInfo {
    pub pcb_ver: u8,
    pub ofw_ver: u8,
    pub cfw_ver: u16,
    pub name: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CartridgeReaderKind {
    GbxCartRw14,
}

#[derive(Debug, Clone)]
pub enum CartridgeReaderInfo {
    GbxCartRw14(GbxCartInfo),
}

impl CartridgeReaderInfo {
    pub fn kind(&self) -> CartridgeReaderKind {
        match self {
            CartridgeReaderInfo::GbxCartRw14(_) => CartridgeReaderKind::GbxCartRw14,
        }
    }

    pub fn gbxcart_info(&self) -> Option<&GbxCartInfo> {
        match self {
            CartridgeReaderInfo::GbxCartRw14(info) => Some(info),
        }
    }
}

pub trait Progress {
    fn message(&mut self, _message: &str) {}
    fn cartridge_header(&mut self, _report: &CartridgeReport) {}
    fn sram_progress(&mut self, _done_bytes: usize, _total_bytes: usize) {}
    fn write_progress(&mut self, _done: usize, _total: usize) {}
}

pub struct NoProgress;

impl Progress for NoProgress {}

fn iowr(type_char: u8, nr: u8, size: usize) -> libc::Ioctl {
    ((3u32 << 30) | ((size as u32) << 16) | ((type_char as u32) << 8) | (nr as u32)) as libc::Ioctl
}

fn iow(type_char: u8, nr: u8, size: usize) -> libc::Ioctl {
    ((2u32 << 30) | ((size as u32) << 16) | ((type_char as u32) << 8) | (nr as u32)) as libc::Ioctl
}

fn usbdevfs_control() -> libc::Ioctl {
    iowr(b'U', 0, std::mem::size_of::<CtrlTransfer>())
}

fn usbdevfs_bulk() -> libc::Ioctl {
    iowr(b'U', 2, std::mem::size_of::<BulkTransfer>())
}

fn usbdevfs_claiminterface() -> libc::Ioctl {
    iow(b'U', 15, std::mem::size_of::<u32>())
}

fn usbdevfs_resetep() -> libc::Ioctl {
    iow(b'U', 3, std::mem::size_of::<u32>())
}

const USB_TYPE_VENDOR: u8 = 0x40;
const USB_DIR_IN: u8 = 0x80;

const EP_OUT: u32 = 0x02;
const EP_IN: u32 = 0x82;

const BULK_TIMEOUT: u32 = 3000;
const CTRL_TIMEOUT: u32 = 1000;

const CMD_NULL: u8 = 0x30;
const CMD_OFW_DONE_LED_ON: u8 = 0x3D;
const CMD_OFW_ERROR_LED_ON: u8 = 0x3F;
const CMD_OFW_CART_MODE: u8 = 0x43;
const CMD_OFW_PCB_VER: u8 = 0x68;
const CMD_OFW_FW_VER: u8 = 0x56;
const CMD_QUERY_FW_INFO: u8 = 0xA1;
const CMD_SET_MODE_DMG: u8 = 0xA3;
const CMD_SET_VOLTAGE_5V: u8 = 0xA5;
const CMD_SET_VARIABLE: u8 = 0xA6;
const CMD_DMG_CART_READ: u8 = 0xB1;
const CMD_DMG_CART_WRITE: u8 = 0xB2;
const CMD_DMG_CART_WRITE_SRAM: u8 = 0xB3;
const CMD_DMG_MBC_RESET: u8 = 0xB4;
const CMD_QUERY_CART_PWR: u8 = 0xF4;
const CMD_CART_PWR_ON: u8 = 0xF2;
const CMD_CART_PWR_OFF: u8 = 0xF3;

const VAR_ADDRESS: (u8, u8) = (32, 0x00);
const VAR_TRANSFER_SIZE: (u8, u8) = (16, 0x00);
const VAR_CART_MODE: (u8, u8) = (8, 0x00);
const VAR_DMG_ACCESS_MODE: (u8, u8) = (8, 0x01);
const VAR_DMG_READ_CS_PULSE: (u8, u8) = (8, 0x08);
const VAR_DMG_WRITE_CS_PULSE: (u8, u8) = (8, 0x09);
const VAR_DMG_READ_METHOD: (u8, u8) = (8, 0x0B);

const CH340_PACKET: usize = 64;
const SRAM_VERIFY_WINDOW: usize = 0x200;
const SRAM_VERIFY_RETRIES: usize = 3;

pub enum CartridgeReader {
    GbxCartRw14(UsbDev),
}

impl CartridgeReader {
    pub fn connect(fd: RawFd, progress: &mut impl Progress) -> Result<(Self, CartridgeReaderInfo)> {
        progress.message("Connecting to GBxCart RW 1.4...");
        let (dev, info) = UsbDev::connect(fd, progress)?;
        Ok((
            CartridgeReader::GbxCartRw14(dev),
            CartridgeReaderInfo::GbxCartRw14(info),
        ))
    }

    pub fn finish_operation(&self, success: bool, progress: &mut impl Progress) {
        match self {
            CartridgeReader::GbxCartRw14(dev) => dev.finish_operation(success, progress),
        }
    }

    pub fn read_cartridge_report(&self) -> Result<CartridgeReport> {
        match self {
            CartridgeReader::GbxCartRw14(dev) => dev.read_cartridge_report(),
        }
    }

    pub fn dump_save(&self, progress: &mut impl Progress) -> Result<Vec<u8>> {
        match self {
            CartridgeReader::GbxCartRw14(dev) => dev.dump_save(progress),
        }
    }

    pub fn erase_save(&self, save_backup: &[u8], progress: &mut impl Progress) -> Result<()> {
        match self {
            CartridgeReader::GbxCartRw14(dev) => dev.erase_save(save_backup, progress),
        }
    }

    pub fn delete_album_photos(
        &self,
        save_backup: &[u8],
        physical_slots: &[usize],
        progress: &mut impl Progress,
    ) -> Result<[u8; ORDER_COUNT]> {
        match self {
            CartridgeReader::GbxCartRw14(dev) => {
                dev.delete_album_photos(save_backup, physical_slots, progress)
            }
        }
    }

    pub fn recover_album_photos(
        &self,
        save_backup: &[u8],
        physical_slots: &[usize],
        progress: &mut impl Progress,
    ) -> Result<[u8; ORDER_COUNT]> {
        match self {
            CartridgeReader::GbxCartRw14(dev) => {
                dev.recover_album_photos(save_backup, physical_slots, progress)
            }
        }
    }

    pub fn reorder_album_photos(
        &self,
        save_backup: &[u8],
        physical_slots_in_display_order: &[usize],
        progress: &mut impl Progress,
    ) -> Result<[u8; ORDER_COUNT]> {
        match self {
            CartridgeReader::GbxCartRw14(dev) => {
                dev.reorder_album_photos(save_backup, physical_slots_in_display_order, progress)
            }
        }
    }
}

pub struct UsbDev {
    fd: RawFd,
    fw_ver: u16,
    has_cart_power: bool,
    rxbuf: RefCell<(Vec<u8>, usize)>,
}

impl UsbDev {
    pub fn new(fd: RawFd) -> Self {
        UsbDev {
            fd,
            fw_ver: 0,
            has_cart_power: false,
            rxbuf: RefCell::new((Vec::new(), 0)),
        }
    }

    pub fn ioctl_layout() -> (usize, usize, usize) {
        (
            std::mem::size_of::<BulkTransfer>(),
            std::mem::size_of::<CtrlTransfer>(),
            std::mem::size_of::<usize>(),
        )
    }

    pub fn connect(fd: RawFd, progress: &mut impl Progress) -> Result<(Self, GbxCartInfo)> {
        progress.message("Initializing CH340 and setting 1 Mbaud...");
        let mut dev = UsbDev::new(fd);
        dev.claim_interface()?;
        dev.flush_endpoints();
        ch340_initialize(&dev)?;
        ch340_set_baud_rate(&dev, 1_000_000)?;
        std::thread::sleep(Duration::from_millis(200));
        dev.flush_endpoints();
        dev.drain_input(20, 16)?;
        std::thread::sleep(Duration::from_millis(60));

        let pcb_ver = dev.query_u8_command(CMD_OFW_PCB_VER, "OFW_PCB_VER")?;
        let ofw_ver = dev.query_u8_command(CMD_OFW_FW_VER, "OFW_FW_VER")?;

        if pcb_ver == 0 {
            return Err(GbcamUsbError::Protocol(
                "No response from GBxCart RW 1.4. Check connections.".to_string(),
            ));
        }

        let mut name = None;
        if pcb_ver >= 5 && ofw_ver > 0 {
            let size = dev.query_u8_command(CMD_QUERY_FW_INFO, "QUERY_FW_INFO length")? as usize;
            let mut info = vec![0u8; size];
            dev.bulk_read(&mut info)?;
            if size >= 3 {
                dev.fw_ver = u16::from_be_bytes([info[1], info[2]]);
            }
            if dev.fw_ver >= 12 {
                let nlen = dev.read_u8()? as usize;
                let mut nbuf = vec![0u8; nlen];
                dev.bulk_read(&mut nbuf)?;
                name = Some(
                    String::from_utf8_lossy(&nbuf)
                        .trim_matches('\0')
                        .to_string(),
                );
                dev.has_cart_power = dev.read_u8()? == 1;
                let _boot = dev.read_u8()?;
            }
        }

        progress.message(&format!(
            "[debug][gbxcart] firmware: pcb=0x{pcb_ver:02X} ofw=R{ofw_ver} cfw=L{} cart_power_query_supported={}",
            dev.fw_ver, dev.has_cart_power
        ));
        dev.debug_cart_power(progress, "connect: after firmware query, before LED reset");
        dev.reset_status_leds(progress, "connect: reset status/mode LEDs");
        dev.debug_cart_power(progress, "connect: after LED reset");

        let info = GbxCartInfo {
            pcb_ver,
            ofw_ver,
            cfw_ver: dev.fw_ver,
            name,
        };
        Ok((dev, info))
    }

    fn claim_interface(&self) -> Result<()> {
        let iface: u32 = 0;
        let ret = unsafe { libc::ioctl(self.fd, usbdevfs_claiminterface(), &iface as *const u32) };
        if ret < 0 {
            return Err(last_usb_error("claim USB interface"));
        }
        Ok(())
    }

    fn flush_endpoints(&self) {
        let mut ep_in: u32 = EP_IN;
        let mut ep_out: u32 = EP_OUT;
        unsafe {
            libc::ioctl(self.fd, usbdevfs_resetep(), &mut ep_in as *mut u32);
            libc::ioctl(self.fd, usbdevfs_resetep(), &mut ep_out as *mut u32);
        }
        self.clear_local_rx();
    }

    fn ctrl_out(&self, request: u8, value: u16, index: u16) -> Result<()> {
        let mut x = CtrlTransfer {
            request_type: USB_TYPE_VENDOR,
            request,
            value,
            index,
            length: 0,
            timeout: CTRL_TIMEOUT,
            data: 0,
        };
        let ret = unsafe { libc::ioctl(self.fd, usbdevfs_control(), &mut x) };
        if ret < 0 {
            return Err(last_usb_error("USB control out"));
        }
        Ok(())
    }

    fn ctrl_in(&self, request: u8, value: u16, index: u16, buf: &mut [u8]) -> Result<()> {
        let mut x = CtrlTransfer {
            request_type: USB_TYPE_VENDOR | USB_DIR_IN,
            request,
            value,
            index,
            length: buf.len() as u16,
            timeout: CTRL_TIMEOUT,
            data: buf.as_mut_ptr() as usize,
        };
        let ret = unsafe { libc::ioctl(self.fd, usbdevfs_control(), &mut x) };
        if ret < 0 {
            return Err(last_usb_error("USB control in"));
        }
        if ret as usize != buf.len() {
            return Err(GbcamUsbError::Protocol(format!(
                "USB control in request 0x{:02X} returned {} byte(s), expected {}",
                request,
                ret,
                buf.len()
            )));
        }
        Ok(())
    }

    fn bulk_write(&self, data: &[u8]) -> Result<()> {
        let deadline = Instant::now() + Duration::from_secs(5);
        let mut sent = 0;
        while sent < data.len() {
            let slice = &data[sent..];
            let mut x = BulkTransfer {
                ep: EP_OUT,
                len: slice.len() as u32,
                timeout: BULK_TIMEOUT,
                data: slice.as_ptr() as usize,
            };
            let ret = unsafe { libc::ioctl(self.fd, usbdevfs_bulk(), &mut x) };
            if ret < 0 {
                let e = io::Error::last_os_error();
                if e.raw_os_error() == Some(libc::ETIMEDOUT) && Instant::now() < deadline {
                    continue;
                }
                return Err(GbcamUsbError::Usb {
                    context: "USB bulk write",
                    source: e,
                });
            }
            sent += ret as usize;
        }
        Ok(())
    }

    fn raw_bulk_read_once(&self, timeout: u32) -> Result<Vec<u8>> {
        let mut raw = vec![0u8; CH340_PACKET];
        let mut x = BulkTransfer {
            ep: EP_IN,
            len: CH340_PACKET as u32,
            timeout,
            data: raw.as_mut_ptr() as usize,
        };
        let ret = unsafe { libc::ioctl(self.fd, usbdevfs_bulk(), &mut x) };
        if ret < 0 {
            return Err(GbcamUsbError::Usb {
                context: "USB bulk read",
                source: io::Error::last_os_error(),
            });
        }
        raw.truncate(ret as usize);
        Ok(raw)
    }

    fn raw_bulk_read(&self) -> Result<Vec<u8>> {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            match self.raw_bulk_read_once(BULK_TIMEOUT) {
                Ok(raw) => return Ok(raw),
                Err(e) if is_usb_timeout(&e) && Instant::now() < deadline => continue,
                Err(e) => return Err(e),
            }
        }
    }

    fn drain_input(&self, timeout: u32, max_packets: usize) -> Result<()> {
        self.clear_local_rx();
        for _ in 0..max_packets {
            match self.raw_bulk_read_once(timeout) {
                Ok(raw) if raw.is_empty() => break,
                Ok(_) => {}
                Err(e) if is_usb_timeout(&e) => break,
                Err(e) => return Err(e),
            }
        }
        self.clear_local_rx();
        Ok(())
    }

    fn bulk_read(&self, buf: &mut [u8]) -> Result<()> {
        let mut rxbuf = self.rxbuf.borrow_mut();
        let mut got = 0;
        while got < buf.len() {
            if rxbuf.1 >= rxbuf.0.len() {
                rxbuf.0 = self.raw_bulk_read()?;
                rxbuf.1 = 0;
            }
            let pos = rxbuf.1;
            let take = (buf.len() - got).min(rxbuf.0.len() - pos);
            buf[got..got + take].copy_from_slice(&rxbuf.0[pos..pos + take]);
            rxbuf.1 += take;
            got += take;
        }
        Ok(())
    }

    fn read_u8(&self) -> Result<u8> {
        let mut b = [0u8; 1];
        self.bulk_read(&mut b)?;
        Ok(b[0])
    }

    fn query_u8_command(&self, cmd: u8, ctx: &'static str) -> Result<u8> {
        for attempt in 1..=3 {
            self.clear_local_rx();
            self.bulk_write(&[cmd])?;
            match self.read_u8() {
                Ok(value) => return Ok(value),
                Err(e) if is_usb_timeout(&e) && attempt < 3 => {
                    self.clear_local_rx();
                    self.flush_endpoints();
                    let _ = self.drain_input(20, 16);
                    std::thread::sleep(Duration::from_millis(80));
                }
                Err(e) => {
                    return Err(GbcamUsbError::Protocol(format!("{ctx}: {e}")));
                }
            }
        }

        Err(GbcamUsbError::Protocol(format!("{ctx}: no response")))
    }

    fn ack_byte(&self) -> Result<u8> {
        self.read_u8()
    }

    fn ack_ok(b: u8) -> bool {
        matches!(b, 0x01 | 0x03)
    }

    fn ack_meaning(b: u8) -> &'static str {
        match b {
            0x01 => "OK",
            0x02 => "device reported error",
            0x03 => "OK / busy-continue",
            _ => "unexpected response",
        }
    }

    fn clear_local_rx(&self) {
        let mut r = self.rxbuf.borrow_mut();
        r.0.clear();
        r.1 = 0;
    }

    fn resync_after_bad_ack(&self) -> Result<()> {
        self.clear_local_rx();
        self.flush_endpoints();
        self.drain_input(20, 32)?;

        // FlashGBX recovers failed acknowledged writes by clearing buffers and
        // sending the firmware NULL command until the command parser answers.
        // Endpoint resets alone do not necessarily realign the GBxCart stream.
        for _ in 0..20 {
            self.bulk_write(&[CMD_NULL])?;
            for _ in 0..4 {
                match self.raw_bulk_read_once(100) {
                    Ok(raw) => {
                        if raw.iter().any(|b| matches!(b, 0x01 | 0x02)) {
                            self.clear_local_rx();
                            return Ok(());
                        }
                    }
                    Err(e) if is_usb_timeout(&e) => break,
                    Err(e) => return Err(e),
                }
            }
            self.clear_local_rx();
            self.flush_endpoints();
            self.drain_input(20, 32)?;
            std::thread::sleep(Duration::from_millis(20));
        }

        Ok(())
    }

    fn debug_result(progress: &mut impl Progress, label: &str, result: Result<()>) -> bool {
        match result {
            Ok(()) => {
                progress.message(&format!("[debug][gbxcart] OK: {label}"));
                true
            }
            Err(e) => {
                progress.message(&format!("[debug][gbxcart] ERROR: {label}: {e}"));
                false
            }
        }
    }

    fn required_step(
        progress: &mut impl Progress,
        label: &str,
        result: Result<()>,
    ) -> Result<()> {
        match result {
            Ok(()) => {
                progress.message(&format!("[debug][gbxcart] OK: {label}"));
                Ok(())
            }
            Err(e) => {
                progress.message(&format!("[debug][gbxcart] ERROR: {label}: {e}"));
                Err(e)
            }
        }
    }

    fn debug_cart_power(&self, progress: &mut impl Progress, label: &str) {
        if !self.has_cart_power {
            progress.message(&format!(
                "[debug][power] {label}: CART_PWR query unsupported by this firmware path"
            ));
            return;
        }

        self.clear_local_rx();
        match self.bulk_write(&[CMD_QUERY_CART_PWR]) {
            Ok(()) => match self.raw_bulk_read_once(400) {
                Ok(raw) if raw.is_empty() => {
                    progress.message(&format!(
                        "[debug][power] {label}: CART_PWR query returned empty packet"
                    ));
                }
                Ok(raw) => {
                    let (value, source) =
                        if raw.len() > 1 && Self::ack_ok(raw[0]) && (raw[1] == 0 || raw[1] == 1) {
                            (raw[1], "raw[1] after leading ACK-like byte")
                        } else {
                            (raw[0], "raw[0]")
                        };
                    let meaning = match value {
                        0 => "OFF",
                        1 => "ON",
                        _ => "unknown",
                    };
                    progress.message(&format!(
                        "[debug][power] {label}: CART_PWR=0x{value:02X} ({meaning}) from {source}; raw={raw:02X?}"
                    ));
                }
                Err(e) => {
                    progress.message(&format!(
                        "[debug][power] {label}: CART_PWR query read failed: {e}"
                    ));
                }
            },
            Err(e) => {
                progress.message(&format!(
                    "[debug][power] {label}: CART_PWR query write failed: {e}"
                ));
            }
        }
        self.clear_local_rx();
    }

    fn raw_led_command(&self, progress: &mut impl Progress, label: &str, cmd: u8, expected: &str) {
        progress.message(&format!(
            "[debug][led] SEND 0x{cmd:02X}: {label}; expected LED effect: {expected}"
        ));
        match self.bulk_write(&[cmd]) {
            Ok(()) => {
                progress.message(&format!(
                    "[debug][led] OK: {label} sent; not requiring ACK for LED-only command"
                ));
                match self.raw_bulk_read_once(50) {
                    Ok(raw) if raw.is_empty() => {}
                    Ok(raw) => {
                        progress.message(&format!(
                            "[debug][led] {label}: drained optional response raw={raw:02X?}"
                        ));
                    }
                    Err(e) if is_usb_timeout(&e) => {}
                    Err(e) => {
                        progress.message(&format!(
                            "[debug][led] {label}: optional response drain failed: {e}"
                        ));
                    }
                }
                self.clear_local_rx();
            }
            Err(e) => {
                progress.message(&format!("[debug][led] ERROR: {label}: {e}"));
            }
        }
    }

    fn reset_status_leds(&self, progress: &mut impl Progress, label: &str) {
        self.raw_led_command(
            progress,
            label,
            CMD_OFW_CART_MODE,
            "reset Mode/Error and Done/Status LEDs according to FlashGBX notes",
        );
    }

    fn power_off_cart(&self, progress: &mut impl Progress, label: &str) {
        if self.has_cart_power {
            progress.message(&format!(
                "[debug][power] SEND 0x{CMD_CART_PWR_OFF:02X}: {label}; expected LED effect: Power LED off"
            ));
            if self.fw_ver >= 10 {
                let result = self.write_wait_retry(&[CMD_CART_PWR_OFF], label, 3);
                Self::debug_result(progress, &format!("{label} acknowledged"), result);
            } else {
                Self::debug_result(progress, label, self.bulk_write(&[CMD_CART_PWR_OFF]));
            }
            Self::debug_result(
                progress,
                "power-off cleanup: drain input after CART_PWR_OFF",
                self.drain_input(20, 8),
            );
            self.debug_cart_power(progress, "after CART_PWR_OFF");
        } else {
            progress.message(&format!(
                "[debug][power] SKIP {label}: cart power control unsupported"
            ));
        }
    }

    pub fn finish_session(&self, progress: &mut impl Progress) {
        progress.message("[debug][session] finish: begin cleanup. Firmware cannot report actual LED pin states; log shows commands sent plus CART_PWR query.");
        self.debug_cart_power(progress, "finish: entry");
        Self::debug_result(
            progress,
            "finish: drain stale input before cleanup",
            self.drain_input(20, 32),
        );
        Self::debug_result(
            progress,
            "finish: SET_VARIABLE DMG_READ_CS_PULSE=0",
            self.set_var(VAR_DMG_READ_CS_PULSE, 0),
        );
        Self::debug_result(
            progress,
            "finish: SET_VARIABLE DMG_WRITE_CS_PULSE=0",
            self.set_var(VAR_DMG_WRITE_CS_PULSE, 0),
        );
        Self::debug_result(
            progress,
            "finish: cart_write 0x0000=0x00 (disable cartridge RAM)",
            self.cart_write(0x0000, 0x00),
        );
        self.debug_cart_power(progress, "finish: before LED reset");
        self.reset_status_leds(progress, "finish: reset status/mode LEDs");
        self.debug_cart_power(progress, "finish: after LED reset, before power off");
        self.power_off_cart(progress, "finish: power off cartridge slot");
        self.clear_local_rx();
        self.flush_endpoints();
        progress.message("[debug][session] finish: endpoint buffers reset locally");
    }

    pub fn finish_operation(&self, success: bool, progress: &mut impl Progress) {
        if success {
            self.mark_session_done(progress);
        } else {
            progress.message("[debug][session] failure path: skipping OFW_ERROR_LED_ON so diagnostics do not intentionally leave Mode/Error LED red");
        }
        self.finish_session(progress);
    }

    pub fn mark_session_error(&self, progress: &mut impl Progress) {
        self.raw_led_command(
            progress,
            "session result: mark failure/error",
            CMD_OFW_ERROR_LED_ON,
            "Mode/Error LED red",
        );
    }

    pub fn mark_session_done(&self, progress: &mut impl Progress) {
        self.raw_led_command(
            progress,
            "session result: mark success/done",
            CMD_OFW_DONE_LED_ON,
            "Done/Status LED green",
        );
    }

    fn write_wait_retry(&self, data: &[u8], ctx: &str, retries: usize) -> Result<()> {
        for attempt in 0..retries {
            self.clear_local_rx();
            self.bulk_write(data)?;
            let b = self.ack_byte()?;
            if Self::ack_ok(b) {
                return Ok(());
            }
            if attempt + 1 < retries {
                self.resync_after_bad_ack()?;
                std::thread::sleep(Duration::from_millis(80));
            } else {
                return Err(GbcamUsbError::Protocol(format!(
                    "{}: ack 0x{:02X} ({}) after {} attempts",
                    ctx,
                    b,
                    Self::ack_meaning(b),
                    retries
                )));
            }
        }
        Ok(()) // reached only when retries == 0
    }

    fn expect_ack(&self, ctx: &'static str) -> Result<()> {
        let b = self.ack_byte()?;
        if !Self::ack_ok(b) {
            return Err(GbcamUsbError::Protocol(format!(
                "{}: ack 0x{:02X} ({})",
                ctx,
                b,
                Self::ack_meaning(b)
            )));
        }
        Ok(())
    }

    fn cmd_ack_named(&self, byte: u8, name: &'static str) -> Result<()> {
        if self.fw_ver >= 10 {
            self.write_wait_retry(&[byte], name, 5)
        } else {
            self.bulk_write(&[byte])
        }
    }

    fn cmd_ack(&self, byte: u8) -> Result<()> {
        let name = match byte {
            CMD_SET_MODE_DMG => "SET_MODE_DMG",
            CMD_SET_VOLTAGE_5V => "SET_VOLTAGE_5V",
            CMD_DMG_MBC_RESET => "DMG_MBC_RESET",
            _ => "command-with-ack",
        };
        self.cmd_ack_named(byte, name)
    }

    fn set_var(&self, var: (u8, u8), value: u32) -> Result<()> {
        let (size_bits, key) = var;
        let mut buf = [0u8; 10];
        buf[0] = CMD_SET_VARIABLE;
        buf[1] = size_bits / 8;
        buf[2..6].copy_from_slice(&(key as u32).to_be_bytes());
        buf[6..10].copy_from_slice(&value.to_be_bytes());

        if self.fw_ver >= 12 {
            let ctx = format!(
                "SET_VARIABLE size={} key=0x{:02X} value=0x{:X}",
                size_bits / 8,
                key,
                value
            );
            self.write_wait_retry(&buf, &ctx, 5)
        } else {
            self.bulk_write(&buf)
        }
    }

    fn cart_write(&self, address: u16, value: u8) -> Result<()> {
        let mut buf = [0u8; 6];
        buf[0] = CMD_DMG_CART_WRITE;
        buf[1..5].copy_from_slice(&(address as u32).to_be_bytes());
        buf[5] = value;
        if self.fw_ver >= 12 {
            self.write_wait_retry(&buf, "DMG_CART_WRITE", 5)
        } else {
            self.bulk_write(&buf)
        }
    }

    fn sram_write_chunk(&self, gb_addr: u32, data: &[u8]) -> Result<()> {
        if data.is_empty() {
            return Ok(());
        }
        if data.len() > WRITE_CHUNK {
            return Err(GbcamUsbError::Protocol(format!(
                "internal error: SRAM write chunk too large: {} bytes",
                data.len()
            )));
        }

        for attempt in 1..=3 {
            self.set_var(VAR_TRANSFER_SIZE, data.len() as u32)?;
            self.set_var(VAR_ADDRESS, gb_addr)?;
            self.set_var(VAR_DMG_ACCESS_MODE, 4)?;
            self.set_var(VAR_DMG_WRITE_CS_PULSE, 1)?;

            self.bulk_write(&[CMD_DMG_CART_WRITE_SRAM])?;
            self.bulk_write(data)?;
            let b = self.ack_byte()?;
            if Self::ack_ok(b) {
                return Ok(());
            }

            self.clear_local_rx();
            std::thread::sleep(Duration::from_millis(120));
            if attempt == 3 {
                return Err(GbcamUsbError::Protocol(format!(
                    "DMG_CART_WRITE_SRAM {} bytes @0x{:04X}: ack 0x{:02X} ({}) after 3 attempts",
                    data.len(),
                    gb_addr,
                    b,
                    Self::ack_meaning(b)
                )));
            }
        }
        Ok(())
    }

    fn sram_write_byte(&self, gb_addr: u32, value: u8, progress: &mut impl Progress) -> Result<()> {
        // Match FlashGBX's _cart_write(..., sram=True) ordering exactly. The
        // firmware path for single-byte SRAM writes is not the same as bulk
        // WriteRAM(): it does not set DMG_ACCESS_MODE before issuing 0xB3.
        Self::required_step(
            progress,
            "byte-write: SET_VARIABLE DMG_WRITE_CS_PULSE=1",
            self.set_var(VAR_DMG_WRITE_CS_PULSE, 1),
        )?;
        Self::required_step(
            progress,
            &format!("byte-write: SET_VARIABLE ADDRESS=0x{gb_addr:04X}"),
            self.set_var(VAR_ADDRESS, gb_addr),
        )?;
        Self::required_step(
            progress,
            "byte-write: SET_VARIABLE TRANSFER_SIZE=1",
            self.set_var(VAR_TRANSFER_SIZE, 1),
        )?;

        progress.message(&format!(
            "[debug][byte-write] SEND 0x{CMD_DMG_CART_WRITE_SRAM:02X} DMG_CART_WRITE_SRAM for 0x{gb_addr:04X}=0x{value:02X}"
        ));
        Self::required_step(
            progress,
            "byte-write: send DMG_CART_WRITE_SRAM command",
            self.bulk_write(&[CMD_DMG_CART_WRITE_SRAM]),
        )?;

        progress.message(&format!("[debug][byte-write] SEND data byte 0x{value:02X}"));
        Self::required_step(
            progress,
            "byte-write: send data byte",
            self.bulk_write(&[value]),
        )?;

        progress.message("[debug][byte-write] waiting for byte write ACK");
        Self::required_step(
            progress,
            "byte-write: DMG_CART_WRITE_SRAM byte ACK",
            self.expect_ack("DMG_CART_WRITE_SRAM byte"),
        )
    }

    pub fn read_cartridge_header(&self) -> Result<Vec<u8>> {
        self.set_var(VAR_TRANSFER_SIZE, READ_CHUNK as u32)?;
        self.set_var(VAR_DMG_ACCESS_MODE, 1)?;

        let mut header = vec![0u8; 0x180];
        for offset in (0..0x180usize).step_by(READ_CHUNK) {
            self.set_var(VAR_ADDRESS, offset as u32)?;
            self.bulk_write(&[CMD_DMG_CART_READ])?;
            self.bulk_read(&mut header[offset..offset + READ_CHUNK])?;
        }
        Ok(header)
    }

    fn read_sram_window(&self, gb_addr: u32, out: &mut [u8]) -> Result<()> {
        if !out.len().is_multiple_of(READ_CHUNK) {
            return Err(GbcamUsbError::Protocol(format!(
                "internal error: SRAM read size {} is not aligned to {}",
                out.len(),
                READ_CHUNK
            )));
        }

        self.set_var(VAR_TRANSFER_SIZE, READ_CHUNK as u32)?;
        self.set_var(VAR_ADDRESS, gb_addr)?;
        self.set_var(VAR_DMG_ACCESS_MODE, 3)?;
        self.set_var(VAR_DMG_READ_CS_PULSE, 1)?;
        let read_result = (|| {
            for sub in (0..out.len()).step_by(READ_CHUNK) {
                self.bulk_write(&[CMD_DMG_CART_READ])?;
                self.bulk_read(&mut out[sub..sub + READ_CHUNK])?;
            }
            Ok(())
        })();
        read_result.and(self.set_var(VAR_DMG_READ_CS_PULSE, 0))
    }

    fn read_sram_window_verified(&self, gb_addr: u32, len: usize) -> Result<Vec<u8>> {
        let mut first = vec![0u8; len];
        let mut second = vec![0u8; len];

        // Bottleneck: reads every window twice and compares; doubles USB traffic.
        // Required because the MAC-GBD chip can return stale or glitched data on
        // the first SRAM access after a bank switch.
        for attempt in 1..=SRAM_VERIFY_RETRIES {
            self.read_sram_window(gb_addr, &mut first)?;
            self.read_sram_window(gb_addr, &mut second)?;
            if first == second {
                return Ok(first);
            }

            self.drain_input(20, 32)?;
            std::thread::sleep(Duration::from_millis(20 * attempt as u64));
        }

        Err(GbcamUsbError::Protocol(format!(
            "unstable SRAM read at 0x{:04X}: {} byte window differed after {} attempts",
            gb_addr, len, SRAM_VERIFY_RETRIES
        )))
    }

    pub fn dump_save(&self, progress: &mut impl Progress) -> Result<Vec<u8>> {
        self.prepare_dmg_cart(progress)?;

        progress.message("Reading cartridge header using A15...");
        self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;
        self.set_var(VAR_DMG_READ_CS_PULSE, 0)?;

        let mut header = self.read_cartridge_header()?;
        let mut mapper = header[0x147];

        if mapper != MAPPER_MAC_GBD {
            self.set_var(VAR_DMG_READ_METHOD, DMG_READ_METHOD_SLOW_A15)?;
            std::thread::sleep(Duration::from_millis(50));
            header = self.read_cartridge_header()?;
            mapper = header[0x147];
        }

        if mapper != MAPPER_MAC_GBD {
            return Err(header_mismatch_error(&header, mapper));
        }
        if let Some(report) = cartridge_report_from_header(&header) {
            progress.cartridge_header(&report);
        }

        self.backup_restore_ram_preamble()?;

        progress.message("Dumping SRAM...");
        // Bottleneck: 16 banks × 16 verified windows per bank × 2 reads each =
        // 512 USB bulk reads, each preceded by 3–4 set_var round-trips. Total
        // USB operation count is dominated by the firmware's per-window overhead.
        let mut save = vec![0u8; SAVE_SIZE];
        for bank in 0..SRAM_BANKS {
            self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;
            self.cart_write(0x4000, bank as u8)?;
            std::thread::sleep(Duration::from_millis(3));

            for buf_offset in (0..BANK_SIZE).step_by(SRAM_VERIFY_WINDOW) {
                let chunk =
                    self.read_sram_window_verified(0xA000 + buf_offset as u32, SRAM_VERIFY_WINDOW)?;
                let out = bank * BANK_SIZE + buf_offset;
                save[out..out + SRAM_VERIFY_WINDOW].copy_from_slice(&chunk);
            }
            progress.sram_progress((bank + 1) * BANK_SIZE, SAVE_SIZE);
        }

        self.cart_write(0x0000, 0x00)?;
        self.set_var(VAR_DMG_READ_CS_PULSE, 0)?;
        Ok(save)
    }

    pub fn read_cartridge_report(&self) -> Result<CartridgeReport> {
        let mut progress = NoProgress;
        self.prepare_dmg_cart(&mut progress)?;
        self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;
        self.set_var(VAR_DMG_READ_CS_PULSE, 0)?;
        let header = self.read_cartridge_header()?;
        cartridge_report_from_header(&header)
            .ok_or_else(|| GbcamUsbError::Protocol("short cartridge header".to_string()))
    }

    pub fn erase_save(&self, save_backup: &[u8], progress: &mut impl Progress) -> Result<()> {
        if save_backup.len() != SAVE_SIZE {
            return Err(gbxcam_core::GbcamCoreError::InvalidBackupSize {
                actual: save_backup.len(),
                expected: SAVE_SIZE,
            }
            .into());
        }

        self.prepare_dmg_cart(progress)?;
        progress.message("Erasing SRAM...");
        self.set_var(VAR_DMG_READ_CS_PULSE, 0)?;
        self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;
        self.cart_write(0x0000, 0x0A)?;
        std::thread::sleep(Duration::from_millis(20));

        for bank in 0..SRAM_BANKS {
            self.cart_write(0x4000, bank as u8)?;
            std::thread::sleep(Duration::from_millis(2));

            for bank_off in (0..BANK_SIZE).step_by(WRITE_CHUNK) {
                let abs_off = bank * BANK_SIZE + bank_off;
                let chunk_len = WRITE_CHUNK.min(BANK_SIZE - bank_off);
                let chunk = make_erase_chunk(save_backup, abs_off, chunk_len)?;
                self.sram_write_chunk(0xA000 + bank_off as u32, &chunk)?;
            }
            progress.sram_progress((bank + 1) * BANK_SIZE, SAVE_SIZE);
        }

        self.cart_write(0x0000, 0x00)?;
        self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;
        self.set_var(VAR_DMG_READ_CS_PULSE, 0)?;
        Ok(())
    }

    pub fn delete_album_photos(
        &self,
        save_backup: &[u8],
        physical_slots: &[usize],
        progress: &mut impl Progress,
    ) -> Result<[u8; ORDER_COUNT]> {
        let order = album_order_after_delete(save_backup, physical_slots)?;

        self.prepare_dmg_cart(progress)?;
        self.verify_cached_album_state(save_backup, progress)?;
        progress.message("Deleting selected photos from camera album...");
        self.write_album_order_verified(&order, progress)?;
        self.cart_write(0x0000, 0x00)?;
        self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;
        self.set_var(VAR_DMG_READ_CS_PULSE, 0)?;
        progress.message("Selected photos deleted from album order.");
        Ok(order)
    }

    pub fn recover_album_photos(
        &self,
        save_backup: &[u8],
        physical_slots: &[usize],
        progress: &mut impl Progress,
    ) -> Result<[u8; ORDER_COUNT]> {
        let updated = apply_album_recover(save_backup, physical_slots)?;
        let mut order = [0u8; ORDER_COUNT];
        order.copy_from_slice(&updated[ORDER_OFFSET_PRIMARY..ORDER_OFFSET_PRIMARY + ORDER_COUNT]);

        self.prepare_dmg_cart(progress)?;
        self.verify_cached_album_state(save_backup, progress)?;
        progress.message("Recovering selected deleted photos into camera album...");
        self.write_album_order_verified(&order, progress)?;
        self.cart_write(0x0000, 0x00)?;
        self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;
        self.set_var(VAR_DMG_READ_CS_PULSE, 0)?;
        progress.message("Selected deleted photos recovered into album order.");
        Ok(order)
    }

    pub fn reorder_album_photos(
        &self,
        save_backup: &[u8],
        physical_slots_in_display_order: &[usize],
        progress: &mut impl Progress,
    ) -> Result<[u8; ORDER_COUNT]> {
        let updated = apply_album_reorder(save_backup, physical_slots_in_display_order)?;
        let mut order = [0u8; ORDER_COUNT];
        order.copy_from_slice(&updated[ORDER_OFFSET_PRIMARY..ORDER_OFFSET_PRIMARY + ORDER_COUNT]);

        self.prepare_dmg_cart(progress)?;
        self.verify_cached_album_state(save_backup, progress)?;
        progress.message("Rewriting camera album order...");
        self.write_album_order_verified(&order, progress)?;
        self.cart_write(0x0000, 0x00)?;
        self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;
        self.set_var(VAR_DMG_READ_CS_PULSE, 0)?;
        progress.message("Camera album order updated.");
        Ok(order)
    }

    fn verify_cached_album_state(
        &self,
        save_backup: &[u8],
        progress: &mut impl Progress,
    ) -> Result<()> {
        let bank = ORDER_OFFSET_PRIMARY / BANK_SIZE;
        let primary_off = ORDER_OFFSET_PRIMARY % BANK_SIZE;
        let cs_off = ORDER_CHECKSUM_OFFSET % BANK_SIZE;
        let echo_off = ORDER_OFFSET % BANK_SIZE;
        let echo_cs_off = ORDER_ECHO_CHECKSUM_OFFSET % BANK_SIZE;
        let aligned_off = echo_off & !(WRITE_CHUNK - 1);
        let primary_in_chunk = primary_off - aligned_off;
        let cs_in_chunk = cs_off - aligned_off;
        let echo_in_chunk = echo_off - aligned_off;
        let echo_cs_in_chunk = echo_cs_off - aligned_off;

        self.set_var(VAR_DMG_READ_CS_PULSE, 0)?;
        self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;
        self.set_var(VAR_DMG_ACCESS_MODE, 3)?;
        self.cart_write(0x0000, 0x0A)?;
        self.cart_write(0x4000, bank as u8)?;
        std::thread::sleep(Duration::from_millis(20));

        progress.message("[debug][delete] verifying cached album state against cartridge...");
        let current = self.read_sram_window_verified(0xA000 + aligned_off as u32, WRITE_CHUNK)?;
        let expected_primary =
            &save_backup[ORDER_OFFSET_PRIMARY..ORDER_OFFSET_PRIMARY + ORDER_COUNT];
        let expected_cs = &save_backup[ORDER_CHECKSUM_OFFSET..ORDER_CHECKSUM_OFFSET + 2];
        let expected_echo = &save_backup[ORDER_OFFSET..ORDER_OFFSET + ORDER_COUNT];
        let expected_echo_cs =
            &save_backup[ORDER_ECHO_CHECKSUM_OFFSET..ORDER_ECHO_CHECKSUM_OFFSET + 2];

        if current[primary_in_chunk..primary_in_chunk + ORDER_COUNT] == *expected_primary
            && current[cs_in_chunk..cs_in_chunk + 2] == *expected_cs
            && current[echo_in_chunk..echo_in_chunk + ORDER_COUNT] == *expected_echo
            && current[echo_cs_in_chunk..echo_cs_in_chunk + 2] == *expected_echo_cs
        {
            return Ok(());
        }

        Err(GbcamUsbError::Protocol(
            "camera album state changed since this gallery was loaded; reload before deleting"
                .to_string(),
        ))
    }

    fn write_album_order_verified(
        &self,
        order: &[u8; ORDER_COUNT],
        progress: &mut impl Progress,
    ) -> Result<()> {
        // All four regions are in bank 0 and fit within one 256-byte aligned window.
        let bank = ORDER_OFFSET_PRIMARY / BANK_SIZE;
        let primary_off = ORDER_OFFSET_PRIMARY % BANK_SIZE; // 0x11B2
        let cs_off = ORDER_CHECKSUM_OFFSET % BANK_SIZE; // 0x11D5
        let echo_off = ORDER_OFFSET % BANK_SIZE; // 0x11D7
        let echo_cs_off = ORDER_ECHO_CHECKSUM_OFFSET % BANK_SIZE; // 0x11FA
        let aligned_off = echo_off & !(WRITE_CHUNK - 1); // 0x1100

        // Offsets within the 256-byte verification window
        let primary_in_chunk = primary_off - aligned_off; // 0xB2
        let cs_in_chunk = cs_off - aligned_off; // 0xD5
        let echo_in_chunk = echo_off - aligned_off; // 0xD7
        let echo_cs_in_chunk = echo_cs_off - aligned_off; // 0xFA

        let checksum = order_table_checksum(order);
        let write_total = order.len() * 2 + 4; // primary(30) + cs(2) + echo(30) + echo_cs(2)

        progress.message(&format!(
            "[debug][delete] order write plan: primary=0x{ORDER_OFFSET_PRIMARY:05X}, checksum=0x{ORDER_CHECKSUM_OFFSET:05X}, echo=0x{ORDER_OFFSET:05X}, echo_cs=0x{ORDER_ECHO_CHECKSUM_OFFSET:05X}, bank={bank}, aligned_off=0x{aligned_off:04X}"
        ));
        progress.message(&format!(
            "[debug][delete] desired order bytes: {:02X?}",
            &order[..]
        ));
        progress.message(&format!(
            "[debug][delete] desired checksum: {:02X?}",
            &checksum
        ));

        for attempt in 1..=3 {
            let mut written = 0usize;
            progress.message(&format!(
                "[debug][delete] attempt {attempt}/3: enable SRAM bank {bank}"
            ));
            self.set_var(VAR_DMG_READ_CS_PULSE, 0)?;
            self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;
            self.set_var(VAR_DMG_ACCESS_MODE, 4)?;
            self.cart_write(0x0000, 0x0A)?;
            self.cart_write(0x4000, bank as u8)?;
            std::thread::sleep(Duration::from_millis(20));

            // Bottleneck: writes 64 bytes (30 order + 2 checksum + 30 echo + 2 echo checksum)
            // one byte at a time. Each sram_write_byte issues 3 set_var USB round-trips plus
            // a write command and an ACK, then sleeps 2 ms for SRAM settling.
            // Minimum wall time: 64 × 2 ms = ~128 ms of sleep per attempt, up to 3 attempts.
            // Bulk chunk writes (sram_write_chunk) are available but produce less reliable
            // results for individual order-table bytes on the MAC-GBD chip.

            // Write primary order table (30 bytes at 0x11B2)
            progress.message(&format!(
                "[debug][delete] attempt {attempt}/3: writing primary order at 0x{ORDER_OFFSET_PRIMARY:05X}"
            ));
            for (i, &value) in order.iter().enumerate() {
                let address = 0xA000 + primary_off as u32 + i as u32;
                self.sram_write_byte(address, value, progress)?;
                std::thread::sleep(Duration::from_millis(2));
                written += 1;
                progress.write_progress(written, write_total);
            }

            // Write primary checksum (2 bytes at 0x11D5)
            progress.message(&format!(
                "[debug][delete] attempt {attempt}/3: writing primary checksum {:02X?} at 0x{ORDER_CHECKSUM_OFFSET:05X}",
                &checksum
            ));
            self.sram_write_byte(0xA000 + cs_off as u32, checksum[0], progress)?;
            std::thread::sleep(Duration::from_millis(2));
            written += 1;
            progress.write_progress(written, write_total);
            self.sram_write_byte(0xA000 + cs_off as u32 + 1, checksum[1], progress)?;
            std::thread::sleep(Duration::from_millis(2));
            written += 1;
            progress.write_progress(written, write_total);

            // Write echo order table (30 bytes at 0x11D7)
            progress.message(&format!(
                "[debug][delete] attempt {attempt}/3: writing echo order at 0x{ORDER_OFFSET:05X}"
            ));
            for (i, &value) in order.iter().enumerate() {
                let address = 0xA000 + echo_off as u32 + i as u32;
                self.sram_write_byte(address, value, progress)?;
                std::thread::sleep(Duration::from_millis(2));
                written += 1;
                progress.write_progress(written, write_total);
            }

            // Write echo checksum (2 bytes at 0x11FA)
            progress.message(&format!(
                "[debug][delete] attempt {attempt}/3: writing echo checksum {:02X?} at 0x{ORDER_ECHO_CHECKSUM_OFFSET:05X}",
                &checksum
            ));
            self.sram_write_byte(0xA000 + echo_cs_off as u32, checksum[0], progress)?;
            std::thread::sleep(Duration::from_millis(2));
            written += 1;
            progress.write_progress(written, write_total);
            self.sram_write_byte(0xA000 + echo_cs_off as u32 + 1, checksum[1], progress)?;
            std::thread::sleep(Duration::from_millis(2));
            written += 1;
            progress.write_progress(written, write_total);

            progress.message("[debug][delete] all writes sent; waiting 250 ms before verification");
            std::thread::sleep(Duration::from_millis(250));

            // Clear WRITE_CS_PULSE before reading: sram_write_byte leaves it at 1,
            // and read_sram_window asserts READ_CS_PULSE=1 without touching WRITE_CS_PULSE.
            self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;

            progress.message(&format!(
                "[debug][delete] attempt {attempt}/3: verifying 0x{WRITE_CHUNK:03X} byte window at 0xA000+0x{aligned_off:04X}"
            ));
            match self.read_sram_window_verified(0xA000 + aligned_off as u32, WRITE_CHUNK) {
                Ok(verify)
                    if verify[primary_in_chunk..primary_in_chunk + ORDER_COUNT] == order[..]
                        && verify[cs_in_chunk..cs_in_chunk + 2] == checksum
                        && verify[echo_in_chunk..echo_in_chunk + ORDER_COUNT] == order[..]
                        && verify[echo_cs_in_chunk..echo_cs_in_chunk + 2] == checksum =>
                {
                    progress.message(&format!(
                        "[debug][delete] attempt {attempt}/3: verification OK"
                    ));
                    return Ok(());
                }
                Ok(verify) => {
                    progress.message(&format!(
                        "[debug][delete] attempt {attempt}/3: mismatch — primary={:02X?} cs={:02X?} echo={:02X?} echo_cs={:02X?}",
                        &verify[primary_in_chunk..primary_in_chunk + ORDER_COUNT],
                        &verify[cs_in_chunk..cs_in_chunk + 2],
                        &verify[echo_in_chunk..echo_in_chunk + ORDER_COUNT],
                        &verify[echo_cs_in_chunk..echo_cs_in_chunk + 2],
                    ));
                }
                Err(e) if attempt < 3 => {
                    progress.message(&format!(
                        "[debug][delete] attempt {attempt}/3: verify read failed, retrying: {e}"
                    ));
                }
                Err(e) => {
                    progress.message(&format!(
                        "[debug][delete] attempt {attempt}/3: verify read failed: {e}"
                    ));
                    return Err(e);
                }
            }

            self.clear_local_rx();
            std::thread::sleep(Duration::from_millis(80 * attempt as u64));
        }

        Err(GbcamUsbError::Protocol(
            "delete verification failed: order table did not match written data".to_string(),
        ))
    }

    fn prepare_dmg_cart(&self, progress: &mut impl Progress) -> Result<()> {
        progress.message("[debug][prepare] begin DMG cartridge preparation");
        self.debug_cart_power(progress, "prepare: entry");
        Self::required_step(
            progress,
            "prepare: SET_MODE_DMG 0xA3",
            self.cmd_ack(CMD_SET_MODE_DMG),
        )?;
        Self::required_step(
            progress,
            "prepare: SET_VOLTAGE_5V 0xA5",
            self.cmd_ack(CMD_SET_VOLTAGE_5V),
        )?;
        Self::required_step(
            progress,
            "prepare: SET_VARIABLE DMG_READ_METHOD=A15",
            self.set_var(VAR_DMG_READ_METHOD, DMG_READ_METHOD_A15),
        )?;
        Self::required_step(
            progress,
            "prepare: SET_VARIABLE CART_MODE=DMG",
            self.set_var(VAR_CART_MODE, 1),
        )?;
        Self::required_step(
            progress,
            "prepare: SET_VARIABLE ADDRESS=0",
            self.set_var(VAR_ADDRESS, 0),
        )?;

        if self.has_cart_power {
            self.debug_cart_power(progress, "prepare: before mandatory power cycle");
            self.bulk_write(&[CMD_QUERY_CART_PWR])?;
            let powered = self.read_u8()?;
            progress.message(&format!(
                "[debug][power] prepare: CART_PWR raw before power cycle = 0x{powered:02X}"
            ));
            if powered != 0 {
                self.power_off_cart(
                    progress,
                    "prepare: power cycle existing cartridge power off",
                );
                std::thread::sleep(Duration::from_millis(150));
            }

            Self::required_step(
                progress,
                "prepare: SET_MODE_DMG before CART_PWR_ON",
                self.cmd_ack(CMD_SET_MODE_DMG),
            )?;
            progress.message(&format!(
                "[debug][power] SEND 0x{CMD_CART_PWR_ON:02X}: prepare: power on cartridge slot; expected LED effect: Power LED red"
            ));
            self.bulk_write(&[CMD_CART_PWR_ON])?;
            std::thread::sleep(Duration::from_millis(200));
            Self::required_step(
                progress,
                "prepare: CART_PWR_ON ack",
                self.expect_ack("CART_PWR_ON"),
            )?;
            self.bulk_write(&[CMD_QUERY_CART_PWR])?;
            let powered_after = self.read_u8()?;
            progress.message(&format!(
                "[debug][power] prepare: CART_PWR raw after power on = 0x{powered_after:02X}"
            ));
            Self::required_step(
                progress,
                "prepare: DMG_MBC_RESET after cart power on",
                self.cmd_ack_named(CMD_DMG_MBC_RESET, "DMG_MBC_RESET after cart power on"),
            )?;
        } else {
            Self::required_step(
                progress,
                "prepare: DMG_MBC_RESET",
                self.cmd_ack_named(CMD_DMG_MBC_RESET, "DMG_MBC_RESET"),
            )?;
        }
        std::thread::sleep(Duration::from_millis(100));
        self.debug_cart_power(progress, "prepare: complete");
        Ok(())
    }

    fn backup_restore_ram_preamble(&self) -> Result<()> {
        self.cmd_ack(CMD_SET_MODE_DMG)?;
        self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;
        self.set_var(VAR_DMG_READ_CS_PULSE, 0)?;

        self.cart_write(0x2000, 0x00)?;
        self.cart_write(0x4000, 0x90)?;
        let mut flash_probe = [0u8; 2];
        self.set_var(VAR_TRANSFER_SIZE, 2)?;
        self.set_var(VAR_ADDRESS, 0x4000)?;
        self.set_var(VAR_DMG_ACCESS_MODE, 1)?;
        self.bulk_write(&[CMD_DMG_CART_READ])?;
        self.bulk_read(&mut flash_probe)?;
        self.cart_write(0x4000, 0xF0)?;
        self.cart_write(0x4000, 0xFF)?;
        self.cart_write(0x2000, 0x01)?;
        self.cart_write(0x4000, 0x00)?;

        self.cart_write(0x0000, 0x0A)?;

        // Warm-up: the MAC-GBD chip can return stale data on the very first SRAM
        // access after a fresh SRAM enable. Read and discard one chunk so the
        // verified dump loop always sees stable data. This mirrors the calibration
        // read that FlashGBX performs in ReadInfo() before calling BackupRAM().
        let mut warmup = [0u8; READ_CHUNK];
        let _ = self.read_sram_window(0xA000, &mut warmup);

        Ok(())
    }
}

fn ch340_initialize(dev: &UsbDev) -> Result<()> {
    // Matches the CH34x initialization sequence used by usb-serial-for-android.
    // State reads are kept as synchronization points; chip revisions vary in
    // the returned status bytes, so the bytes are not validated here.
    let mut state = [0u8; 2];
    let _ = dev.ctrl_in(0x5f, 0, 0, &mut state);
    dev.ctrl_out(0xa1, 0, 0)?;
    ch340_set_baud_rate(dev, 9_600)?;
    let _ = dev.ctrl_in(0x95, 0x2518, 0, &mut state);
    dev.ctrl_out(0x9a, 0x2518, 0x00c3)?;
    let _ = dev.ctrl_in(0x95, 0x0706, 0, &mut state);
    dev.ctrl_out(0xa1, 0x501f, 0xd90a)?;
    ch340_set_baud_rate(dev, 9_600)?;
    dev.ctrl_out(0xa4, 0xffff, 0)?;
    let _ = dev.ctrl_in(0x95, 0x0706, 0, &mut state);
    Ok(())
}

fn ch340_set_baud_rate(dev: &UsbDev, baud_rate: u32) -> Result<()> {
    let (val1, val2) = ch340_baud_params(baud_rate)?;
    dev.ctrl_out(0x9a, 0x1312, val1)?;
    dev.ctrl_out(0x9a, 0x0f2c, val2)?;
    Ok(())
}

fn ch340_baud_params(baud_rate: u32) -> Result<(u16, u16)> {
    let (factor, divisor) = if baud_rate == 921_600 {
        (0xf300u32, 7u32)
    } else {
        let mut factor = 1_532_620_800u32 / baud_rate;
        let mut divisor = 3u32;
        while factor > 0xfff0 && divisor > 0 {
            factor >>= 3;
            divisor -= 1;
        }
        if factor > 0xfff0 {
            return Err(GbcamUsbError::Protocol(format!(
                "unsupported CH340 baud rate: {}",
                baud_rate
            )));
        }
        (0x10000 - factor, divisor)
    };

    let divisor = divisor | 0x0080;
    let val1 = ((factor & 0xff00) | divisor) as u16;
    let val2 = (factor & 0x00ff) as u16;
    Ok((val1, val2))
}

fn is_usb_timeout(error: &GbcamUsbError) -> bool {
    match error {
        GbcamUsbError::Usb { source, .. } => source.raw_os_error() == Some(libc::ETIMEDOUT),
        _ => false,
    }
}

fn last_usb_error(context: &'static str) -> GbcamUsbError {
    GbcamUsbError::Usb {
        context,
        source: io::Error::last_os_error(),
    }
}

fn header_mismatch_error(header: &[u8], mapper: u8) -> GbcamUsbError {
    let title = title_from_header(header);
    let hint = if shifted_header_hint(header) {
        " The cartridge header looks shifted — try reseating it."
    } else {
        ""
    };
    GbcamUsbError::Protocol(format!(
        "Wrong cartridge: \"{title:?}\" (mapper 0x{mapper:02X}) is not a Game Boy Camera. \
        Insert the Game Boy Camera cartridge and try again.{hint}"
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ack_ok_accepts_only_success_codes() {
        assert!(UsbDev::ack_ok(0x01));
        assert!(UsbDev::ack_ok(0x03));
        assert!(!UsbDev::ack_ok(0x02));
        assert!(!UsbDev::ack_ok(0x19));
    }

    #[test]
    fn lifecycle_command_constants_match_flashgbx_docs() {
        assert_eq!(CMD_OFW_DONE_LED_ON, 0x3D);
        assert_eq!(CMD_OFW_ERROR_LED_ON, 0x3F);
        assert_eq!(CMD_OFW_CART_MODE, 0x43);
        assert_eq!(CMD_CART_PWR_ON, 0xF2);
        assert_eq!(CMD_CART_PWR_OFF, 0xF3);
    }

    #[test]
    fn ch340_baud_params_match_known_values() {
        assert_eq!(ch340_baud_params(9_600).unwrap(), (0xB282, 0x000C));
        assert_eq!(ch340_baud_params(1_000_000).unwrap(), (0xFA83, 0x0004));
        assert_eq!(ch340_baud_params(1_500_000).unwrap(), (0xFC83, 0x0003));
    }

    #[test]
    fn gbx_cart_info_cfw_ver_holds_full_u16_range() {
        // Firmware version 258 (0x0102) must survive roundtrip without truncation.
        // Before fix, cfw_ver was u8: 258_u16 as u8 == 2, so the >= 12 check
        // below would silently fail even though the device reports a recent firmware.
        let info = GbxCartInfo { pcb_ver: 5, ofw_ver: 4, cfw_ver: 258, name: None };
        assert_eq!(info.cfw_ver, 258);
        assert!(info.cfw_ver >= 12);
    }
}
