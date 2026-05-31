use std::fs;
use std::path::Path;

pub const MAPPER_MAC_GBD: u8 = 0xFC;
pub const SRAM_BANKS: usize = 16;
pub const BANK_SIZE: usize = 0x2000;
pub const SAVE_SIZE: usize = SRAM_BANKS * BANK_SIZE;
pub const READ_CHUNK: usize = 64;
pub const WRITE_CHUNK: usize = 0x100;

pub const DMG_READ_METHOD_A15: u32 = 1;
pub const DMG_READ_METHOD_SLOW_A15: u32 = 2;

pub const ORDER_OFFSET: usize = 0x11D7;
pub const ORDER_COUNT: usize = 30;
pub const GAME_FACE_OFFSET: usize = 0x11FC;
pub const LAST_SEEN_OFFSET: usize = 0x0000;
pub const PHOTO_BASE: usize = 0x2000;
pub const TILE_BLOCK_SIZE: usize = 0x1000;
pub const PHOTO_IMAGE_SIZE: usize = 0x0E00;
pub const IMG_W: usize = 128;
pub const IMG_H: usize = 112;
pub const LAST_SEEN_H: usize = 123;
pub const TILES_X: usize = 16;
pub const TILES_Y: usize = 14;
pub const LAST_SEEN_TILES_Y: usize = 16;
pub const PALETTE: [u8; 4] = [255, 176, 104, 0];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PhotoKind {
    Album,
    GameFace,
    LastSeen,
}

