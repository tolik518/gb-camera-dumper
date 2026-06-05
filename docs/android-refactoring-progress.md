# Android refactoring — progress log

Tracks execution of the plan in [android-refactoring.md](android-refactoring.md).
Each phase is a separate commit; every phase must compile
(`:app:compileDebugJavaWithJavac`) and ideally smoke-test on device.

**Verification loop used:** a device is not currently attached, so each phase is
verified with a Java compile (`gradle :app:compileDebugJavaWithJavac`) rather than
`just android-apk-install`. The Rust `.so` is prebuilt and unchanged, so this
exercises the full Java compilation. Manual on-device smoke tests still need to be
run by the user before release.

| Phase | Scope | Status |
| --- | --- | --- |
| 0 | Builders & data hygiene | ✅ done |
| 1 | Leaf helpers (AppFiles, PaletteCatalog) + dead code | ✅ done |
| 2 | UsbDeviceController | ✅ done |
| 3 | ManualMergeStore + BackupRepository | ⬜ pending |
| 4 | GalleryPipeline + GalleryController | ⬜ pending |
| 5 | Dialog classes | ⬜ pending |
| 6 | MainScreen split | ⬜ pending |

---

## Phase 0 — Builders & data hygiene

**Goal:** remove the noisiest boilerplate (telescoping constructors, hand-written
state copies) and replace the fragile `isManualMerge()` path heuristic with an
explicit field. Pure mechanical, no behavior change.

### Changes
- `GalleryPhoto`
  - Added explicit `final boolean manualMerge` field (replaces the
    `path.contains("rgb-merged-manual")` heuristic). `isManualMerge()` now returns
    the field.
  - Added a fluent `GalleryPhoto.Builder` (6 required core fields via
    `builder(...)`, fluent setters for the rest with the previous defaults).
  - Replaced the three telescoping constructors with a single private
    `GalleryPhoto(Builder)` constructor.
  - `withDeleted(...)` now goes through `toBuilder()`.
- `GalleryState`
  - Added `withPhotos(List<GalleryPhoto>)`; replaced the 9 hand-written
    `new GalleryState(...)` photo-swap copies across `MainActivity` and
    `RgbMergeDetector`.
- Persistence (`manual-merges.json`)
  - `saveManualMerges` writes `"manualMerge": true`.
  - `loadManualMerges` reads `manualMerge`, falling back to the old
    `path.contains("rgb-merged-manual")` heuristic for files written before the
    field existed (backward compatible).

### Verification
- `:app:compileDebugJavaWithJavac` — ✅ BUILD SUCCESSFUL
- On-device smoke test — ⬜ pending (no device attached; user to verify load /
  palette switch / select-merge-delete-recover / manual merge persistence)

---

## Phase 1 — Extract leaf helpers + delete dead code

**Goal:** pull stateless helpers out of `MainActivity`/`PhotoExporter` into focused
classes and remove confirmed-dead API. No logic change.

### Changes
- New `AppFiles` — `dumpsDir(ctx)`, `appFilesDir(ctx, type)`, `safeFilePart`,
  `safeFolderName`. Dedupes the `appFilesDir`/safe-name logic that was copied in
  both `MainActivity` and `PhotoExporter`. `MainActivity.dumpsDir()`/
  `appFilesDir()` are now thin one-line delegates (kept to avoid churning ~15 call
  sites that Phase 4 will move to the controller anyway).
- New `PaletteCatalog` — owns native `paletteLabels`/`paletteColors` loading,
  `parseRow`, fallbacks, and the `labelFor(i)`/`colorsFor(i)` lookups. `MainActivity`
  now holds a single `PaletteCatalog palettes` field instead of the two raw arrays.
- Deleted dead code:
  - `PhotoExporter`: 5 unused overloads (`exportSelected(ctx,gallery)`,
    `exportSelected(ctx,gallery,boolean)`, `exportAll`, two `exportPhotos(...)`).
    The reachable `exportSelected(ctx,gallery,int[],boolean)` stays; the
    `exportPhotos(...,int[],...)` impl is now `private`.
  - `MainActivity`: unused `previewButton(...)` and no-arg `recolorCachedGallery()`.

### Result
- `MainActivity` 2642 → 2539 lines; `PhotoExporter` 356 → 320 lines.

### Verification
- `:app:compileDebugJavaWithJavac` — ✅ BUILD SUCCESSFUL
- On-device smoke test — ⬜ pending (verify export/share, palette switch, backup
  thumbnails still render).

---

## Phase 2 — Extract the USB layer

**Goal:** move all USB plumbing (discovery, the attach/detach/permission receiver,
and the permission-gated action flow) out of `MainActivity` into a focused
controller, leaving `MainActivity` as the host of the UI reactions.

### Changes
- New `UsbDeviceController` owns `usbManager`, `selectedDevice`, `pendingAction`,
  the `BroadcastReceiver`, `register()`/`unregister()`, `permissionIntent()`,
  `usbDeviceFrom()`, `refresh()` (was `refreshDevice`) and `withPermission()`
  (was `runWithPermission`). Exposes `manager()`, `device()`, `isConnected()`.
- UI reactions are delegated through `UsbDeviceController.Listener`, which
  `MainActivity` implements: `onUsbLog`, `onConnectionChanged`, `onDeviceAttached`,
  `onDeviceDetached`, `onPermissionGranted`, `onPermissionDenied`. The exact
  attach/detach/permission behavior (screen connection state, auto-load, startup
  step colors, disconnect log) is preserved in those callbacks.
- Call sites updated: `operationRunner.*(usb.manager(), usb.device(), ...)`,
  `usb.withPermission(...)`, `usb.isConnected()`. The startup cartridge poll
  (`doStartupCartridgeCheck`) stays in `MainActivity` for now — it is really
  StartupDialog logic (Phase 5) that happens to read USB via `usb.manager()/device()`.
- Removed now-unused imports (`BroadcastReceiver`, `IntentFilter`, `PendingIntent`,
  `UsbManager`, `Context`, `SuppressLint`).

### Result
- `MainActivity` 2539 → 2468 lines; new `UsbDeviceController` 164 lines.

### Verification
- `:app:compileDebugJavaWithJavac` — ✅ BUILD SUCCESSFUL
- On-device smoke test — ⬜ pending (verify: plug/unplug detection, permission
  prompt on first load, auto-load on attach, startup popup step colors).
