use gbxcam_core::{apply_album_delete, extract_photos, write_photos_to_dir, write_png, PhotoKind};
use gbxcam_usb::{GbxCartInfo, Progress, UsbDev};
use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::ffi::CString;
use std::os::raw::c_char;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

#[no_mangle]
pub extern "C" fn gbcam_version() -> *mut c_char {
    CString::new(env!("CARGO_PKG_VERSION"))
        .expect("version contains no NUL bytes")
        .into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn gbcam_string_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        let _ = CString::from_raw(ptr);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tolik518_gbcam_NativeGbcam_version(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    java_string_or_throw(&mut env, env!("CARGO_PKG_VERSION").to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_tolik518_gbcam_NativeGbcam_loadGalleryFromFd<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    fd: jint,
    output_dir: JString<'local>,
    progress: JObject<'local>,
) -> jstring {
    let output_dir = match java_path(&mut env, output_dir) {
        Ok(path) => path,
        Err(e) => return throw(&mut env, e),
    };

    let result = {
        let mut progress = JniProgress::new(&mut env, progress);
        load_gallery_from_fd(fd, output_dir, &mut progress)
    };
    java_result(&mut env, result, "GB Camera gallery load failed")
}

#[no_mangle]
pub extern "system" fn Java_com_tolik518_gbcam_NativeGbcam_deletePhotosFromFd<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    fd: jint,
    save_path: JString<'local>,
    output_dir: JString<'local>,
    physical_slots_csv: JString<'local>,
    progress: JObject<'local>,
) -> jstring {
    let save_path = match java_path(&mut env, save_path) {
        Ok(path) => path,
        Err(e) => return throw(&mut env, e),
    };
    let output_dir = match java_path(&mut env, output_dir) {
        Ok(path) => path,
        Err(e) => return throw(&mut env, e),
    };
    let physical_slots_csv = match java_string(&mut env, physical_slots_csv) {
        Ok(value) => value,
        Err(e) => return throw(&mut env, e),
    };

    let result = {
        let mut progress = JniProgress::new(&mut env, progress);
        delete_photos_from_fd(
            fd,
            save_path,
            output_dir,
            &physical_slots_csv,
            &mut progress,
        )
    };
    java_result(&mut env, result, "GB Camera delete failed")
}

#[no_mangle]
pub extern "system" fn Java_com_tolik518_gbcam_NativeGbcam_loadGalleryFromSave<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    save_path: JString<'local>,
    output_dir: JString<'local>,
) -> jstring {
    let save_path = match java_path(&mut env, save_path) {
        Ok(path) => path,
        Err(e) => return throw(&mut env, e),
    };
    let output_dir = match java_path(&mut env, output_dir) {
        Ok(path) => path,
        Err(e) => return throw(&mut env, e),
    };

    java_result(
        &mut env,
        load_gallery_from_save(save_path, output_dir),
        "GB Camera cached gallery load failed",
    )
}

#[no_mangle]
pub extern "system" fn Java_com_tolik518_gbcam_NativeGbcam_dumpFromFd(
    mut env: JNIEnv,
    _class: JClass,
    fd: jint,
    output_dir: JString,
    erase_after: jni::sys::jboolean,
) -> jstring {
    let output_dir = match java_path(&mut env, output_dir) {
        Ok(path) => path,
        Err(e) => return throw(&mut env, e),
    };

    let result = {
        let mut progress = NoJniProgress;
        dump_from_fd(
            fd,
            output_dir,
            erase_after != jni::sys::JNI_FALSE,
            &mut progress,
        )
    };
    java_result(&mut env, result, "GB Camera dump failed")
}

struct JniProgress<'a, 'local> {
    env: &'a mut JNIEnv<'local>,
    callback: JObject<'local>,
}

impl<'a, 'local> JniProgress<'a, 'local> {
    fn new(env: &'a mut JNIEnv<'local>, callback: JObject<'local>) -> Self {
        Self { env, callback }
    }