#[derive(Debug, thiserror::Error)]
pub enum GbcamCoreError {
    #[error("save too small: {actual} bytes, expected at least {expected} bytes")]
    SaveTooSmall { actual: usize, expected: usize },
    #[error("backup size is {actual} bytes, expected {expected} bytes")]
    InvalidBackupSize { actual: usize, expected: usize },
    #[error("PNG error: {0}")]
    Png(#[from] png::EncodingError),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("invalid album physical slot: {slot}")]
    InvalidPhysicalSlot { slot: usize },
}

#[derive(Debug, Clone)]
pub struct CartridgeReport {
    pub title: String,
    pub mapper: u8,
    pub rom_size: u8,
    pub ram_size: u8,
    pub checksum: u16,
}

impl CartridgeReport {
    pub fn is_game_boy_camera(&self) -> bool {
        self.mapper == MAPPER_MAC_GBD
    }
}

#[derive(Debug, Clone)]
pub struct Photo {
    pub name: String,
    pub width: u32,
    pub height: u32,
    pub pixels_gray8: Vec<u8>,
    pub kind: PhotoKind,
    pub display_index: Option<usize>,
    pub physical_slot: Option<usize>,
    pub deleted: bool,
}

pub fn cartridge_report_from_header(header: &[u8]) -> Option<CartridgeReport> {
    if header.len() < 0x150 {
        return None;
    }
    Some(CartridgeReport {
        title: title_from_header(header),
        mapper: header[0x147],
        rom_size: header[0x148],
        ram_size: header[0x149],
        checksum: u16::from_be_bytes([header[0x14E], header[0x14F]]),
    })
}

pub fn title_from_header(header: &[u8]) -> String {
    if header.len() < 0x143 {
        return "?".to_string();
    }
    std::str::from_utf8(&header[0x134..0x143])
        .unwrap_or("?")
        .trim_matches('\0')
        .trim()
        .to_string()
}

pub fn shifted_header_hint(header: &[u8]) -> bool {
    header.len() > 0x14F && header[0x13F] == MAPPER_MAC_GBD && header[0x147] == header[0x14F]
}

pub fn should_preserve_gbcam_byte(abs_off: usize) -> bool {
    const CAL1_START: usize = 2 * BANK_SIZE + 0x0FF2;
    const CAL2_START: usize = 8 * BANK_SIZE + 0x1FF2;
    (CAL1_START..CAL1_START + 0x0E).contains(&abs_off)
        || (CAL2_START..CAL2_START + 0x0E).contains(&abs_off)
}

pub fn make_erase_chunk(
    save_backup: &[u8],
    abs_off: usize,
    len: usize,
) -> Result<Vec<u8>, GbcamCoreError> {
    if save_backup.len() != SAVE_SIZE {
        return Err(GbcamCoreError::InvalidBackupSize {
            actual: save_backup.len(),
            expected: SAVE_SIZE,
        });
    }

    let mut chunk = vec![0u8; len];
    for (i, b) in chunk.iter_mut().enumerate() {
        let p = abs_off + i;
        if should_preserve_gbcam_byte(p) {
            *b = save_backup[p];
        }
    }
    Ok(chunk)
}

pub fn decode_tile(tile: &[u8]) -> [[u8; 8]; 8] {
    let mut px = [[0u8; 8]; 8];
    for row in 0..8 {
        let hi = tile[row * 2];
        let lo = tile[row * 2 + 1];
        for col in 0..8 {
            let bit = 7 - col;
            px[row][col] = ((lo >> bit) & 1) << 1 | ((hi >> bit) & 1);
        }
    }
    px
}

pub fn decode_image(data: &[u8]) -> Vec<u8> {
    decode_tiled_image(data, TILES_Y, IMG_H)
}

pub fn decode_last_seen_image(data: &[u8]) -> Vec<u8> {
    decode_tiled_image(data, LAST_SEEN_TILES_Y, LAST_SEEN_H)
}

fn decode_tiled_image(data: &[u8], tile_rows: usize, output_height: usize) -> Vec<u8> {
    let mut img = vec![0u8; IMG_W * output_height];
    for ty in 0..tile_rows {
        for tx in 0..TILES_X {
            let tile = &data[(ty * TILES_X + tx) * 16..][..16];
            let px = decode_tile(tile);
            for row in 0..8 {
                let y = ty * 8 + row;
                if y >= output_height {
                    continue;
                }
                for col in 0..8 {
                    img[y * IMG_W + (tx * 8 + col)] = PALETTE[px[row][col] as usize];
                }
            }
        }
    }
    img
}

pub fn extract_photos(save: &[u8]) -> Result<Vec<Photo>, GbcamCoreError> {
    if save.len() < SAVE_SIZE {
        return Err(GbcamCoreError::SaveTooSmall {
            actual: save.len(),
            expected: SAVE_SIZE,
        });
    }

    let mut photos = Vec::new();
    let order = &save[ORDER_OFFSET..ORDER_OFFSET + ORDER_COUNT];
    let mut slots_by_display_order = [None; ORDER_COUNT];
    let mut seen_positions = [false; ORDER_COUNT];
    let mut deleted_slots = Vec::new();
    for (slot, &position) in order.iter().enumerate() {
        let position = position as usize;
        if position >= ORDER_COUNT || seen_positions[position] {
            deleted_slots.push(slot);
            continue;
        }
        seen_positions[position] = true;
        slots_by_display_order[position] = Some(slot);
    }

    let mut slots = slots_by_display_order
        .iter()
        .filter_map(|&slot| slot)
        .map(|slot| (slot, false))
        .collect::<Vec<_>>();
    slots.extend(deleted_slots.iter().map(|&slot| (slot, true)));

    for (index, (slot, deleted)) in slots.iter().take(ORDER_COUNT).enumerate() {
        let off = PHOTO_BASE + slot * TILE_BLOCK_SIZE;
        if off + PHOTO_IMAGE_SIZE > save.len() {
            continue;
        }
        photos.push(Photo {
            name: format!("IMG_PC{:02}.png", index + 1),
            width: IMG_W as u32,
            height: IMG_H as u32,
            pixels_gray8: decode_image(&save[off..off + PHOTO_IMAGE_SIZE]),
            kind: PhotoKind::Album,
            display_index: Some(index),
            physical_slot: Some(*slot),
            deleted: *deleted,
        });
    }

    photos.push(Photo {
        name: "IMG_PC31.png".to_string(),
        width: IMG_W as u32,
        height: IMG_H as u32,
        pixels_gray8: decode_image(&save[GAME_FACE_OFFSET..GAME_FACE_OFFSET + TILE_BLOCK_SIZE]),
        kind: PhotoKind::GameFace,
        display_index: None,
        physical_slot: None,
        deleted: false,
    });
    photos.push(Photo {
        name: "IMG_PC32.png".to_string(),
        width: IMG_W as u32,
        height: LAST_SEEN_H as u32,
        pixels_gray8: decode_last_seen_image(
            &save[LAST_SEEN_OFFSET..LAST_SEEN_OFFSET + TILE_BLOCK_SIZE],
        ),
        kind: PhotoKind::LastSeen,
        display_index: None,
        physical_slot: None,
        deleted: false,
    });

    Ok(photos)
}

pub fn album_order_after_delete(
    save: &[u8],
    physical_slots_to_delete: &[usize],
) -> Result<[u8; ORDER_COUNT], GbcamCoreError> {
    if save.len() < SAVE_SIZE {
        return Err(GbcamCoreError::SaveTooSmall {
            actual: save.len(),
            expected: SAVE_SIZE,
        });
    }
    let mut delete = [false; ORDER_COUNT];
    for &slot in physical_slots_to_delete {
        if slot >= ORDER_COUNT {
            return Err(GbcamCoreError::InvalidPhysicalSlot { slot });
        }
        delete[slot] = true;
    }

    let photos = extract_photos(save)?;
    let mut order = [0xFF; ORDER_COUNT];
    for (new_display, slot) in photos
        .iter()
        .filter(|photo| photo.kind == PhotoKind::Album && !photo.deleted)
        .filter_map(|photo| photo.physical_slot)
        .filter(|&slot| !delete[slot])
        .enumerate()
    {
        order[slot] = new_display as u8;
    }
    Ok(order)
}

pub fn apply_album_delete(
    save: &[u8],
    physical_slots_to_delete: &[usize],
) -> Result<Vec<u8>, GbcamCoreError> {
    let order = album_order_after_delete(save, physical_slots_to_delete)?;
    let mut updated = save.to_vec();
    updated[ORDER_OFFSET..ORDER_OFFSET + ORDER_COUNT].copy_from_slice(&order);
    Ok(updated)
}

pub fn write_png(path: &Path, pixels: &[u8]) -> Result<(), GbcamCoreError> {
    let file = fs::File::create(path)?;
    let height = (pixels.len() / IMG_W) as u32;
    let mut enc = png::Encoder::new(file, IMG_W as u32, height);
    enc.set_color(png::ColorType::Grayscale);
    enc.set_depth(png::BitDepth::Eight);
    enc.write_header()?.write_image_data(pixels)?;
    Ok(())
}

pub fn write_photos_to_dir(save: &[u8], dir: &Path) -> Result<Vec<String>, GbcamCoreError> {
    let photos = extract_photos(save)?;
    let mut written = Vec::with_capacity(photos.len());
    for photo in photos {
        let path = dir.join(&photo.name);
        write_png(&path, &photo.pixels_gray8)?;
        written.push(photo.name);
    }
    Ok(written)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cartridge_report_parses_header_fields() {
        let mut header = vec![0u8; 0x180];
        header[0x134..0x134 + 13].copy_from_slice(b"GAMEBOYCAMERA");
        header[0x147] = MAPPER_MAC_GBD;
        header[0x148] = 0x08;
        header[0x149] = 0x04;
        header[0x14E] = 0xBA;
        header[0x14F] = 0xF9;

        let report = cartridge_report_from_header(&header).unwrap();

        assert_eq!(report.title, "GAMEBOYCAMERA");
        assert_eq!(report.mapper, MAPPER_MAC_GBD);
        assert_eq!(report.rom_size, 0x08);
        assert_eq!(report.ram_size, 0x04);
        assert_eq!(report.checksum, 0xBAF9);
        assert!(report.is_game_boy_camera());
    }

    #[test]
    fn cartridge_report_rejects_short_header() {
        assert!(cartridge_report_from_header(&vec![0u8; 0x14F]).is_none());
    }

    #[test]
    fn title_from_header_trims_nul_and_whitespace() {
        let mut header = vec![0u8; 0x180];
        header[0x134..0x134 + 9].copy_from_slice(b"CAMERA   ");

        assert_eq!(title_from_header(&header), "CAMERA");
    }

    #[test]
    fn shifted_header_hint_detects_known_eight_byte_shift_pattern() {
        let mut header = vec![0u8; 0x180];
        header[0x13F] = MAPPER_MAC_GBD;
        header[0x147] = 0xF9;
        header[0x14F] = 0xF9;

        assert!(shifted_header_hint(&header));
    }

    #[test]
    fn erase_chunk_preserves_calibration_bytes() {
        let mut backup = vec![0xAA; SAVE_SIZE];
        let cal_start = 2 * BANK_SIZE + 0x0FF2;
        backup[cal_start] = 0x42;

        let chunk = make_erase_chunk(&backup, cal_start - 1, 3).unwrap();

        assert_eq!(chunk, vec![0x00, 0x42, 0xAA]);
    }

    #[test]
    fn erase_chunk_rejects_wrong_backup_size() {
        let backup = vec![0u8; SAVE_SIZE - 1];
        let err = make_erase_chunk(&backup, 0, WRITE_CHUNK).unwrap_err();

        assert!(matches!(
            err,
            GbcamCoreError::InvalidBackupSize {
                actual,
                expected: SAVE_SIZE
            } if actual == SAVE_SIZE - 1
        ));
    }

    #[test]
    fn preserve_ranges_are_exactly_bounded() {
        let cal1_start = 2 * BANK_SIZE + 0x0FF2;
        let cal1_end = cal1_start + 0x0E;
        let cal2_start = 8 * BANK_SIZE + 0x1FF2;
        let cal2_end = cal2_start + 0x0E;

        assert!(!should_preserve_gbcam_byte(cal1_start - 1));
        assert!(should_preserve_gbcam_byte(cal1_start));
        assert!(should_preserve_gbcam_byte(cal1_end - 1));
        assert!(!should_preserve_gbcam_byte(cal1_end));
        assert!(!should_preserve_gbcam_byte(cal2_start - 1));
        assert!(should_preserve_gbcam_byte(cal2_start));
        assert!(should_preserve_gbcam_byte(cal2_end - 1));
        assert!(!should_preserve_gbcam_byte(cal2_end));
    }

    #[test]
    fn decode_tile_decodes_game_boy_bitplanes() {
        let tile = [
            0b1010_1010,
            0b1100_1100,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
        ];

        let pixels = decode_tile(&tile);

        assert_eq!(pixels[0], [3, 2, 1, 0, 3, 2, 1, 0]);
        assert_eq!(pixels[1], [0; 8]);
    }

    #[test]
    fn decode_image_maps_palette_pixels() {
        let mut data = vec![0u8; TILE_BLOCK_SIZE];
        data[0] = 0b1010_1010;
        data[1] = 0b1100_1100;

        let image = decode_image(&data);

        assert_eq!(&image[0..8], &[0, 104, 176, 255, 0, 104, 176, 255]);
        assert_eq!(image.len(), IMG_W * IMG_H);
    }

    #[test]
    fn extract_photos_uses_display_order_and_appends_deleted_slots() {
        let mut save = vec![0u8; SAVE_SIZE];
        save[ORDER_OFFSET] = 2;
        save[ORDER_OFFSET + 1] = 99;
        save[ORDER_OFFSET + 2] = 0;
        for order_byte in &mut save[ORDER_OFFSET + 3..ORDER_OFFSET + ORDER_COUNT] {
            *order_byte = 99;
        }

        let slot2 = PHOTO_BASE + 2 * TILE_BLOCK_SIZE;
        save[slot2] = 0b1010_1010;
        save[slot2 + 1] = 0b1100_1100;

        let photos = extract_photos(&save).unwrap();

        assert_eq!(
            &photos.iter().map(|p| p.name.as_str()).collect::<Vec<_>>()[..4],
            &[
                "IMG_PC01.png",
                "IMG_PC02.png",
                "IMG_PC03.png",
                "IMG_PC04.png"
            ]
        );
        assert_eq!(photos.len(), 32);
        assert_eq!(photos[30].name, "IMG_PC31.png");
        assert_eq!(photos[31].name, "IMG_PC32.png");
        assert_eq!(photos[31].height, LAST_SEEN_H as u32);
        assert_eq!(photos[0].kind, PhotoKind::Album);
        assert_eq!(photos[0].display_index, Some(0));
        assert_eq!(photos[0].physical_slot, Some(2));
        assert!(!photos[0].deleted);
        assert!(photos[3].deleted);
        assert_eq!(
            &photos[0].pixels_gray8[0..8],
            &[0, 104, 176, 255, 0, 104, 176, 255]
        );
    }

    #[test]
    fn album_order_after_delete_renumbers_remaining_active_photos() {
        let mut save = vec![0u8; SAVE_SIZE];
        save[ORDER_OFFSET] = 2;
        save[ORDER_OFFSET + 1] = 0;
        save[ORDER_OFFSET + 2] = 1;
        for order_byte in &mut save[ORDER_OFFSET + 3..ORDER_OFFSET + ORDER_COUNT] {
            *order_byte = 0xFF;
        }

        let order = album_order_after_delete(&save, &[2]).unwrap();

        assert_eq!(order[1], 0);
        assert_eq!(order[0], 1);
        assert_eq!(order[2], 0xFF);
        assert!(order[3..].iter().all(|&v| v == 0xFF));
    }

    #[test]
    fn apply_album_delete_updates_only_order_table() {
        let mut save = vec![0xAA; SAVE_SIZE];
        save[ORDER_OFFSET] = 0;
        save[ORDER_OFFSET + 1] = 1;
        for order_byte in &mut save[ORDER_OFFSET + 2..ORDER_OFFSET + ORDER_COUNT] {
            *order_byte = 0xFF;
        }

        let updated = apply_album_delete(&save, &[0]).unwrap();

        assert_eq!(updated[ORDER_OFFSET], 0xFF);
        assert_eq!(updated[ORDER_OFFSET + 1], 0);
        assert_eq!(&updated[..ORDER_OFFSET], &save[..ORDER_OFFSET]);
        assert_eq!(
            &updated[ORDER_OFFSET + ORDER_COUNT..],
            &save[ORDER_OFFSET + ORDER_COUNT..]
        );
    }

    #[test]
    fn extract_photos_rejects_short_save() {
        let err = extract_photos(&vec![0u8; SAVE_SIZE - 1]).unwrap_err();

        assert!(matches!(
            err,
            GbcamCoreError::SaveTooSmall {
                actual,
                expected: SAVE_SIZE
            } if actual == SAVE_SIZE - 1
        ));
    }

    #[test]
    fn provided_dump_shows_corrupt_first_photo_rows() {
        let save = include_bytes!("../../../dump/GAMEBOYCAMERA.sav");
        let photos = extract_photos(save).unwrap();
        let pc01 = photos.iter().find(|p| p.name == "IMG_PC01.png").unwrap();
        let expected_pixels = decode_image(&save[PHOTO_BASE..PHOTO_BASE + PHOTO_IMAGE_SIZE]);

        assert_eq!(pc01.pixels_gray8, expected_pixels);
        assert!(save[PHOTO_BASE + 2..PHOTO_BASE + 0x200]
            .iter()
            .all(|b| *b == 0xF3));
    }

    #[test]
    fn provided_dump_uses_contiguous_album_names() {
        let save = include_bytes!("../../../dump/GAMEBOYCAMERA.sav");
        let photos = extract_photos(save).unwrap();
        let names = photos.iter().map(|p| p.name.as_str()).collect::<Vec<_>>();

        assert_eq!(names.len(), 32);
        assert_eq!(
            &names[..9],
            &[
                "IMG_PC01.png",
                "IMG_PC02.png",
                "IMG_PC03.png",
                "IMG_PC04.png",
                "IMG_PC05.png",
                "IMG_PC06.png",
                "IMG_PC07.png",
                "IMG_PC08.png",
                "IMG_PC09.png",
            ]
        );
        assert_eq!(names[30], "IMG_PC31.png");
        assert_eq!(names[31], "IMG_PC32.png");
    }
}
