use gbxcam_core::{
    apply_album_delete, apply_album_recover, apply_album_reorder, extract_photos, palette_colors,
    palette_labels, write_palette_png, GbcamSave, PaletteId, PhotoKind, ValidationSeverity,
    DEFAULT_PALETTE_INDEX,
};
use gbxcam_usb::{GbxCartInfo, Progress, UsbDev};
use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jstring};
use jni::strings::JNIString;
use jni::Env;
use std::ffi::CString;
use std::os::raw::c_char;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

type AppResult<T> = std::result::Result<T, Box<dyn std::error::Error>>;

#[no_mangle]
pub extern "C" fn gbcam_version() -> *mut c_char {
    CString::new(env!("CARGO_PKG_VERSION"))
        .expect("version contains no NUL bytes")
        .into_raw()
}

/// # Safety
///
/// If `ptr` is non-null, it must be a pointer returned by `gbcam_version` that
/// has not already been freed.
#[no_mangle]
pub unsafe extern "C" fn gbcam_string_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        let _ = CString::from_raw(ptr);
    }
}

#[no_mangle]
pub extern "system" fn Java_fyi_r0_gbxcam_NativeGbcam_version(
    env: jni::EnvUnowned,
    _class: JClass,
) -> jstring {
    with_jni(&env, |env| java_string_or_throw(env, env!("CARGO_PKG_VERSION").to_string()))
}

#[no_mangle]
pub extern "system" fn Java_fyi_r0_gbxcam_NativeGbcam_defaultPaletteIndex(
    _env: jni::EnvUnowned,
    _class: JClass,
) -> jint {
    DEFAULT_PALETTE_INDEX as jint
}

#[no_mangle]
pub extern "system" fn Java_fyi_r0_gbxcam_NativeGbcam_paletteLabels(
    env: jni::EnvUnowned,
    _class: JClass,
) -> jstring {
    with_jni(&env, |env| {
        java_string_or_throw(env, palette_labels().collect::<Vec<_>>().join("\n"))
    })
}

#[no_mangle]
pub extern "system" fn Java_fyi_r0_gbxcam_NativeGbcam_paletteColors(
    env: jni::EnvUnowned,
    _class: JClass,
) -> jstring {
    with_jni(&env, |env| {
        let colors = palette_colors()
            .map(|palette| {
                palette
                    .iter()
                    .map(|color| format!("{:02X}{:02X}{:02X}", color[0], color[1], color[2]))
                    .collect::<Vec<_>>()
                    .join(",")
            })
            .collect::<Vec<_>>()
            .join("\n");
        java_string_or_throw(env, colors)
    })
}

#[no_mangle]
pub extern "system" fn Java_fyi_r0_gbxcam_NativeGbcam_loadGalleryFromFd<'local>(
    env: jni::EnvUnowned<'local>,
    _class: JClass<'local>,
    fd: jint,
    output_dir: JString<'local>,
    palette_index: jint,
    progress: JObject<'local>,
) -> jstring {
    with_jni(&env, |env| {
        let output_dir = match java_path(env, output_dir) {
            Ok(path) => path,
            Err(e) => return throw(env, e),
        };
        let result = {
            let mut progress = JniProgress::new(env, progress);
            load_gallery_from_fd(fd, output_dir, palette_from_jint(palette_index), &mut progress)
        };
        java_result(env, result, "GB Camera gallery load failed")
    })
}

#[no_mangle]
pub extern "system" fn Java_fyi_r0_gbxcam_NativeGbcam_deletePhotosFromFd<'local>(
    env: jni::EnvUnowned<'local>,
    _class: JClass<'local>,
    fd: jint,
    save_path: JString<'local>,
    output_dir: JString<'local>,
    physical_slots_csv: JString<'local>,
    palette_index: jint,
    progress: JObject<'local>,
) -> jstring {
    with_jni(&env, |env| {
        let save_path = match java_path(env, save_path) {
            Ok(path) => path,
            Err(e) => return throw(env, e),
        };
        let output_dir = match java_path(env, output_dir) {
            Ok(path) => path,
            Err(e) => return throw(env, e),
        };
        let physical_slots_csv = match java_string(env, physical_slots_csv) {
            Ok(value) => value,
            Err(e) => return throw(env, e),
        };
        let result = {
            let mut progress = JniProgress::new(env, progress);
            delete_photos_from_fd(
                fd,
                save_path,
                output_dir,
                &physical_slots_csv,
                palette_from_jint(palette_index),
                &mut progress,
            )
        };
        java_result(env, result, "GB Camera delete failed")
    })
}