    fn emit(&mut self, message: &str) {
        let Ok(message) = self.env.new_string(message) else {
            return;
        };
        let message = JObject::from(message);
        let _ = self.env.call_method(
            &self.callback,
            "onProgress",
            "(Ljava/lang/String;)V",
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
}

struct NoJniProgress;

impl Progress for NoJniProgress {}

fn load_gallery_from_fd(
    fd: jint,
    output_dir: PathBuf,
    progress: &mut impl Progress,
) -> Result<String, Box<dyn std::error::Error>> {
    std::fs::create_dir_all(&output_dir)?;

    progress.message("Connecting to GBxCart RW...");
    let (dev, info) = UsbDev::connect(fd, progress)?;
    progress.message("Connected. Dumping camera save...");
    let save = match dev.dump_save(progress) {
        Ok(save) => {
            finish_usb_session(&dev, true, progress);
            save
        }
        Err(e) => {
            finish_usb_session(&dev, false, progress);
            return Err(e.into());
        }
    };

    let save_path = output_dir.join("GAMEBOYCAMERA.sav");
    std::fs::write(&save_path, &save)?;
    progress.message("Decoding photos...");
    write_photos_to_dir(&save, &output_dir)?;
    progress.message("Gallery ready.");

    Ok(gallery_json(&save, &output_dir, &save_path, &info)?)
}

fn delete_photos_from_fd(
    fd: jint,
    save_path: PathBuf,
    output_dir: PathBuf,
    physical_slots_csv: &str,
    progress: &mut impl Progress,
) -> Result<String, Box<dyn std::error::Error>> {
    let slots = parse_physical_slots(physical_slots_csv)?;
    if slots.is_empty() {
        return Err("No photos selected.".into());
    }

    progress.message("Connecting to GBxCart RW...");
    let (dev, info) = UsbDev::connect(fd, progress)?;
    let save = std::fs::read(&save_path)?;
    let backup_path = timestamped_backup_path(&output_dir, "GAMEBOYCAMERA-before-delete")?;
    std::fs::create_dir_all(&output_dir)?;
    std::fs::write(&backup_path, &save)?;
    progress.message(&format!(
        "Pre-delete backup saved: {}",
        backup_path.display()
    ));
    if let Err(e) = dev.delete_album_photos(&save, &slots, progress) {
        finish_usb_session(&dev, false, progress);
        return Err(e.into());
    }
    finish_usb_session(&dev, true, progress);

    let updated = apply_album_delete(&save, &slots)?;
    std::fs::write(&save_path, &updated)?;
    write_photos_to_dir(&updated, &output_dir)?;
    progress.message("Gallery updated after delete.");

    Ok(gallery_json(&updated, &output_dir, &save_path, &info)?)
}

fn load_gallery_from_save(
    save_path: PathBuf,
    output_dir: PathBuf,
) -> Result<String, Box<dyn std::error::Error>> {
    std::fs::create_dir_all(&output_dir)?;
    let save = std::fs::read(&save_path)?;
    let info = GbxCartInfo {
        pcb_ver: 0,
        ofw_ver: 0,
        cfw_ver: 0,
        name: Some("Cached save".to_string()),
    };
    gallery_json(&save, &output_dir, &save_path, &info)
}

fn dump_from_fd(
    fd: jint,
    output_dir: PathBuf,
    erase_after: bool,
    progress: &mut impl Progress,
) -> Result<String, Box<dyn std::error::Error>> {
    std::fs::create_dir_all(&output_dir)?;

    let (dev, info) = UsbDev::connect(fd, progress)?;
    let save = match dev.dump_save(progress) {
        Ok(save) => save,
        Err(e) => {
            finish_usb_session(&dev, false, progress);
            return Err(e.into());
        }
    };

    let save_path = output_dir.join("GAMEBOYCAMERA.sav");
    std::fs::write(&save_path, &save)?;
    let photo_names = write_photos_to_dir(&save, &output_dir)?;

    if erase_after {
        if let Err(e) = dev.erase_save(&save, progress) {
            finish_usb_session(&dev, false, progress);
            return Err(e.into());
        }
    }
    finish_usb_session(&dev, true, progress);

    let image_list = photo_names
        .iter()
        .map(|name| format!("  {name}"))
        .collect::<Vec<_>>()
        .join("\n");

    Ok(format!(
        "Connected: {}\nSaved: {}\nExtracted {} image(s)\n{}{}",
        connected_label(&info),
        save_path.display(),
        photo_names.len(),
        image_list,
        if erase_after {
            "\nErased camera memory."
        } else {
            ""
        }
    ))
}

fn finish_usb_session(dev: &UsbDev, success: bool, progress: &mut impl Progress) {
    if success {
        dev.mark_session_done(progress);
    } else {
        progress.message("[debug][session] failure path: skipping OFW_ERROR_LED_ON so diagnostics do not intentionally leave Mode/Error LED red");
    }
    dev.finish_session(progress);
}

fn gallery_json(
    save: &[u8],
    output_dir: &Path,
    save_path: &Path,
    info: &GbxCartInfo,
) -> Result<String, Box<dyn std::error::Error>> {
    let photos = extract_photos(save)?;
    let mut json = String::new();
    json.push_str("{\"connected\":\"");
    json.push_str(&json_escape(&connected_label(info)));
    json.push_str("\",\"savePath\":\"");
    json.push_str(&json_escape(&save_path.to_string_lossy()));
    json.push_str("\",\"outputDir\":\"");
    json.push_str(&json_escape(&output_dir.to_string_lossy()));
    json.push_str("\",\"photos\":[");

    let mut first = true;
    for photo in photos
        .iter()
        .filter(|photo| photo.kind == PhotoKind::Album && !photo.deleted)
    {
        let path = output_dir.join(&photo.name);
        write_png(&path, &photo.pixels_gray8)?;
        if !first {
            json.push(',');
        }
        first = false;
        json.push_str("{\"name\":\"");
        json.push_str(&json_escape(&photo.name));
        json.push_str("\",\"path\":\"");
        json.push_str(&json_escape(&path.to_string_lossy()));
        json.push_str("\",\"displayIndex\":");
        json.push_str(&photo.display_index.unwrap_or(0).to_string());
        json.push_str(",\"physicalSlot\":");
        json.push_str(&photo.physical_slot.unwrap_or(0).to_string());
        json.push_str(",\"width\":");
        json.push_str(&photo.width.to_string());
        json.push_str(",\"height\":");
        json.push_str(&photo.height.to_string());
        json.push('}');
    }

    json.push_str("]}");
    Ok(json)
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

fn java_path(env: &mut JNIEnv, value: JString) -> Result<PathBuf, String> {
    java_string(env, value).map(PathBuf::from)
}

fn java_string(env: &mut JNIEnv, value: JString) -> Result<String, String> {
    env.get_string(&value)
        .map(|s| s.to_string_lossy().into_owned())
        .map_err(|e| e.to_string())
}

fn java_result(
    env: &mut JNIEnv,
    result: Result<String, Box<dyn std::error::Error>>,
    context: &str,
) -> jstring {
    match result {
        Ok(value) => java_string_or_throw(env, value),
        Err(e) => throw(env, format!("{context}: {e}")),
    }
}

fn java_string_or_throw(env: &mut JNIEnv, value: String) -> jstring {
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(e) => throw(env, e.to_string()),
    }
}

fn throw(env: &mut JNIEnv, message: String) -> jstring {
    let _ = env.throw_new("java/lang/RuntimeException", message);
    std::ptr::null_mut()
}

fn json_escape(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len());
    for ch in value.chars() {
        match ch {
            '"' => escaped.push_str("\\\""),
            '\\' => escaped.push_str("\\\\"),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            ch if ch.is_control() => escaped.push_str(&format!("\\u{:04x}", ch as u32)),
            ch => escaped.push(ch),
        }
    }
    escaped
}
