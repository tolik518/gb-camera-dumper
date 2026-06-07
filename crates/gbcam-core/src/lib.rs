use std::fs;
use std::path::Path;

pub mod rgb_merge;
pub use rgb_merge::{merge_rgb_gray8, RgbMergeAlgorithm, RgbMergeError, RgbMergeOrder};

pub const MAPPER_MAC_GBD: u8 = 0xFC;
pub const SRAM_BANKS: usize = 16;
pub const BANK_SIZE: usize = 0x2000;
pub const SAVE_SIZE: usize = SRAM_BANKS * BANK_SIZE;
pub const READ_CHUNK: usize = 64;
pub const WRITE_CHUNK: usize = 0x100;

pub const DMG_READ_METHOD_A15: u32 = 1;
pub const DMG_READ_METHOD_SLOW_A15: u32 = 2;

// The GB Camera stores the album order table in two places in bank 0 SRAM:
//   Primary:         0x11B2–0x11CF  (30 bytes, zero-indexed display position; 0xFF = deleted)
//   Primary magic:   0x11D0–0x11D4  ("Magic", fixed 5 bytes, never written by us)
//   Primary checksum:0x11D5–0x11D6  (2 bytes: sum + xor over primary, seed 0x2F/0x15)
//   Echo:            0x11D7–0x11F4  (mirror of primary order table)
//   Echo magic:      0x11F5–0x11F9  ("Magic", echo)
//   Echo checksum:   0x11FA–0x11FB  (same values as primary checksum)
//
// The ROM validates the echo checksum on boot. If that fails it falls back to the primary.
// If both checksums fail, the ROM wipes the save. We must write all four regions correctly.
pub const ORDER_OFFSET_PRIMARY: usize = 0x11B2;
pub const ORDER_OFFSET: usize = 0x11D7; // echo of primary (what extract_photos reads)
pub const ORDER_CHECKSUM_OFFSET: usize = 0x11D5; // 2 bytes covering ORDER_OFFSET_PRIMARY..+30
pub const ORDER_ECHO_CHECKSUM_OFFSET: usize = 0x11FA; // 2 bytes covering ORDER_OFFSET..+30
pub const ORDER_COUNT: usize = 30;
pub const ORDER_MAGIC_PRIMARY_OFFSET: usize = 0x11D0;
pub const ORDER_MAGIC_ECHO_OFFSET: usize = 0x11F5;
pub const MAGIC: &[u8; 5] = b"Magic";
pub const SETTINGS_OFFSET_PRIMARY: usize = 0x1000;
pub const SETTINGS_OFFSET_ECHO: usize = 0x10D9;
pub const SETTINGS_DATA_LEN: usize = 0x0D2;
pub const SETTINGS_BLOCK_LEN: usize = 0x0D9;
pub const SETTINGS_MAGIC_PRIMARY_OFFSET: usize = 0x10D2;
pub const SETTINGS_MAGIC_ECHO_OFFSET: usize = 0x11AB;
pub const SETTINGS_CHECKSUM_PRIMARY_OFFSET: usize = 0x10D7;
pub const SETTINGS_CHECKSUM_ECHO_OFFSET: usize = 0x11B0;
pub const GAME_FACE_OFFSET: usize = 0x11FC;
pub const LAST_SEEN_OFFSET: usize = 0x0000;
pub const PHOTO_BASE: usize = 0x2000;
pub const TILE_BLOCK_SIZE: usize = 0x1000;
pub const PHOTO_IMAGE_SIZE: usize = 0x0E00;
pub const PHOTO_THUMBNAIL_SIZE: usize = 0x0100;
pub const PHOTO_METADATA_SIZE: usize = 0x0100;
pub const PHOTO_OWNER_METADATA_LEN: usize = 0x5C;
pub const PHOTO_OWNER_METADATA_ECHO_OFFSET: usize = 0x5C;
pub const CAMERA_OWNER_METADATA_OFFSET: usize = 0xB8;
pub const CAMERA_OWNER_METADATA_LEN: usize = 0x19;
pub const CAMERA_OWNER_METADATA_ECHO_OFFSET: usize = 0xD1;
pub const CALIBRATION_PRIMARY_OFFSET: usize = 0x04FF2;
pub const CALIBRATION_ECHO_OFFSET: usize = 0x11FF2;
pub const CALIBRATION_LEN: usize = 0x0E;
pub const IMG_W: usize = 128;
pub const IMG_H: usize = 112;
pub const LAST_SEEN_H: usize = 123;
pub const TILES_X: usize = 16;
pub const TILES_Y: usize = 14;
pub const LAST_SEEN_TILES_Y: usize = 16;
pub const PALETTE: [u8; 4] = [255, 176, 104, 0];
pub const DEFAULT_PALETTE_INDEX: usize = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PalettePreset {
    pub label: &'static str,
    pub colors: [[u8; 3]; 4],
}

// Single source of truth for all palettes — used by both the Rust core and the
// Android build system.
//
// The Gradle task `generatePaletteIcons` (apps/android/app/build.gradle) parses
// this array at build time to generate:
//   • one adaptive launcher icon per palette (4-stripe background from colors[0..3])
//   • one activity-alias per palette (for dynamic icon switching)
//   • PaletteIcons.java (component names + apply helper)
//
// Formatting rules the parser relies on:
//   • Each PalettePreset must be a single `PalettePreset { … }` block with no
//     nested braces.
//   • `label` must be a single-line string literal.
//   • `colors` must contain exactly four [r, g, b] triples (light → dark).
//   • colors[3] is used as the darkest stripe / icon accent in the launcher icon.
pub const PALETTE_PRESETS: &[PalettePreset] = &[
    PalettePreset {
        label: "CRT - Amber",
        colors: [[255, 244, 214], [255, 183, 77], [181, 92, 18], [50, 22, 5]],
    },
    PalettePreset {
        label: "Game Boy Color - Camera",
        colors: [
            [240, 240, 240],
            [134, 200, 100],
            [58, 96, 132],
            [30, 30, 30],
        ],
    },
    PalettePreset {
        label: "Game Boy Color - Camera Gold",
        colors: [
            [240, 240, 240],
            [220, 160, 160],
            [136, 78, 78],
            [30, 30, 30],
        ],
    },
    PalettePreset {
        label: "Game Boy Color - Pocket Camera",
        colors: [
            [240, 240, 240],
            [218, 196, 106],
            [112, 88, 52],
            [30, 30, 30],
        ],
    },
    PalettePreset {
        label: "Game Boy Color Boot - Blue",
        colors: [[222, 255, 255], [99, 206, 255], [49, 99, 206], [0, 0, 66]],
    },
    PalettePreset {
        label: "Game Boy Color Boot - Brown",
        colors: [[255, 255, 198], [222, 156, 66], [148, 74, 0], [74, 33, 0]],
    },
    PalettePreset {
        label: "Game Boy Color Boot - Inverted",
        colors: [[0, 0, 0], [104, 104, 104], [176, 176, 176], [255, 255, 255]],
    },
    PalettePreset {
        label: "Game Boy Color Boot - Red",
        colors: [[255, 231, 231], [255, 132, 132], [132, 33, 33], [33, 0, 0]],
    },
    PalettePreset {
        label: "Game Boy Color Boot - Yellow",
        colors: [[255, 255, 165], [255, 198, 66], [206, 99, 0], [66, 33, 0]],
    },
    PalettePreset {
        label: "Game Boy DMG - Classic Green",
        colors: [[155, 188, 15], [139, 172, 15], [48, 98, 48], [15, 56, 15]],
    },
    PalettePreset {
        label: "Game Boy DMG - Original LCD",
        colors: [[208, 217, 60], [120, 164, 106], [84, 88, 84], [36, 70, 36]],
    },
    PalettePreset {
        label: "Game Boy Light - Green",
        colors: [
            [240, 255, 216],
            [168, 208, 128],
            [88, 128, 80],
            [24, 48, 40],
        ],
    },
    PalettePreset {
        label: "Game Boy Pocket - Monochrome",
        colors: [[248, 248, 248], [168, 168, 168], [80, 80, 80], [8, 8, 8]],
    },
    PalettePreset {
        label: "GB Studio - Green",
        colors: [[224, 248, 208], [136, 192, 112], [52, 104, 86], [8, 24, 32]],
    },
    PalettePreset {
        label: "Monochrome - Grayscale",
        colors: [[255, 255, 255], [176, 176, 176], [104, 104, 104], [0, 0, 0]],
    },
    PalettePreset {
        label: "Print - Cyanotype",
        colors: [[230, 250, 255], [118, 196, 219], [36, 91, 130], [4, 24, 45]],
    },
    PalettePreset {
        label: "Print - Sakura",
        colors: [
            [255, 247, 250],
            [245, 179, 197],
            [139, 82, 115],
            [35, 24, 45],
        ],
    },
];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PaletteId {
    index: usize,
}