#[no_mangle]
pub extern "system" fn Java_fyi_r0_gbxcam_NativeGbcam_recoverPhotosFromFd<'local>(
    env: jni::EnvUnowned<'local>,
    _class: JClass<'local>,
    fd: jint,
    save_path: JString<'local>,
    output_dir: JString<'local>,
    physical_slots_csv: JString<'local>,
    palette_index: jint,
    progress: JObject<'local>,
) -> jstring {
    with_jni(&env, |env| {
        let save_path = match java_path(env, save_path) {
            Ok(path) => path,
            Err(e) => return throw(env, e),
        };
        let output_dir = match java_path(env, output_dir) {
            Ok(path) => path,
            Err(e) => return throw(env, e),
        };
        let physical_slots_csv = match java_string(env, physical_slots_csv) {
            Ok(value) => value,
            Err(e) => return throw(env, e),
        };
        let result = {
            let mut progress = JniProgress::new(env, progress);
            recover_photos_from_fd(
                fd,
                save_path,
                output_dir,
                &physical_slots_csv,
                palette_from_jint(palette_index),
                &mut progress,
            )
        };
        java_result(env, result, "GB Camera recover failed")
    })
}

#[no_mangle]
pub extern "system" fn Java_fyi_r0_gbxcam_NativeGbcam_recoverPhotosFromSave<'local>(
    env: jni::EnvUnowned<'local>,
    _class: JClass<'local>,
    save_path: JString<'local>,
    output_dir: JString<'local>,
    physical_slots_csv: JString<'local>,
    palette_index: jint,
    progress: JObject<'local>,
) -> jstring {
    with_jni(&env, |env| {
        let save_path = match java_path(env, save_path) {
            Ok(path) => path,
            Err(e) => return throw(env, e),
        };
        let output_dir = match java_path(env, output_dir) {
            Ok(path) => path,
            Err(e) => return throw(env, e),
        };
        let physical_slots_csv = match java_string(env, physical_slots_csv) {
            Ok(value) => value,
            Err(e) => return throw(env, e),
        };
        let result = {
            let mut progress = JniProgress::new(env, progress);
            recover_photos_from_save(
                save_path,
                output_dir,
                &physical_slots_csv,
                palette_from_jint(palette_index),
                &mut progress,
            )
        };
        java_result(env, result, "Cached save recover failed")
    })
}

#[no_mangle]
pub extern "system" fn Java_fyi_r0_gbxcam_NativeGbcam_reorderPhotosFromFd<'local>(
    env: jni::EnvUnowned<'local>,
    _class: JClass<'local>,
    fd: jint,
    save_path: JString<'local>,
    output_dir: JString<'local>,
    physical_slots_csv: JString<'local>,
    palette_index: jint,
    progress: JObject<'local>,
) -> jstring {
    with_jni(&env, |env| {
        let save_path = match java_path(env, save_path) {
            Ok(path) => path,
            Err(e) => return throw(env, e),
        };
        let output_dir = match java_path(env, output_dir) {
            Ok(path) => path,
            Err(e) => return throw(env, e),
        };
        let physical_slots_csv = match java_string(env, physical_slots_csv) {
            Ok(value) => value,
            Err(e) => return throw(env, e),
        };
        let result = {
            let mut progress = JniProgress::new(env, progress);
            reorder_photos_from_fd(
                fd,
                save_path,
                output_dir,
                &physical_slots_csv,
                palette_from_jint(palette_index),
                &mut progress,
            )
        };
        java_result(env, result, "GB Camera reorder failed")
    })
}

