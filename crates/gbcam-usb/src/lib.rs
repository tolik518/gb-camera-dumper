use gbxcam_core::{
    album_order_after_delete, cartridge_report_from_header, make_erase_chunk, shifted_header_hint,
    title_from_header, CartridgeReport, BANK_SIZE, DMG_READ_METHOD_A15, DMG_READ_METHOD_SLOW_A15,
    MAPPER_MAC_GBD, ORDER_COUNT, ORDER_OFFSET, READ_CHUNK, SAVE_SIZE, SRAM_BANKS, WRITE_CHUNK,
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
    Core(#[from] gbcam_core::GbcamCoreError),
}

pub type Result<T> = std::result::Result<T, GbcamUsbError>;

#[derive(Debug, Clone)]
pub struct GbxCartInfo {
    pub pcb_ver: u8,
    pub ofw_ver: u8,
    pub cfw_ver: u8,
    pub name: Option<String>,
}

pub trait Progress {
    fn message(&mut self, _message: &str) {}
    fn cartridge_header(&mut self, _report: &CartridgeReport) {}
    fn sram_progress(&mut self, _done_bytes: usize, _total_bytes: usize) {}
}

pub struct NoProgress;

impl Progress for NoProgress {}

fn iowr(type_char: u8, nr: u8, size: usize) -> libc::Ioctl {
    ((3u32 << 30) | ((size as u32) << 16) | ((type_char as u32) << 8) | (nr as u32)) as libc::Ioctl
}

fn usbdevfs_control() -> libc::Ioctl {
    iowr(b'U', 0, std::mem::size_of::<CtrlTransfer>())
}

fn usbdevfs_bulk() -> libc::Ioctl {
    iowr(b'U', 2, std::mem::size_of::<BulkTransfer>())
}

fn usbdevfs_claiminterface() -> libc::Ioctl {
    ((2u32 << 30) | (4u32 << 16) | (b'U' as u32) << 8 | 15u32) as libc::Ioctl
}

fn usbdevfs_resetep() -> libc::Ioctl {
    ((2u32 << 30) | (4u32 << 16) | (b'U' as u32) << 8 | 3u32) as libc::Ioctl
}

const USB_TYPE_VENDOR: u8 = 0x40;
const USB_DIR_IN: u8 = 0x80;

const EP_OUT: u32 = 0x02;
const EP_IN: u32 = 0x82;

const BULK_TIMEOUT: u32 = 3000;
const CTRL_TIMEOUT: u32 = 1000;

const CMD_NULL: u8 = 0x30;
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

pub struct UsbDev {
    fd: RawFd,
    fw_ver: u8,
    has_cart_power: bool,
    rxbuf: RefCell<Vec<u8>>,
}

impl UsbDev {
    pub fn new(fd: RawFd) -> Self {
        UsbDev {
            fd,
            fw_ver: 0,
            has_cart_power: false,
            rxbuf: RefCell::new(Vec::new()),
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

        dev.bulk_write(&[CMD_OFW_PCB_VER])?;
        let pcb_ver = dev.read_u8()?;
        dev.bulk_write(&[CMD_OFW_FW_VER])?;
        let ofw_ver = dev.read_u8()?;

        if pcb_ver == 0 {
            return Err(GbcamUsbError::Protocol(
                "No response from GBxCart RW. Check connections.".to_string(),
            ));
        }

        let mut name = None;
        if pcb_ver >= 5 && ofw_ver > 0 {
            dev.bulk_write(&[CMD_QUERY_FW_INFO])?;
            let size = dev.read_u8()? as usize;
            let mut info = vec![0u8; size];
            dev.bulk_read(&mut info)?;
            if size >= 3 {
                dev.fw_ver = u16::from_be_bytes([info[1], info[2]]) as u8;
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
        self.rxbuf.borrow_mut().clear();
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
            if rxbuf.is_empty() {
                *rxbuf = self.raw_bulk_read()?;
            }
            let take = (buf.len() - got).min(rxbuf.len());
            buf[got..got + take].copy_from_slice(&rxbuf[..take]);
            rxbuf.drain(..take);
            got += take;
        }
        Ok(())
    }

    fn read_u8(&self) -> Result<u8> {
        let mut b = [0u8; 1];
        self.bulk_read(&mut b)?;
        Ok(b[0])
    }

    fn ack_byte(&self) -> Result<u8> {
        self.read_u8()
    }

    fn ack_ok(b: u8) -> bool {
        b == 0x01 || b == 0x03
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
        self.rxbuf.borrow_mut().clear();
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
                        if raw.iter().any(|b| *b == 0x01 || *b == 0x02) {
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

    fn write_wait_retry(&self, data: &[u8], ctx: &str, retries: usize) -> Result<()> {
        for attempt in 1..=retries {
            self.clear_local_rx();
            self.bulk_write(data)?;
            let b = self.ack_byte()?;
            if Self::ack_ok(b) {
                return Ok(());
            }

            if attempt < retries {
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
        Ok(())
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
        self.bulk_write(&buf)
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
        if out.len() % READ_CHUNK != 0 {
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
        for sub in (0..out.len()).step_by(READ_CHUNK) {
            self.bulk_write(&[CMD_DMG_CART_READ])?;
            self.bulk_read(&mut out[sub..sub + READ_CHUNK])?;
        }
        self.set_var(VAR_DMG_READ_CS_PULSE, 0)?;
        Ok(())
    }

    fn read_sram_window_verified(&self, gb_addr: u32, len: usize) -> Result<Vec<u8>> {
        let mut first = vec![0u8; len];
        let mut second = vec![0u8; len];

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
        self.prepare_dmg_cart()?;

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
        let mut save = vec![0u8; SAVE_SIZE];
        for bank in 0..SRAM_BANKS {
            self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;
            self.cart_write(0x4000, bank as u8)?;

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
        self.prepare_dmg_cart()?;
        self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;
        self.set_var(VAR_DMG_READ_CS_PULSE, 0)?;
        let header = self.read_cartridge_header()?;
        cartridge_report_from_header(&header)
            .ok_or_else(|| GbcamUsbError::Protocol("short cartridge header".to_string()))
    }

    pub fn erase_save(&self, save_backup: &[u8], progress: &mut impl Progress) -> Result<()> {
        if save_backup.len() != SAVE_SIZE {
            return Err(gbcam_core::GbcamCoreError::InvalidBackupSize {
                actual: save_backup.len(),
                expected: SAVE_SIZE,
            }
            .into());
        }

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

        progress.message("Deleting selected photos from camera album...");
        self.set_var(VAR_DMG_READ_CS_PULSE, 0)?;
        self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;
        self.cart_write(0x0000, 0x0A)?;
        self.cart_write(0x4000, (ORDER_OFFSET / BANK_SIZE) as u8)?;
        std::thread::sleep(Duration::from_millis(20));
        self.sram_write_chunk(0xA000 + (ORDER_OFFSET % BANK_SIZE) as u32, &order)?;
        self.cart_write(0x0000, 0x00)?;
        self.set_var(VAR_DMG_WRITE_CS_PULSE, 0)?;
        self.set_var(VAR_DMG_READ_CS_PULSE, 0)?;
        progress.message("Selected photos deleted from album order.");
        Ok(order)
    }

    fn prepare_dmg_cart(&self) -> Result<()> {
        self.cmd_ack(CMD_SET_MODE_DMG)?;
        self.cmd_ack(CMD_SET_VOLTAGE_5V)?;
        self.set_var(VAR_DMG_READ_METHOD, DMG_READ_METHOD_A15)?;
        self.set_var(VAR_CART_MODE, 1)?;
        self.set_var(VAR_ADDRESS, 0)?;

        if self.has_cart_power {
            self.bulk_write(&[CMD_QUERY_CART_PWR])?;
            let powered = self.read_u8()?;
            if powered == 0 {
                self.cmd_ack(CMD_SET_MODE_DMG)?;
                self.bulk_write(&[CMD_CART_PWR_ON])?;
                std::thread::sleep(Duration::from_millis(200));
                self.expect_ack("CART_PWR_ON")?;
                self.bulk_write(&[CMD_QUERY_CART_PWR])?;
                let _ = self.read_u8()?;
                self.cmd_ack_named(CMD_DMG_MBC_RESET, "DMG_MBC_RESET after cart power on")?;
            }
        } else {
            self.cmd_ack_named(CMD_DMG_MBC_RESET, "DMG_MBC_RESET")?;
        }
        std::thread::sleep(Duration::from_millis(100));
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
    let mut msg = format!(
        "mapper 0x{:02X} != 0xFC. title={:?}, rom_size=0x{:02X}, ram_size=0x{:02X}, checksum=0x{:02X}{:02X}",
        mapper,
        title_from_header(header),
        header[0x148],
        header[0x149],
        header[0x14E],
        header[0x14F]
    );
    if shifted_header_hint(header) {
        msg.push_str("; header looks 8-byte shifted, likely wrong DMG read method");
    }
    GbcamUsbError::Protocol(msg)
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
    fn ch340_baud_params_match_known_values() {
        assert_eq!(ch340_baud_params(9_600).unwrap(), (0xB282, 0x000C));
        assert_eq!(ch340_baud_params(1_000_000).unwrap(), (0xFA83, 0x0004));
        assert_eq!(ch340_baud_params(1_500_000).unwrap(), (0xFC83, 0x0003));
    }
}