impl PaletteId {
    pub const DEFAULT: Self = Self {
        index: DEFAULT_PALETTE_INDEX,
    };

    pub fn from_index(index: usize) -> Self {
        if index < PALETTE_PRESETS.len() {
            Self { index }
        } else {
            Self { index: 0 }
        }
    }

    pub fn index(self) -> usize {
        self.index
    }

    pub fn label(self) -> &'static str {
        PALETTE_PRESETS[self.index].label
    }

    pub fn colors(self) -> [[u8; 3]; 4] {
        PALETTE_PRESETS[self.index].colors
    }
}

pub fn palette_labels() -> impl Iterator<Item = &'static str> {
    PALETTE_PRESETS.iter().map(|palette| palette.label)
}

pub fn palette_colors() -> impl Iterator<Item = [[u8; 3]; 4]> {
    PALETTE_PRESETS.iter().map(|palette| palette.colors)
}

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
    #[error("no valid album state vector")]
    NoValidStateVector,
    #[error("invalid album display position: {position}")]
    InvalidDisplayPosition { position: usize },
    #[error("no free album display position")]
    NoFreeDisplayPosition,
    #[error("duplicate album physical slot: {slot}")]
    DuplicatePhysicalSlot { slot: usize },
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
    pub pixels_indexed: Vec<u8>,
    pub kind: PhotoKind,
    pub display_index: Option<usize>,
    pub physical_slot: Option<usize>,
    pub deleted: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StateVectorCopyKind {
    Primary,
    Echo,
}

impl std::fmt::Display for StateVectorCopyKind {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Primary => write!(f, "primary"),
            Self::Echo => write!(f, "echo"),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StateVectorCopy {
    pub kind: StateVectorCopyKind,
    pub order: [u8; ORDER_COUNT],
    pub magic_valid: bool,
    pub stored_checksum: [u8; 2],
    pub computed_checksum: [u8; 2],
}

impl StateVectorCopy {
    pub fn checksum_valid(&self) -> bool {
        self.stored_checksum == self.computed_checksum
    }