#[no_mangle]
pub extern "system" fn Java_fyi_r0_gbxcam_NativeGbcam_loadGalleryFromSave<'local>(
    env: jni::EnvUnowned<'local>,
    _class: JClass<'local>,
    save_path: JString<'local>,
    output_dir: JString<'local>,
    palette_index: jint,
) -> jstring {
    with_jni(&env, |env| {
        let save_path = match java_path(env, save_path) {
            Ok(path) => path,
            Err(e) => return throw(env, e),
        };
        let output_dir = match java_path(env, output_dir) {
            Ok(path) => path,
            Err(e) => return throw(env, e),
        };
        java_result(
            env,
            load_gallery_from_save(save_path, output_dir, palette_from_jint(palette_index)),
            "GB Camera cached gallery load failed",
        )
    })
}

// Safety: called directly from a JNI native method; env is a valid non-null
// JNIEnv* for the duration of the call.
fn with_jni<'local, F>(env: &jni::EnvUnowned<'local>, f: F) -> jstring
where
    F: FnOnce(&mut Env<'local>) -> jstring,
{
    let mut guard: jni::AttachGuard<'local> =
        unsafe { jni::AttachGuard::from_unowned(env.as_raw()) };
    f(guard.borrow_env_mut())
}

struct JniProgress<'a, 'local> {
    env: &'a mut Env<'local>,
    callback: JObject<'local>,
}

impl<'a, 'local> JniProgress<'a, 'local> {
    fn new(env: &'a mut Env<'local>, callback: JObject<'local>) -> Self {
        Self { env, callback }
    }

    fn emit(&mut self, message: &str) {
        let Ok(message) = self.env.new_string(message) else {
            return;
        };
        let message = JObject::from(message);
        let _ = self.env.call_method(
            &self.callback,
            jni::jni_str!("onProgress"),
            jni::jni_sig!("(Ljava/lang/String;)V"),
            &[JValue::Object(&message)],
        );
    }
}

impl Progress for JniProgress<'_, '_> {
    fn message(&mut self, message: &str) {
        self.emit(message);
    }

    fn cartridge_header(&mut self, report: &gbxcam_core::CartridgeReport) {
        self.emit(&format!(
            "Cartridge: {} mapper 0x{:02X}",
            report.title, report.mapper
        ));
    }

    fn sram_progress(&mut self, done_bytes: usize, total_bytes: usize) {
        self.emit(&format!(
            "Reading SRAM: {}/{} KiB",
            done_bytes / 1024,
            total_bytes / 1024
        ));
    }

    fn write_progress(&mut self, done: usize, total: usize) {
        self.emit(&format!("Writing: {done}/{total}"));
    }
}

#[no_mangle]
pub extern "system" fn Java_fyi_r0_gbxcam_NativeGbcam_isGameBoyCameraInserted(
    _env: jni::EnvUnowned,
    _class: JClass,
    fd: jint,
) -> jboolean {
    let ok = UsbDev::connect(fd as std::os::unix::io::RawFd, &mut NoJniProgress)
        .and_then(|(dev, _info)| {
            let report = dev.read_cartridge_report();
            dev.finish_operation(report.is_ok(), &mut NoJniProgress);
            report
        })
        .map(|r| r.mapper == 0xFC) // 0xFC = MAPPER_MAC_GBD
        .unwrap_or(false);
    ok as jboolean
}

struct NoJniProgress;

impl Progress for NoJniProgress {}

fn load_gallery_from_fd(
    fd: jint,
    output_dir: PathBuf,
    palette: PaletteId,
    progress: &mut impl Progress,
) -> AppResult<String> {
    std::fs::create_dir_all(&output_dir)?;

    let (info, save) = with_gbxcart_session(fd, progress, |dev, _info, progress| {
        progress.message("Connected. Dumping camera save...");
        Ok(dev.dump_save(progress)?)
    })?;

    let save_path = output_dir.join("GAMEBOYCAMERA.sav");
    std::fs::write(&save_path, &save)?;
    progress.message("Decoding photos...");
    gallery_json(&save, &output_dir, &save_path, &info, palette)
}

fn delete_photos_from_fd(
    fd: jint,
    save_path: PathBuf,
    output_dir: PathBuf,
    physical_slots_csv: &str,
    palette: PaletteId,
    progress: &mut impl Progress,
) -> AppResult<String> {
    let slots = parse_physical_slots(physical_slots_csv)?;
    if slots.is_empty() {
        return Err("No photos selected.".into());
    }

    let save = std::fs::read(&save_path)?;
    let backup_path = timestamped_backup_path(&output_dir, "GAMEBOYCAMERA-before-delete")?;
    std::fs::create_dir_all(&output_dir)?;
    std::fs::write(&backup_path, &save)?;
    progress.message(&format!(
        "Pre-delete backup saved: {}",
        backup_path.display()
    ));

    let (info, _order) = with_gbxcart_session(fd, progress, |dev, _info, progress| {
        Ok(dev.delete_album_photos(&save, &slots, progress)?)
    })?;

    let updated = apply_album_delete(&save, &slots)?;
    std::fs::write(&save_path, &updated)?;
    progress.message("Gallery updated after delete.");
    gallery_json(&updated, &output_dir, &save_path, &info, palette)
}

fn recover_photos_from_fd(
    fd: jint,
    save_path: PathBuf,
    output_dir: PathBuf,
    physical_slots_csv: &str,
    palette: PaletteId,
    progress: &mut impl Progress,
) -> AppResult<String> {
    let slots = parse_physical_slots(physical_slots_csv)?;
    if slots.is_empty() {
        return Err("No deleted photos selected.".into());
    }

    let save = std::fs::read(&save_path)?;
    let backup_path = timestamped_backup_path(&output_dir, "GAMEBOYCAMERA-before-recover")?;
    std::fs::create_dir_all(&output_dir)?;
    std::fs::write(&backup_path, &save)?;
    progress.message(&format!(
        "Pre-recover backup saved: {}",
        backup_path.display()
    ));

    let (info, _order) = with_gbxcart_session(fd, progress, |dev, _info, progress| {
        Ok(dev.recover_album_photos(&save, &slots, progress)?)
    })?;

    let updated = apply_album_recover(&save, &slots)?;
    std::fs::write(&save_path, &updated)?;
    progress.message("Gallery updated after recover.");
    gallery_json(&updated, &output_dir, &save_path, &info, palette)
}

fn recover_photos_from_save(
    save_path: PathBuf,
    output_dir: PathBuf,
    physical_slots_csv: &str,
    palette: PaletteId,
    progress: &mut impl Progress,
) -> AppResult<String> {
    let slots = parse_physical_slots(physical_slots_csv)?;
    if slots.is_empty() {
        return Err("No deleted photos selected.".into());
    }

    let save = std::fs::read(&save_path)?;
    let backup_path = timestamped_backup_path(&output_dir, "GAMEBOYCAMERA-before-recover")?;
    std::fs::create_dir_all(&output_dir)?;
    std::fs::write(&backup_path, &save)?;
    progress.message(&format!(
        "Pre-recover backup saved: {}",
        backup_path.display()
    ));

    let updated = apply_album_recover(&save, &slots)?;
    std::fs::write(&save_path, &updated)?;
    progress.message("Cached gallery updated after recover.");

    let info = GbxCartInfo {
        pcb_ver: 0,
        ofw_ver: 0,
        cfw_ver: 0,
        name: Some("Cached save".to_string()),
    };
    gallery_json(&updated, &output_dir, &save_path, &info, palette)
}

fn reorder_photos_from_fd(
    fd: jint,
    save_path: PathBuf,
    output_dir: PathBuf,
    physical_slots_csv: &str,
    palette: PaletteId,
    progress: &mut impl Progress,
) -> AppResult<String> {
    let slots = parse_physical_slots(physical_slots_csv)?;

    let save = std::fs::read(&save_path)?;
    let backup_path = timestamped_backup_path(&output_dir, "GAMEBOYCAMERA-before-reorder")?;
    std::fs::create_dir_all(&output_dir)?;
    std::fs::write(&backup_path, &save)?;
    progress.message(&format!(
        "Pre-reorder backup saved: {}",
        backup_path.display()
    ));

    let (info, _order) = with_gbxcart_session(fd, progress, |dev, _info, progress| {
        Ok(dev.reorder_album_photos(&save, &slots, progress)?)
    })?;

    let updated = apply_album_reorder(&save, &slots)?;
    std::fs::write(&save_path, &updated)?;
    progress.message("Gallery updated after reorder.");
    gallery_json(&updated, &output_dir, &save_path, &info, palette)
}

fn load_gallery_from_save(
    save_path: PathBuf,
    output_dir: PathBuf,
    palette: PaletteId,
) -> AppResult<String> {
    std::fs::create_dir_all(&output_dir)?;
    let save = std::fs::read(&save_path)?;
    let info = GbxCartInfo {
        pcb_ver: 0,
        ofw_ver: 0,
        cfw_ver: 0,
        name: Some("Cached save".to_string()),
    };
    gallery_json(&save, &output_dir, &save_path, &info, palette)
}

fn with_gbxcart_session<T, P, F>(
    fd: jint,
    progress: &mut P,
    operation: F,
) -> AppResult<(GbxCartInfo, T)>
where
    P: Progress,
    F: FnOnce(&UsbDev, &GbxCartInfo, &mut P) -> AppResult<T>,
{
    progress.message("Connecting to GBxCart RW...");
    let (dev, info) = UsbDev::connect(fd, progress)?;
    let result = operation(&dev, &info, progress);
    dev.finish_operation(result.is_ok(), progress);
    result.map(|value| (info, value))
}

fn gallery_json(
    save: &[u8],
    output_dir: &Path,
    save_path: &Path,
    info: &GbxCartInfo,
    palette: PaletteId,
) -> Result<String, Box<dyn std::error::Error>> {
    let photos = extract_photos(save)?;
    let save_view = GbcamSave::new(save)?;

    let validation = save_view.validate();
    let (validation_errors, validation_warnings) =
        validation
            .findings
            .iter()
            .fold((0usize, 0usize), |(e, w), f| match f.severity {
                ValidationSeverity::Error => (e + 1, w),
                ValidationSeverity::Warning => (e, w + 1),
                ValidationSeverity::Info => (e, w),
            });

    let mut photos_json: Vec<serde_json::Value> = Vec::new();
    for photo in photos.iter().filter(|p| p.kind == PhotoKind::Album) {
        let path = output_dir.join(&photo.name);
        write_palette_png(&path, &photo.pixels_indexed, palette)?;

        let mut obj = serde_json::json!({
            "name": photo.name,
            "path": path.to_string_lossy(),
            "displayIndex": photo.display_index.unwrap_or(0),
            "physicalSlot": photo.physical_slot.unwrap_or(0),
            "width": photo.width,
            "height": photo.height,
            "indexedPixels": base64_encode(&photo.pixels_indexed),
            "blank": indexed_pixels_blank(&photo.pixels_indexed),
            "deleted": photo.deleted,
        });

        if let Some(slot) = photo.physical_slot {
            let metadata = save_view.metadata_for_slot(slot)?;
            obj["border"] = serde_json::json!(metadata.border);
            obj["copy"] = serde_json::json!(metadata.copy);
            obj["metadataValid"] = serde_json::json!(metadata.owner_checksum.valid());
            obj["ownerUserId"] = serde_json::json!(hex_bytes(&metadata.image_owner.user_id));
        }

        photos_json.push(obj);
    }

    let out = serde_json::json!({
        "connected": connected_label(info),
        "savePath": save_path.to_string_lossy(),
        "outputDir": output_dir.to_string_lossy(),
        "paletteIndex": palette.index(),
        "paletteName": palette.label(),
        "validationErrors": validation_errors,
        "validationWarnings": validation_warnings,
        "photos": photos_json,
    });

    Ok(serde_json::to_string(&out)?)
}

fn hex_bytes(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|byte| format!("{byte:02X}"))
        .collect::<Vec<_>>()
        .join(" ")
}

fn indexed_pixels_blank(pixels: &[u8]) -> bool {
    match pixels.split_first() {
        None => true,
        Some((first, rest)) => rest.iter().all(|pixel| pixel == first),
    }
}

fn base64_encode(bytes: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(((bytes.len() + 2) / 3) * 4);

    for chunk in bytes.chunks(3) {
        let b0 = chunk[0];
        let b1 = chunk.get(1).copied().unwrap_or(0);
        let b2 = chunk.get(2).copied().unwrap_or(0);

        out.push(TABLE[(b0 >> 2) as usize] as char);
        out.push(TABLE[(((b0 & 0x03) << 4) | (b1 >> 4)) as usize] as char);
        out.push(if chunk.len() > 1 {
            TABLE[(((b1 & 0x0F) << 2) | (b2 >> 6)) as usize] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            TABLE[(b2 & 0x3F) as usize] as char
        } else {
            '='
        });
    }

    out
}

fn palette_from_jint(index: jint) -> PaletteId {
    PaletteId::from_index(index.max(0) as usize)
}

fn connected_label(info: &GbxCartInfo) -> String {
    match (&info.name, info.cfw_ver) {
        (Some(name), cfw) if cfw >= 12 => format!(
            "{} (PCB v{}, OFW R{}, CFW L{})",
            name, info.pcb_ver, info.ofw_ver, info.cfw_ver
        ),
        (Some(name), _) => name.clone(),
        (_, cfw) if cfw > 0 => format!(
            "GBxCart RW v{} (OFW R{}, CFW L{})",
            info.pcb_ver, info.ofw_ver, info.cfw_ver
        ),
        _ => format!("GBxCart RW v{} (OFW R{})", info.pcb_ver, info.ofw_ver),
    }
}