    pub fn valid(&self) -> bool {
        self.magic_valid && self.checksum_valid()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StateVectorReport {
    pub primary: StateVectorCopy,
    pub echo: StateVectorCopy,
    pub selected: Option<StateVectorCopyKind>,
}

impl StateVectorReport {
    pub fn selected_order(&self) -> Option<[u8; ORDER_COUNT]> {
        match self.selected {
            Some(StateVectorCopyKind::Primary) => Some(self.primary.order),
            Some(StateVectorCopyKind::Echo) => Some(self.echo.order),
            None => None,
        }
    }

    pub fn copies_match(&self) -> bool {
        self.primary.order == self.echo.order
            && self.primary.stored_checksum == self.echo.stored_checksum
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ValidationSeverity {
    Info,
    Warning,
    Error,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ValidationFinding {
    pub severity: ValidationSeverity,
    pub offset: Option<usize>,
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SaveValidationReport {
    pub findings: Vec<ValidationFinding>,
}

impl SaveValidationReport {
    pub fn is_valid(&self) -> bool {
        !self
            .findings
            .iter()
            .any(|finding| finding.severity == ValidationSeverity::Error)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Gender {
    None,
    Male,
    Female,
    Unknown(u8),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BloodType {
    None,
    A,
    B,
    O,
    AB,
    Unknown(u8),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UserProfile {
    pub user_id: [u8; 4],
    pub username_raw: Vec<u8>,
    pub gender: Gender,
    pub blood_type: BloodType,
    pub birthdate_raw: [u8; 4],
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PhotoHotspot {
    pub enabled: bool,
    pub x: u8,
    pub y: u8,
    pub sound: Option<u8>,
    pub effect: Option<u8>,
    pub jump_to_image: Option<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MetadataChecksum {
    pub stored: [u8; 2],
    pub computed: [u8; 2],
    pub magic_valid: bool,
}

impl MetadataChecksum {
    pub fn valid(&self) -> bool {
        self.magic_valid && self.stored == self.computed
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PhotoMetadata {
    pub physical_slot: usize,
    pub image_owner: UserProfile,
    pub comment_raw: Vec<u8>,
    pub copy: bool,
    pub image_checksum_guess: [u8; 2],
    pub hotspots: Vec<PhotoHotspot>,
    pub border: u8,
    pub owner_checksum: MetadataChecksum,
    pub owner_echo_matches: bool,
    pub camera_owner: Option<UserProfile>,
    pub camera_owner_checksum: Option<MetadataChecksum>,
    pub camera_owner_echo_matches: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SettingsCopy {
    pub primary: bool,
    pub magic_valid: bool,
    pub stored_checksum: [u8; 2],
    pub computed_checksum: [u8; 2],
}

impl SettingsCopy {
    pub fn checksum_valid(&self) -> bool {
        self.stored_checksum == self.computed_checksum
    }

    pub fn valid(&self) -> bool {
        self.magic_valid && self.checksum_valid()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CameraCounters {
    pub pictures_taken_raw: [u8; 2],
    pub pictures_erased_raw: [u8; 2],
    pub pictures_transferred_raw: [u8; 2],
    pub pictures_printed_raw: [u8; 2],
    pub pictures_received_raw: [u8; 2],
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MinigameScores {
    pub space_fever_ii_raw: [u8; 2],
    pub ball_raw: [u8; 2],
    pub run_run_run_raw: [u8; 2],
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SettingsBlock {
    pub primary: SettingsCopy,
    pub echo: SettingsCopy,
    pub copies_match: bool,
    pub animation_slots_raw: Vec<u8>,
    pub animation_loop_flag: u8,
    pub animation_speed: u8,
    pub animation_border: u8,
    pub tempo: u8,
    pub partition_saved: bool,
    pub counters: CameraCounters,
    pub scores: MinigameScores,
    pub printing_intensity: u8,
}

#[derive(Debug, Clone, Copy)]
pub struct GbcamSave<'a> {
    data: &'a [u8],
}

impl<'a> GbcamSave<'a> {
    pub fn new(data: &'a [u8]) -> Result<Self, GbcamCoreError> {
        ensure_save_size(data)?;
        Ok(Self { data })
    }

    pub fn data(&self) -> &'a [u8] {
        self.data
    }

    pub fn state_vector_report(&self) -> StateVectorReport {
        state_vector_report(self.data)
    }

    pub fn canonical_order(&self) -> Result<[u8; ORDER_COUNT], GbcamCoreError> {
        self.state_vector_report()
            .selected_order()
            .ok_or(GbcamCoreError::NoValidStateVector)
    }

    pub fn album_slots(&self) -> Result<Vec<(usize, bool)>, GbcamCoreError> {
        Ok(album_slots_from_order(&self.canonical_order()?))
    }

    pub fn game_face(&self) -> &'a [u8] {
        &self.data[GAME_FACE_OFFSET..GAME_FACE_OFFSET + PHOTO_IMAGE_SIZE]
    }

    pub fn last_seen(&self) -> &'a [u8] {
        &self.data[LAST_SEEN_OFFSET..LAST_SEEN_OFFSET + TILE_BLOCK_SIZE]
    }

    pub fn image_data_for_slot(&self, physical_slot: usize) -> Result<&'a [u8], GbcamCoreError> {
        let base = photo_slot_base(physical_slot)?;
        Ok(&self.data[base..base + PHOTO_IMAGE_SIZE])
    }

    pub fn thumbnail_for_slot(&self, physical_slot: usize) -> Result<&'a [u8], GbcamCoreError> {
        let base = photo_slot_base(physical_slot)?;
        Ok(&self.data[base + PHOTO_IMAGE_SIZE..base + PHOTO_IMAGE_SIZE + PHOTO_THUMBNAIL_SIZE])
    }

    pub fn metadata_for_slot(&self, physical_slot: usize) -> Result<PhotoMetadata, GbcamCoreError> {
        photo_metadata(self.data, physical_slot)
    }

    pub fn settings_block(&self) -> SettingsBlock {
        settings_block(self.data)
    }

    pub fn calibration_ranges(&self) -> (&'a [u8], &'a [u8]) {
        (
            &self.data[CALIBRATION_PRIMARY_OFFSET..CALIBRATION_PRIMARY_OFFSET + CALIBRATION_LEN],
            &self.data[CALIBRATION_ECHO_OFFSET..CALIBRATION_ECHO_OFFSET + CALIBRATION_LEN],
        )
    }

    pub fn validate(&self) -> SaveValidationReport {
        validate_save(self.data)
    }
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

fn ensure_save_size(save: &[u8]) -> Result<(), GbcamCoreError> {
    if save.len() < SAVE_SIZE {
        return Err(GbcamCoreError::SaveTooSmall {
            actual: save.len(),
            expected: SAVE_SIZE,
        });
    }
    Ok(())
}

fn checksum_seeded(data: &[u8]) -> [u8; 2] {
    let mut sum: u8 = 0x2F;
    let mut xor: u8 = 0x15;
    for &b in data {
        sum = sum.wrapping_add(b);
        xor ^= b;
    }
    [sum, xor]
}

fn state_vector_copy(
    save: &[u8],
    kind: StateVectorCopyKind,
    order_offset: usize,
    magic_offset: usize,
    checksum_offset: usize,
) -> StateVectorCopy {
    let mut order = [0u8; ORDER_COUNT];
    order.copy_from_slice(&save[order_offset..order_offset + ORDER_COUNT]);
    StateVectorCopy {
        kind,
        order,
        magic_valid: &save[magic_offset..magic_offset + MAGIC.len()] == MAGIC,
        stored_checksum: save[checksum_offset..checksum_offset + 2].try_into().unwrap(),
        computed_checksum: order_table_checksum(&order),
    }
}

pub fn state_vector_report(save: &[u8]) -> StateVectorReport {
    let primary = state_vector_copy(
        save,
        StateVectorCopyKind::Primary,
        ORDER_OFFSET_PRIMARY,
        ORDER_MAGIC_PRIMARY_OFFSET,
        ORDER_CHECKSUM_OFFSET,
    );
    let echo = state_vector_copy(
        save,
        StateVectorCopyKind::Echo,
        ORDER_OFFSET,
        ORDER_MAGIC_ECHO_OFFSET,
        ORDER_ECHO_CHECKSUM_OFFSET,
    );
    let selected = if primary.valid() {
        Some(StateVectorCopyKind::Primary)
    } else if echo.valid() {
        Some(StateVectorCopyKind::Echo)
    } else {
        None
    };
    StateVectorReport {
        primary,
        echo,
        selected,
    }
}

fn album_slots_from_order(order: &[u8; ORDER_COUNT]) -> Vec<(usize, bool)> {
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
        .copied()
        .flatten()
        .map(|slot| (slot, false))
        .collect::<Vec<_>>();
    slots.extend(deleted_slots.iter().map(|&slot| (slot, true)));
    slots
}

pub fn photo_slot_base(physical_slot: usize) -> Result<usize, GbcamCoreError> {
    if physical_slot >= ORDER_COUNT {
        return Err(GbcamCoreError::InvalidPhysicalSlot {
            slot: physical_slot,
        });
    }
    Ok(PHOTO_BASE + physical_slot * TILE_BLOCK_SIZE)
}

fn parse_gender_blood(value: u8) -> (Gender, BloodType) {
    let gender = match value & 0x03 {
        0x00 => Gender::None,
        0x01 => Gender::Male,
        0x02 => Gender::Female,
        other => Gender::Unknown(other),
    };
    let blood_type = match value & 0x1C {
        0x00 => BloodType::None,
        0x04 => BloodType::A,
        0x08 => BloodType::B,
        0x0C => BloodType::O,
        0x10 => BloodType::AB,
        other => BloodType::Unknown(other),
    };
    (gender, blood_type)
}

fn parse_user_profile(data: &[u8]) -> UserProfile {
    let (gender, blood_type) = parse_gender_blood(data[0x0D]);
    UserProfile {
        user_id: data[0x00..0x04].try_into().unwrap(),
        username_raw: data[0x04..0x0D].to_vec(),
        gender,
        blood_type,
        birthdate_raw: data[0x0E..0x12].try_into().unwrap(),
    }
}

fn metadata_checksum(data: &[u8], checksum_offset: usize, magic_offset: usize) -> MetadataChecksum {
    MetadataChecksum {
        stored: data[checksum_offset..checksum_offset + 2].try_into().unwrap(),
        computed: checksum_seeded(&data[..magic_offset]),
        magic_valid: &data[magic_offset..magic_offset + MAGIC.len()] == MAGIC,
    }
}

fn settings_copy(
    save: &[u8],
    primary: bool,
    offset: usize,
    magic_offset: usize,
    checksum_offset: usize,
) -> SettingsCopy {
    SettingsCopy {
        primary,
        magic_valid: &save[magic_offset..magic_offset + MAGIC.len()] == MAGIC,
        stored_checksum: save[checksum_offset..checksum_offset + 2].try_into().unwrap(),
        computed_checksum: checksum_seeded(&save[offset..offset + SETTINGS_DATA_LEN]),
    }
}

pub fn settings_block(save: &[u8]) -> SettingsBlock {
    let data = &save[SETTINGS_OFFSET_PRIMARY..SETTINGS_OFFSET_PRIMARY + SETTINGS_DATA_LEN];
    SettingsBlock {
        primary: settings_copy(
            save,
            true,
            SETTINGS_OFFSET_PRIMARY,
            SETTINGS_MAGIC_PRIMARY_OFFSET,
            SETTINGS_CHECKSUM_PRIMARY_OFFSET,
        ),
        echo: settings_copy(
            save,
            false,
            SETTINGS_OFFSET_ECHO,
            SETTINGS_MAGIC_ECHO_OFFSET,
            SETTINGS_CHECKSUM_ECHO_OFFSET,
        ),
        copies_match: save[SETTINGS_OFFSET_PRIMARY..SETTINGS_OFFSET_PRIMARY + SETTINGS_BLOCK_LEN]
            == save[SETTINGS_OFFSET_ECHO..SETTINGS_OFFSET_ECHO + SETTINGS_BLOCK_LEN],
        animation_slots_raw: data[0x000..0x02F].to_vec(),
        animation_loop_flag: data[0x02F],
        animation_speed: data[0x05F],
        animation_border: data[0x060],
        tempo: data[0x0B9],
        partition_saved: data[0x0BA] == 0x01,
        counters: CameraCounters {
            pictures_taken_raw: data[0x0BB..0x0BD].try_into().unwrap(),
            pictures_erased_raw: data[0x0BD..0x0BF].try_into().unwrap(),
            pictures_transferred_raw: data[0x0BF..0x0C1].try_into().unwrap(),
            pictures_printed_raw: data[0x0C1..0x0C3].try_into().unwrap(),
            pictures_received_raw: data[0x0C3..0x0C5].try_into().unwrap(),
        },
        scores: MinigameScores {
            space_fever_ii_raw: data[0x0C5..0x0C7].try_into().unwrap(),
            ball_raw: data[0x0C9..0x0CB].try_into().unwrap(),
            run_run_run_raw: data[0x0CB..0x0CD].try_into().unwrap(),
        },
        printing_intensity: data[0x0D0],
    }
}

pub fn photo_metadata(save: &[u8], physical_slot: usize) -> Result<PhotoMetadata, GbcamCoreError> {
    ensure_save_size(save)?;
    let base = photo_slot_base(physical_slot)?;
    let metadata = &save[base + PHOTO_IMAGE_SIZE + PHOTO_THUMBNAIL_SIZE..base + TILE_BLOCK_SIZE];
    let owner = &metadata[..PHOTO_OWNER_METADATA_LEN];
    let owner_echo = &metadata[PHOTO_OWNER_METADATA_ECHO_OFFSET
        ..PHOTO_OWNER_METADATA_ECHO_OFFSET + PHOTO_OWNER_METADATA_LEN];
    let camera_owner = &metadata
        [CAMERA_OWNER_METADATA_OFFSET..CAMERA_OWNER_METADATA_OFFSET + CAMERA_OWNER_METADATA_LEN];
    let camera_owner_echo = &metadata[CAMERA_OWNER_METADATA_ECHO_OFFSET
        ..CAMERA_OWNER_METADATA_ECHO_OFFSET + CAMERA_OWNER_METADATA_LEN];

    let hotspots = (0..5)
        .map(|i| PhotoHotspot {
            enabled: owner[0x36 + i] == 0x01,
            x: owner[0x3B + i],
            y: owner[0x40 + i],
            sound: (owner[0x45 + i] != 0xFF).then_some(owner[0x45 + i]),
            effect: (owner[0x4A + i] != 0xFF).then_some(owner[0x4A + i]),
            jump_to_image: (owner[0x4F + i] != 0xFF).then_some(owner[0x4F + i]),
        })
        .collect();

    let camera_owner_present = physical_slot == 0;
    Ok(PhotoMetadata {
        physical_slot,
        image_owner: parse_user_profile(owner),
        comment_raw: owner[0x15..0x30].to_vec(),
        copy: owner[0x33] == 0x01,
        image_checksum_guess: [owner[0x34], owner[0x35]],
        hotspots,
        border: owner[0x54],
        owner_checksum: metadata_checksum(owner, 0x5A, 0x55),
        owner_echo_matches: owner == owner_echo,
        camera_owner: camera_owner_present.then(|| parse_user_profile(camera_owner)),
        camera_owner_checksum: camera_owner_present
            .then(|| metadata_checksum(camera_owner, 0x17, 0x12)),
        camera_owner_echo_matches: camera_owner == camera_owner_echo,
    })
}

pub fn validate_save(save: &[u8]) -> SaveValidationReport {
    let mut findings = Vec::new();
    if let Err(err) = ensure_save_size(save) {
        findings.push(ValidationFinding {
            severity: ValidationSeverity::Error,
            offset: None,
            message: err.to_string(),
        });
        return SaveValidationReport { findings };
    }

    let state = state_vector_report(save);
    let settings = settings_block(save);
    for copy in [&settings.primary, &settings.echo] {
        let offset = if copy.primary {
            SETTINGS_OFFSET_PRIMARY
        } else {
            SETTINGS_OFFSET_ECHO
        };
        let label = if copy.primary { "primary" } else { "echo" };
        if !copy.magic_valid {
            findings.push(ValidationFinding {
                severity: ValidationSeverity::Error,
                offset: Some(offset),
                message: format!("{label} settings Magic marker is invalid"),
            });
        }
        if !copy.checksum_valid() {
            findings.push(ValidationFinding {
                severity: ValidationSeverity::Error,
                offset: Some(offset),
                message: format!(
                    "{label} settings checksum is {:02X?}, expected {:02X?}",
                    copy.stored_checksum, copy.computed_checksum
                ),
            });
        }
    }
    if !settings.copies_match {
        findings.push(ValidationFinding {
            severity: ValidationSeverity::Warning,
            offset: Some(SETTINGS_OFFSET_PRIMARY),
            message: "primary and echo settings blocks differ".to_string(),
        });
    }

    for copy in [&state.primary, &state.echo] {
        let offset = match copy.kind {
            StateVectorCopyKind::Primary => ORDER_OFFSET_PRIMARY,
            StateVectorCopyKind::Echo => ORDER_OFFSET,
        };
        if !copy.magic_valid {
            findings.push(ValidationFinding {
                severity: ValidationSeverity::Error,
                offset: Some(offset),
                message: format!("{} state vector Magic marker is invalid", copy.kind),
            });
        }
        if !copy.checksum_valid() {
            findings.push(ValidationFinding {
                severity: ValidationSeverity::Error,
                offset: Some(offset),
                message: format!(
                    "{} state vector checksum is {:02X?}, expected {:02X?}",
                    copy.kind, copy.stored_checksum, copy.computed_checksum
                ),
            });
        }
    }
    if state.selected.is_none() {
        findings.push(ValidationFinding {
            severity: ValidationSeverity::Error,
            offset: Some(ORDER_OFFSET_PRIMARY),
            message: "no valid album state vector copy".to_string(),
        });
    }
    if !state.copies_match() {
        findings.push(ValidationFinding {
            severity: ValidationSeverity::Warning,
            offset: Some(ORDER_OFFSET_PRIMARY),
            message: "primary and echo state vectors differ".to_string(),
        });
    }

    if let Some(order) = state.selected_order() {
        let mut seen = [false; ORDER_COUNT];
        for &position in &order {
            let position = position as usize;
            if position < ORDER_COUNT {
                if seen[position] {
                    findings.push(ValidationFinding {
                        severity: ValidationSeverity::Warning,
                        offset: Some(ORDER_OFFSET_PRIMARY),
                        message: format!("duplicate album display position {position}"),
                    });
                }
                seen[position] = true;
            } else if position != 0xFF {
                findings.push(ValidationFinding {
                    severity: ValidationSeverity::Warning,
                    offset: Some(ORDER_OFFSET_PRIMARY),
                    message: format!("nonstandard deleted slot marker 0x{position:02X}"),
                });
            }
        }
    }

    let save_view = GbcamSave { data: save };
    for slot in 0..ORDER_COUNT {
        if let Ok(metadata) = save_view.metadata_for_slot(slot) {
            let metadata_base =
                PHOTO_BASE + slot * TILE_BLOCK_SIZE + PHOTO_IMAGE_SIZE + PHOTO_THUMBNAIL_SIZE;
            if !metadata.owner_checksum.valid() {
                findings.push(ValidationFinding {
                    severity: ValidationSeverity::Warning,
                    offset: Some(metadata_base),
                    message: format!("slot {} image-owner metadata checksum is invalid", slot),
                });
            }
            if !metadata.owner_echo_matches {
                findings.push(ValidationFinding {
                    severity: ValidationSeverity::Warning,
                    offset: Some(metadata_base + PHOTO_OWNER_METADATA_ECHO_OFFSET),
                    message: format!("slot {} image-owner metadata echo differs", slot),
                });
            }
            if let Some(checksum) = &metadata.camera_owner_checksum {
                if !checksum.valid() {
                    findings.push(ValidationFinding {
                        severity: ValidationSeverity::Warning,
                        offset: Some(metadata_base + CAMERA_OWNER_METADATA_OFFSET),
                        message: format!("slot {} camera-owner metadata checksum is invalid", slot),
                    });
                }
            }
            if metadata.camera_owner.is_some() && !metadata.camera_owner_echo_matches {
                findings.push(ValidationFinding {
                    severity: ValidationSeverity::Warning,
                    offset: Some(metadata_base + CAMERA_OWNER_METADATA_ECHO_OFFSET),
                    message: format!("slot {} camera-owner metadata echo differs", slot),
                });
            }
        }
    }

    let (calibration, calibration_echo) = save_view.calibration_ranges();
    if calibration != calibration_echo {
        findings.push(ValidationFinding {
            severity: ValidationSeverity::Warning,
            offset: Some(CALIBRATION_PRIMARY_OFFSET),
            message: "calibration primary and echo ranges differ".to_string(),
        });
    }

    SaveValidationReport { findings }
}

pub fn should_preserve_gbcam_byte(abs_off: usize) -> bool {
    (CALIBRATION_PRIMARY_OFFSET..CALIBRATION_PRIMARY_OFFSET + CALIBRATION_LEN).contains(&abs_off)
        || (CALIBRATION_ECHO_OFFSET..CALIBRATION_ECHO_OFFSET + CALIBRATION_LEN).contains(&abs_off)
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
    for (row, px_row) in px.iter_mut().enumerate() {
        let hi = *tile.get(row * 2).unwrap_or(&0);
        let lo = *tile.get(row * 2 + 1).unwrap_or(&0);
        for (col, px) in px_row.iter_mut().enumerate() {
            let bit = 7 - col;
            *px = ((lo >> bit) & 1) << 1 | ((hi >> bit) & 1);
        }
    }
    px
}

pub fn decode_image(data: &[u8]) -> Vec<u8> {
    indexed_to_gray8(&decode_image_indices(data))
}

pub fn decode_last_seen_image(data: &[u8]) -> Vec<u8> {
    indexed_to_gray8(&decode_last_seen_image_indices(data))
}

pub fn decode_image_indices(data: &[u8]) -> Vec<u8> {
    decode_tiled_image_indices(data, TILES_Y, IMG_H)
}

pub fn decode_last_seen_image_indices(data: &[u8]) -> Vec<u8> {
    decode_tiled_image_indices(data, LAST_SEEN_TILES_Y, LAST_SEEN_H)
}

fn decode_tiled_image_indices(data: &[u8], tile_rows: usize, output_height: usize) -> Vec<u8> {
    let mut img = vec![0u8; IMG_W * output_height];
    for ty in 0..tile_rows {
        for tx in 0..TILES_X {
            let tile_offset = (ty * TILES_X + tx) * 16;
            let px = if tile_offset + 16 <= data.len() {
                decode_tile(&data[tile_offset..tile_offset + 16])
            } else {
                let mut padded = [0u8; 16];
                if tile_offset < data.len() {
                    let copy_len = data.len() - tile_offset;
                    padded[..copy_len].copy_from_slice(&data[tile_offset..]);
                }
                decode_tile(&padded)
            };
            for (row, px_row) in px.iter().enumerate() {
                let y = ty * 8 + row;
                if y >= output_height {
                    continue;
                }
                for (col, &px) in px_row.iter().enumerate() {
                    img[y * IMG_W + (tx * 8 + col)] = px;
                }
            }
        }
    }
    img
}

pub fn indexed_to_gray8(pixels_indexed: &[u8]) -> Vec<u8> {
    pixels_indexed
        .iter()
        .map(|&px| PALETTE.get(px as usize).copied().unwrap_or(PALETTE[0]))
        .collect()
}

pub fn indexed_to_rgb8(pixels_indexed: &[u8], palette: PaletteId) -> Vec<u8> {
    let colors = palette.colors();
    // Bottleneck: allocates len * 3 bytes (≈ 43 KB per 128×112 photo) per call.
    // Avoidable by emitting an indexed/palette PNG instead of an RGB one, which
    // would also reduce the zlib payload that write_palette_png has to compress.
    let mut rgb = Vec::with_capacity(pixels_indexed.len() * 3);
    for &px in pixels_indexed {
        rgb.extend_from_slice(colors.get(px as usize).unwrap_or(&colors[0]));
    }
    rgb
}

pub fn extract_photos(save: &[u8]) -> Result<Vec<Photo>, GbcamCoreError> {
    let save_view = GbcamSave::new(save)?;

    // Bottleneck: decodes all 32 photos (30 album + GameFace + LastSeen), allocating
    // a pixels_indexed Vec per photo. Callers that only need album photos (gallery_json)
    // still pay the decode cost for the two special-purpose images.
    let mut photos = Vec::new();
    let slots = save_view.album_slots()?;

    for (index, (slot, deleted)) in slots.iter().take(ORDER_COUNT).enumerate() {
        let pixels_indexed = decode_image_indices(save_view.image_data_for_slot(*slot)?);
        photos.push(Photo {
            name: format!("IMG_PC{:02}.png", index + 1),
            width: IMG_W as u32,
            height: IMG_H as u32,
            pixels_indexed,
            kind: PhotoKind::Album,
            display_index: Some(index),
            physical_slot: Some(*slot),
            deleted: *deleted,
        });
    }

    let pixels_indexed =
        decode_image_indices(&save[GAME_FACE_OFFSET..GAME_FACE_OFFSET + TILE_BLOCK_SIZE]);
    photos.push(Photo {
        name: "IMG_PC31.png".to_string(),
        width: IMG_W as u32,
        height: IMG_H as u32,
        pixels_indexed,
        kind: PhotoKind::GameFace,
        display_index: None,
        physical_slot: None,
        deleted: false,
    });
    let pixels_indexed =
        decode_last_seen_image_indices(&save[LAST_SEEN_OFFSET..LAST_SEEN_OFFSET + TILE_BLOCK_SIZE]);
    photos.push(Photo {
        name: "IMG_PC32.png".to_string(),
        width: IMG_W as u32,
        height: LAST_SEEN_H as u32,
        pixels_indexed,
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
    let save_view = GbcamSave::new(save)?;
    let current_order = save_view.canonical_order()?;
    let mut delete = [false; ORDER_COUNT];
    for &slot in physical_slots_to_delete {
        if slot >= ORDER_COUNT {
            return Err(GbcamCoreError::InvalidPhysicalSlot { slot });
        }
        delete[slot] = true;
    }

    // Do NOT renumber remaining slots: the GB Camera ROM stores display
    // positions as-is and expects them to be preserved across deletes,
    // the same way its own firmware handles album-photo deletion.
    Ok(std::array::from_fn(|slot| {
        if delete[slot] { 0xFF } else { current_order[slot] }
    }))
}

/// Compute the 2-byte checksum for the 30-byte order table.
///
/// Algorithm (from GB Camera save format documentation):
/// - Left byte:  (0x2F + Σ order_bytes) mod 256
/// - Right byte: 0x15 ⊕ XOR(order_bytes)
pub fn order_table_checksum(order: &[u8; ORDER_COUNT]) -> [u8; 2] {
    checksum_seeded(order)
}

pub fn apply_album_delete(
    save: &[u8],
    physical_slots_to_delete: &[usize],
) -> Result<Vec<u8>, GbcamCoreError> {
    let order = album_order_after_delete(save, physical_slots_to_delete)?;
    apply_album_order(save, &order)
}

pub fn apply_album_recover(
    save: &[u8],
    physical_slots_to_recover: &[usize],
) -> Result<Vec<u8>, GbcamCoreError> {
    let save_view = GbcamSave::new(save)?;
    let mut order = save_view.canonical_order()?;
    let mut used_positions = [false; ORDER_COUNT];
    for &position in &order {
        let position = position as usize;
        if position < ORDER_COUNT {
            used_positions[position] = true;
        }
    }

    for &slot in physical_slots_to_recover {
        if slot >= ORDER_COUNT {
            return Err(GbcamCoreError::InvalidPhysicalSlot { slot });
        }
        if (order[slot] as usize) < ORDER_COUNT {
            continue;
        }
        let position = used_positions
            .iter()
            .position(|&used| !used)
            .ok_or(GbcamCoreError::NoFreeDisplayPosition)?;
        order[slot] = position as u8;
        used_positions[position] = true;
    }

    apply_album_order(save, &order)
}

pub fn apply_album_reorder(
    save: &[u8],
    physical_slots_in_display_order: &[usize],
) -> Result<Vec<u8>, GbcamCoreError> {
    GbcamSave::new(save)?;
    let mut seen = [false; ORDER_COUNT];
    let mut order = [0xFF; ORDER_COUNT];
    for (display_position, &slot) in physical_slots_in_display_order.iter().enumerate() {
        if slot >= ORDER_COUNT {
            return Err(GbcamCoreError::InvalidPhysicalSlot { slot });
        }
        if seen[slot] {
            return Err(GbcamCoreError::DuplicatePhysicalSlot { slot });
        }
        if display_position >= ORDER_COUNT {
            return Err(GbcamCoreError::InvalidDisplayPosition {
                position: display_position,
            });
        }
        seen[slot] = true;
        order[slot] = display_position as u8;
    }

    apply_album_order(save, &order)
}

pub fn apply_album_rebuild_from_nonblank_slots(save: &[u8]) -> Result<Vec<u8>, GbcamCoreError> {
    ensure_save_size(save)?;
    let slots = (0..ORDER_COUNT)
        .filter(|&slot| {
            let base = PHOTO_BASE + slot * TILE_BLOCK_SIZE;
            save[base..base + PHOTO_IMAGE_SIZE]
                .iter()
                .any(|&byte| byte != 0x00)
        })
        .collect::<Vec<_>>();
    apply_album_reorder(save, &slots)
}

pub fn apply_album_order(
    save: &[u8],
    order: &[u8; ORDER_COUNT],
) -> Result<Vec<u8>, GbcamCoreError> {
    ensure_save_size(save)?;
    let checksum = order_table_checksum(order);
    // Bottleneck: clones the full 128 KB save on every album operation.
    // An in-place mutation API would avoid this, but callers currently diff
    // old vs. new without keeping a separate copy.
    let mut updated = save.to_vec();
    // Primary order table + checksum
    updated[ORDER_OFFSET_PRIMARY..ORDER_OFFSET_PRIMARY + ORDER_COUNT].copy_from_slice(order);
    updated[ORDER_CHECKSUM_OFFSET..ORDER_CHECKSUM_OFFSET + 2].copy_from_slice(&checksum);
    // Echo order table + checksum (mirror of primary)
    updated[ORDER_OFFSET..ORDER_OFFSET + ORDER_COUNT].copy_from_slice(order);
    updated[ORDER_ECHO_CHECKSUM_OFFSET..ORDER_ECHO_CHECKSUM_OFFSET + 2].copy_from_slice(&checksum);
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

pub fn write_palette_png(
    path: &Path,
    pixels_indexed: &[u8],
    palette: PaletteId,
) -> Result<(), GbcamCoreError> {
    let file = fs::File::create(path)?;
    let height = (pixels_indexed.len() / IMG_W) as u32;
    let mut enc = png::Encoder::new(file, IMG_W as u32, height);
    enc.set_color(png::ColorType::Rgb);
    enc.set_depth(png::BitDepth::Eight);
    enc.write_header()?
        .write_image_data(&indexed_to_rgb8(pixels_indexed, palette))?;
    Ok(())
}

pub fn write_photos_to_dir(save: &[u8], dir: &Path) -> Result<Vec<String>, GbcamCoreError> {
    let photos = extract_photos(save)?;
    let mut written = Vec::with_capacity(photos.len());
    for photo in photos {
        let path = dir.join(&photo.name);
        write_png(&path, &indexed_to_gray8(&photo.pixels_indexed))?;
        written.push(photo.name);
    }
    Ok(written)
}

pub fn write_photos_to_dir_with_palette(
    save: &[u8],
    dir: &Path,
    palette: PaletteId,
) -> Result<Vec<String>, GbcamCoreError> {
    let photos = extract_photos(save)?;
    let mut written = Vec::with_capacity(photos.len());
    for photo in photos {
        let path = dir.join(&photo.name);
        write_palette_png(&path, &photo.pixels_indexed, palette)?;
        written.push(photo.name);
    }
    Ok(written)
}

#[cfg(test)]
mod tests {
    use super::*;

    const DELETE_FIXTURE_BEFORE: &[u8] =
        include_bytes!("../tests/fixtures/before_delete_first_photo.sav");
    const DELETE_FIXTURE_AFTER: &[u8] =
        include_bytes!("../tests/fixtures/after_delete_first_photo.sav");

    fn write_valid_state_vector(save: &mut [u8], order: &[u8; ORDER_COUNT]) {
        let checksum = order_table_checksum(order);
        save[ORDER_OFFSET_PRIMARY..ORDER_OFFSET_PRIMARY + ORDER_COUNT].copy_from_slice(order);
        save[ORDER_MAGIC_PRIMARY_OFFSET..ORDER_MAGIC_PRIMARY_OFFSET + MAGIC.len()]
            .copy_from_slice(MAGIC);
        save[ORDER_CHECKSUM_OFFSET..ORDER_CHECKSUM_OFFSET + 2].copy_from_slice(&checksum);
        save[ORDER_OFFSET..ORDER_OFFSET + ORDER_COUNT].copy_from_slice(order);
        save[ORDER_MAGIC_ECHO_OFFSET..ORDER_MAGIC_ECHO_OFFSET + MAGIC.len()].copy_from_slice(MAGIC);
        save[ORDER_ECHO_CHECKSUM_OFFSET..ORDER_ECHO_CHECKSUM_OFFSET + 2].copy_from_slice(&checksum);
    }

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
    fn decode_tile_pads_short_input() {
        let pixels = decode_tile(&[0b1010_1010]);

        assert_eq!(pixels[0], [1, 0, 1, 0, 1, 0, 1, 0]);
        assert_eq!(pixels[1], [0; 8]);
    }

    #[test]
    fn decode_image_maps_palette_pixels() {
        let mut data = vec![0u8; TILE_BLOCK_SIZE];
        data[0] = 0b1010_1010;
        data[1] = 0b1100_1100;

        let image = decode_image(&data);
        let indexed = decode_image_indices(&data);

        assert_eq!(&indexed[0..8], &[3, 2, 1, 0, 3, 2, 1, 0]);
        assert_eq!(&image[0..8], &[0, 104, 176, 255, 0, 104, 176, 255]);
        assert_eq!(image.len(), IMG_W * IMG_H);
    }

    #[test]
    fn decode_image_pads_short_input() {
        let image = decode_image(&[0b1010_1010]);

        assert_eq!(&image[0..8], &[176, 255, 176, 255, 176, 255, 176, 255]);
        assert_eq!(image.len(), IMG_W * IMG_H);
    }

    #[test]
    fn palette_presets_are_sorted_by_label() {
        assert_eq!(PaletteId::DEFAULT.index(), DEFAULT_PALETTE_INDEX);
        assert_eq!(PaletteId::DEFAULT.label(), "Game Boy Color - Pocket Camera");

        let labels = palette_labels().collect::<Vec<_>>();
        let mut sorted = labels.clone();
        sorted.sort_unstable_by_key(|label| palette_sort_key(label));

        assert_eq!(labels, sorted);
    }

    fn palette_sort_key(label: &str) -> String {
        label
            .replace("GB Studio", "Game Boy Studio")
            .to_ascii_lowercase()
    }

    #[test]
    fn palette_catalog_contains_added_presets() {
        let labels = palette_labels().collect::<Vec<_>>();

        assert!(labels.contains(&"Game Boy DMG - Classic Green"));
        assert!(labels.contains(&"GB Studio - Green"));
        assert!(labels.contains(&"Game Boy Pocket - Monochrome"));
        assert!(labels.contains(&"Game Boy Light - Green"));
        assert!(labels.contains(&"Game Boy Color Boot - Brown"));
        assert!(labels.contains(&"Game Boy Color Boot - Blue"));
        assert!(labels.contains(&"Game Boy Color Boot - Inverted"));
        assert!(labels.contains(&"CRT - Amber"));
    }

    #[test]
    fn indexed_pixels_render_to_selected_rgb_palette() {
        let pixels = [0, 1, 2, 3];

        let rgb = indexed_to_rgb8(&pixels, palette_by_label("Game Boy DMG - Original LCD"));

        assert_eq!(
            rgb,
            vec![208, 217, 60, 120, 164, 106, 84, 88, 84, 36, 70, 36]
        );
    }

    #[test]
    fn invalid_indexed_pixels_render_as_palette_zero() {
        assert_eq!(indexed_to_gray8(&[4]), vec![255]);
        assert_eq!(
            indexed_to_rgb8(&[4], palette_by_label("Game Boy DMG - Original LCD")),
            vec![208, 217, 60]
        );
    }

    fn palette_by_label(label: &str) -> PaletteId {
        let index = palette_labels()
            .position(|candidate| candidate == label)
            .expect("palette exists");
        PaletteId::from_index(index)
    }

    #[test]
    fn extract_photos_uses_display_order_and_appends_deleted_slots() {
        let mut save = vec![0u8; SAVE_SIZE];
        let mut order = [99u8; ORDER_COUNT];
        order[0] = 2;
        order[1] = 99;
        order[2] = 0;
        write_valid_state_vector(&mut save, &order);

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
            &indexed_to_gray8(&photos[0].pixels_indexed)[0..8],
            &[0, 104, 176, 255, 0, 104, 176, 255]
        );
    }

    #[test]
    fn album_order_after_delete_marks_slot_deleted_preserves_others() {
        let mut save = vec![0u8; SAVE_SIZE];
        let mut order = [0xFF; ORDER_COUNT];
        order[0] = 2;
        order[1] = 0;
        order[2] = 1;
        write_valid_state_vector(&mut save, &order);

        let order = album_order_after_delete(&save, &[2]).unwrap();

        // Slot 2 deleted → 0xFF; others keep their existing display values.
        assert_eq!(order[0], 2);
        assert_eq!(order[1], 0);
        assert_eq!(order[2], 0xFF);
        assert!(order[3..].iter().all(|&v| v == 0xFF));
    }

    #[test]
    fn apply_album_delete_updates_primary_echo_and_checksums() {
        let mut save = vec![0xAA; SAVE_SIZE];
        let mut order = [0xFF; ORDER_COUNT];
        order[0] = 0;
        order[1] = 1;
        write_valid_state_vector(&mut save, &order);

        let updated = apply_album_delete(&save, &[0]).unwrap();

        // Primary: slot 0 deleted, slot 1 unchanged
        assert_eq!(updated[ORDER_OFFSET_PRIMARY], 0xFF);
        assert_eq!(updated[ORDER_OFFSET_PRIMARY + 1], 1);
        // Echo must mirror primary
        assert_eq!(
            &updated[ORDER_OFFSET..ORDER_OFFSET + ORDER_COUNT],
            &updated[ORDER_OFFSET_PRIMARY..ORDER_OFFSET_PRIMARY + ORDER_COUNT]
        );
        // Both checksums must be equal and correct
        let expected_cs = order_table_checksum(
            updated[ORDER_OFFSET_PRIMARY..ORDER_OFFSET_PRIMARY + ORDER_COUNT]
                .try_into()
                .unwrap(),
        );
        assert_eq!(
            &updated[ORDER_CHECKSUM_OFFSET..ORDER_CHECKSUM_OFFSET + 2],
            &expected_cs
        );
        assert_eq!(
            &updated[ORDER_ECHO_CHECKSUM_OFFSET..ORDER_ECHO_CHECKSUM_OFFSET + 2],
            &expected_cs
        );
        // Bytes outside all four modified regions are unchanged
        assert_eq!(
            &updated[..ORDER_OFFSET_PRIMARY],
            &save[..ORDER_OFFSET_PRIMARY]
        );
        assert_eq!(
            &updated[ORDER_ECHO_CHECKSUM_OFFSET + 2..],
            &save[ORDER_ECHO_CHECKSUM_OFFSET + 2..]
        );
    }

    #[test]
    fn delete_fixtures_have_valid_state_vector_format() {
        assert_state_vector_format(
            DELETE_FIXTURE_BEFORE,
            &[0, 1, 2, 3, 4, 5, 6, 7],
            [0x35, 0x15],
        );
        assert_state_vector_format(
            DELETE_FIXTURE_AFTER,
            &[0xFF, 1, 2, 3, 4, 5, 6, 7],
            [0x34, 0xEA],
        );
    }

    #[test]
    fn apply_album_delete_matches_real_delete_fixture_state_vectors() {
        let updated = apply_album_delete(DELETE_FIXTURE_BEFORE, &[0]).unwrap();

        assert_eq!(
            &updated[ORDER_OFFSET_PRIMARY..ORDER_CHECKSUM_OFFSET + 2],
            &DELETE_FIXTURE_AFTER[ORDER_OFFSET_PRIMARY..ORDER_CHECKSUM_OFFSET + 2]
        );
        assert_eq!(
            &updated[ORDER_OFFSET..ORDER_ECHO_CHECKSUM_OFFSET + 2],
            &DELETE_FIXTURE_AFTER[ORDER_OFFSET..ORDER_ECHO_CHECKSUM_OFFSET + 2]
        );
    }

    #[test]
    fn canonical_state_vector_prefers_valid_primary_and_falls_back_to_echo() {
        let mut save = vec![0u8; SAVE_SIZE];
        let mut primary_order = [0xFF; ORDER_COUNT];
        primary_order[0] = 0;
        let mut echo_order = [0xFF; ORDER_COUNT];
        echo_order[1] = 0;
        write_valid_state_vector(&mut save, &primary_order);
        save[ORDER_OFFSET..ORDER_OFFSET + ORDER_COUNT].copy_from_slice(&echo_order);
        let echo_checksum = order_table_checksum(&echo_order);
        save[ORDER_ECHO_CHECKSUM_OFFSET..ORDER_ECHO_CHECKSUM_OFFSET + 2]
            .copy_from_slice(&echo_checksum);

        let report = GbcamSave::new(&save).unwrap().state_vector_report();
        assert_eq!(report.selected, Some(StateVectorCopyKind::Primary));
        assert!(!report.copies_match());

        save[ORDER_CHECKSUM_OFFSET] ^= 0xFF;
        let report = GbcamSave::new(&save).unwrap().state_vector_report();
        assert_eq!(report.selected, Some(StateVectorCopyKind::Echo));
        assert_eq!(report.selected_order().unwrap(), echo_order);
    }

    #[test]
    fn recover_deleted_album_slot_assigns_next_free_display_position() {
        let mut save = vec![0u8; SAVE_SIZE];
        let mut order = [0xFF; ORDER_COUNT];
        order[0] = 0;
        order[2] = 2;
        write_valid_state_vector(&mut save, &order);

        let updated = apply_album_recover(&save, &[1]).unwrap();

        assert_eq!(updated[ORDER_OFFSET_PRIMARY], 0);
        assert_eq!(updated[ORDER_OFFSET_PRIMARY + 1], 1);
        assert_eq!(updated[ORDER_OFFSET_PRIMARY + 2], 2);
        assert_eq!(
            &updated[ORDER_OFFSET_PRIMARY..ORDER_OFFSET_PRIMARY + ORDER_COUNT],
            &updated[ORDER_OFFSET..ORDER_OFFSET + ORDER_COUNT]
        );
    }

    #[test]
    fn reorder_album_slots_rebuilds_display_positions() {
        let mut save = vec![0u8; SAVE_SIZE];
        let order = [0xFF; ORDER_COUNT];
        write_valid_state_vector(&mut save, &order);

        let updated = apply_album_reorder(&save, &[7, 2, 4]).unwrap();

        let order = &updated[ORDER_OFFSET_PRIMARY..ORDER_OFFSET_PRIMARY + ORDER_COUNT];
        assert_eq!(order[7], 0);
        assert_eq!(order[2], 1);
        assert_eq!(order[4], 2);
        assert_eq!(order.iter().filter(|&&value| value != 0xFF).count(), 3);
    }

    #[test]
    fn rebuild_album_activates_nonblank_physical_slots() {
        let mut save = vec![0u8; SAVE_SIZE];
        let order = [0xFF; ORDER_COUNT];
        write_valid_state_vector(&mut save, &order);
        save[PHOTO_BASE + 3 * TILE_BLOCK_SIZE] = 0x42;
        save[PHOTO_BASE + 8 * TILE_BLOCK_SIZE + 17] = 0x24;

        let updated = apply_album_rebuild_from_nonblank_slots(&save).unwrap();

        let order = &updated[ORDER_OFFSET_PRIMARY..ORDER_OFFSET_PRIMARY + ORDER_COUNT];
        assert_eq!(order[3], 0);
        assert_eq!(order[8], 1);
        assert_eq!(order.iter().filter(|&&value| value != 0xFF).count(), 2);
    }

    #[test]
    fn validation_reports_state_vector_corruption() {
        let mut save = DELETE_FIXTURE_BEFORE.to_vec();
        save[ORDER_CHECKSUM_OFFSET] ^= 0xFF;

        let report = GbcamSave::new(&save).unwrap().validate();

        assert!(report.findings.iter().any(|finding| {
            finding.severity == ValidationSeverity::Error
                && finding.message.contains("primary state vector checksum")
        }));
    }

    #[test]
    fn metadata_parser_reads_slot_owner_fields() {
        let metadata = GbcamSave::new(DELETE_FIXTURE_BEFORE)
            .unwrap()
            .metadata_for_slot(0)
            .unwrap();

        assert_eq!(metadata.physical_slot, 0);
        assert_eq!(metadata.image_owner.user_id, [0x11, 0xA2, 0x38, 0x41]);
        assert_eq!(metadata.owner_checksum.stored, [0x7D, 0x0D]);
        assert!(metadata.owner_checksum.valid());
        assert!(metadata.owner_echo_matches);
        assert_eq!(
            metadata.camera_owner.as_ref().unwrap().user_id,
            [0x11, 0xA2, 0x38, 0x41]
        );
        assert_eq!(
            metadata.camera_owner_checksum.as_ref().unwrap().stored,
            [0x5B, 0xDF]
        );
        assert!(metadata.camera_owner_checksum.as_ref().unwrap().valid());
        assert!(metadata.camera_owner_echo_matches);
        assert_eq!(metadata.hotspots.len(), 5);
    }

    #[test]
    fn settings_block_parser_validates_fixture_checksums_and_fields() {
        let settings = GbcamSave::new(DELETE_FIXTURE_BEFORE)
            .unwrap()
            .settings_block();

        assert!(settings.primary.valid());
        assert!(settings.echo.valid());
        assert!(settings.copies_match);
        assert_eq!(settings.primary.stored_checksum, [0x74, 0x42]);
        assert_eq!(settings.printing_intensity, 0x40);
        assert_eq!(settings.tempo, 0x78);
        assert_eq!(settings.counters.pictures_taken_raw, [0x27, 0x00]);
        assert_eq!(settings.counters.pictures_erased_raw, [0x06, 0x00]);
        assert_eq!(settings.counters.pictures_transferred_raw, [0x00, 0x00]);
    }

    #[test]
    fn validation_reports_settings_corruption() {
        let mut save = DELETE_FIXTURE_BEFORE.to_vec();
        save[SETTINGS_CHECKSUM_PRIMARY_OFFSET] ^= 0xFF;

        let report = GbcamSave::new(&save).unwrap().validate();

        assert!(report.findings.iter().any(|finding| {
            finding.severity == ValidationSeverity::Error
                && finding.message.contains("primary settings checksum")
        }));
    }

    fn assert_state_vector_format(save: &[u8], prefix: &[u8], checksum: [u8; 2]) {
        assert_eq!(save.len(), SAVE_SIZE);

        let primary_order = &save[ORDER_OFFSET_PRIMARY..ORDER_OFFSET_PRIMARY + ORDER_COUNT];
        let echo_order = &save[ORDER_OFFSET..ORDER_OFFSET + ORDER_COUNT];
        assert_eq!(primary_order, echo_order);
        assert_eq!(&primary_order[..prefix.len()], prefix);
        assert!(primary_order[prefix.len()..].iter().all(|&b| b == 0xFF));

        assert_eq!(&save[0x11D0..0x11D5], b"Magic");
        assert_eq!(&save[0x11F5..0x11FA], b"Magic");

        let expected = order_table_checksum(primary_order.try_into().unwrap());
        assert_eq!(expected, checksum);
        assert_eq!(
            &save[ORDER_CHECKSUM_OFFSET..ORDER_CHECKSUM_OFFSET + 2],
            &checksum
        );
        assert_eq!(
            &save[ORDER_ECHO_CHECKSUM_OFFSET..ORDER_ECHO_CHECKSUM_OFFSET + 2],
            &checksum
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

        assert_eq!(indexed_to_gray8(&pc01.pixels_indexed), expected_pixels);
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