fn parse_physical_slots(csv: &str) -> Result<Vec<usize>, Box<dyn std::error::Error>> {
    let mut slots = Vec::new();
    for part in csv
        .split(',')
        .map(str::trim)
        .filter(|part| !part.is_empty())
    {
        let slot = part.parse::<usize>()?;
        if slot >= gbxcam_core::ORDER_COUNT {
            return Err(format!("invalid physical slot {slot}").into());
        }
        if !slots.contains(&slot) {
            slots.push(slot);
        }
    }
    Ok(slots)
}

fn timestamped_backup_path(
    output_dir: &Path,
    prefix: &str,
) -> Result<PathBuf, Box<dyn std::error::Error>> {
    let seconds = SystemTime::now().duration_since(UNIX_EPOCH)?.as_secs();
    Ok(output_dir.join(format!("{prefix}-{seconds}.sav")))
}

fn java_path(env: &mut Env<'_>, value: JString) -> Result<PathBuf, String> {
    java_string(env, value).map(PathBuf::from)
}

fn java_string(env: &mut Env<'_>, value: JString) -> Result<String, String> {
    value.try_to_string(env).map_err(|e| e.to_string())
}

fn java_result(
    env: &mut Env<'_>,
    result: Result<String, Box<dyn std::error::Error>>,
    context: &str,
) -> jstring {
    match result {
        Ok(value) => java_string_or_throw(env, value),
        Err(e) => throw(env, format!("{context}: {e}")),
    }
}

fn java_string_or_throw(env: &mut Env<'_>, value: String) -> jstring {
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(e) => throw(env, e.to_string()),
    }
}

fn throw(env: &mut Env<'_>, message: String) -> jstring {
    let _ = env.throw_new(jni::jni_str!("java/lang/RuntimeException"), JNIString::from(message));
    std::ptr::null_mut()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connected_label_includes_full_cfw_ver_above_255() {
        // cfw_ver 258 (0x0102) must appear as "L258" in the label, not "L2".
        // Before the fix, cfw_ver was stored as u8, truncating 258 → 2.
        let info = GbxCartInfo {
            pcb_ver: 5,
            ofw_ver: 4,
            cfw_ver: 258,
            name: Some("GBxCart RW".to_string()),
        };
        let label = connected_label(&info);
        assert_eq!(label, "GBxCart RW (PCB v5, OFW R4, CFW L258)");
    }

    #[test]
    fn base64_encode_matches_standard_padding() {
        assert_eq!(base64_encode(b""), "");
        assert_eq!(base64_encode(b"f"), "Zg==");
        assert_eq!(base64_encode(b"fo"), "Zm8=");
        assert_eq!(base64_encode(b"foo"), "Zm9v");
        assert_eq!(base64_encode(&[0, 1, 2, 3]), "AAECAw==");
    }

    #[test]
    fn indexed_pixels_blank_requires_one_flat_value() {
        assert!(indexed_pixels_blank(&[]));
        assert!(indexed_pixels_blank(&[2, 2, 2]));
        assert!(!indexed_pixels_blank(&[2, 2, 1]));
    }
}
