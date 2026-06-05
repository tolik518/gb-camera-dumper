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
| 3 | ManualMergeStore + BackupRepository | ✅ done |
| 4a | GalleryPipeline (decode transform chain) | ✅ done |
| 5 (i) | Dialogs: About, ShareSize, Settings | ✅ done |
| 5 (ii) | Dialogs: BackupPicker, PhotoDetail | ✅ done |
| 5 (iii) | Dialog: Startup (USB-entangled) | ⏸ deferred — see note |
| 4b | GalleryController (listener/callback orchestration) | ⬜ pending |
| 4b | GalleryController (listener/callback orchestration) | ⬜ pending |
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

---

## Phase 3 — Extract the data stores

**Goal:** move manual-merge persistence and backup-save handling out of
`MainActivity` into dedicated stores. No behavior change.

### Changes
- New `ManualMergeStore(context)` owns the `manualMerges` list and
  `manual-merges.json`: `load`, `save`, `addVariant`, `replaceVariant`,
  `removeByPath`, `inject(gallery)`, and static `insertIndex(...)`. `MainActivity`
  calls these instead of its old private methods; `deleteSelectedManualMerges`
  now uses `removeByPath` + a single `save()`.
- New `BackupRepository(context, settings, emptyImages)` owns the dumps-folder
  backups: `listBackups`, `previewPhotos` (+ on-disk preview cache via
  `loadCachedPreviews`/`previewIndices`), `paletteIndexFor`/`rememberPalette`, and
  the `importSave`/`exportSave` file copies (the orchestration — `loadCachedGallery`,
  logging — stays in `MainActivity`). The backup picker *dialog* still lives in
  `MainActivity` for now (Phase 5) and calls `backups.previewPhotos(...)`.
- New `EmptyImageCache` leaf (blank-PNG detection + cache), shared by
  `BackupRepository.loadCachedPreviews` and the gallery pipeline's
  `filterEmptyDeletedPhotos`. Extracted because both sides needed it.
- Removed now-dead `compareLongsDescending` and 8 orphaned imports.

### Result
- `MainActivity` 2468 → 2183 lines. New: `ManualMergeStore` 180,
  `BackupRepository` 171, `EmptyImageCache` 38.

### Verification
- `:app:compileDebugJavaWithJavac` — ✅ BUILD SUCCESSFUL
- On-device smoke test — ⬜ pending (verify: manual merge add/edit/delete persists
  across restart; backups picker thumbnails; import/export .sav).

---

## Phase 3 on-device test

User smoke-tested connection + palette switching on device — OK. Continued.

---

## Phase 4a — Extract GalleryPipeline

Phase 4 is split into two reviewable commits (4a pipeline, 4b controller) because
the controller is large and entangled with dialogs (Phase 5).

**Goal:** dedup the decode-side transform chain that was open-coded in the three
load paths (`onGalleryLoaded`, `loadCachedGallery`, `recolorCachedGallery`).

### Changes
- New `GalleryPipeline(settings, emptyImages, logger)` owns `applyLocallyDeletedSlots`,
  `applyAutoRgbMerge`, `filterEmptyDeletedPhotos`, and `monoSourcePhotos`. The shared
  entry point is `process(raw, applyLocallyDeleted)`:
  - camera read → `process(g, false)` (locally-deleted set was just cleared);
  - cache / recolor → `process(g, true)` (re-apply locally-deleted).
- Manual merges are deliberately **not** in the pipeline: the camera/cache paths
  call `mergeStore.load()` then `mergeStore.inject(...)`, while the recolor path
  injects on the UI thread (to see the latest in-memory state). The
  `recolorGeneration` guards and the bg/UI thread split are preserved exactly.
- `monoSourcePhotos` is exposed (the detail-view preview merge uses it).

### Result
- `MainActivity` 2183 → 2118 lines; new `GalleryPipeline` 108 lines.

### Verification
- `:app:compileDebugJavaWithJavac` — ✅ BUILD SUCCESSFUL
- On-device smoke test — ⬜ pending (verify: camera load, cache load on restart,
  backup load, palette recolor, auto-RGB-merge appears, manual merges still inject).

---

## Phase 5 — Extract dialogs (reordered before Phase 4b)

Phase 5 is done before the controller (4b): the controller is the
`MainScreen.Listener`, and many listener methods open dialogs — pulling the
dialogs out first turns a circular dependency into plain calls. All dialog
classes stay in the flat `fyi.r0.gbxcam` package (they rely on package-private
access to `GalleryState`/`UiStyle`/`MainScreen`).

### Part (i) — About, ShareSize, Settings
- New `AboutDialog(activity, logger)` — `show(connectedGallery, deviceConnected)`;
  owns `aboutSection`/`aboutRow`/`openUrl`/`packageVersionName`.
- New `ShareSizeDialog` — static `show(activity, settings, onPicked)`.
- New `SettingsDialog(activity, settings, screen, host)` with a `Host` callback
  interface (`onAbout/onBackups/onImport/onExport/shareLogs/applyPaletteIcon/
  recolorGallery`). `MainActivity` implements `Host` (4 methods already existed as
  `MainScreen.Listener` methods; added `shareLogs`/`applyPaletteIcon`/`recolorGallery`).
  Owns `settingsActionRow`/`settingsPickerRow`/`settingsIdPickerRow` + its own
  `indexOf`/`shortLabelForId` (`indexOf` kept in `MainActivity` too, used by the
  photo-detail dialog extracted in part ii).

### Result so far
- `MainActivity` 2118 → 1705 lines. New: `AboutDialog` 159, `SettingsDialog` 320,
  `ShareSizeDialog` 31.

### Verification
- `:app:compileDebugJavaWithJavac` — ✅ BUILD SUCCESSFUL; full APK installed on device.
- On-device smoke test — ⬜ pending (verify: Settings toggles persist + RGB pickers
  show/hide + action rows; About links; share-size picker).

### Part (ii) — BackupPicker, PhotoDetail
- New `BackupPickerDialog(activity, screen, backups, previewExecutor, postToUi,
  onSelected)` — owns `backupRow`/`backupMosaic`; thumbnails load off the preview
  executor and post to the UI; selection calls back via `onSelected`.
- New `PhotoDetailDialog(activity, screen, settings, pipeline, previewExecutor,
  postToUi, currentPaletteColors, host)` — the big one: status/metadata chips,
  swipe nav, order/algorithm dropdowns, the live `runPreviewMerge`, and the
  title/subtitle/merge-label builders. `Host` keeps `applyOrSaveDetailChanges` and
  `shareSinglePhoto` in `MainActivity` (controller-side per the plan). The merge
  preview generation guard and bg/UI threading are preserved exactly.
- `MainActivity`'s now-dead `rounded`/`indexOf` helpers and 7 orphaned imports removed.

### Result
- `MainActivity` 1705 → ~1064 lines. New: `BackupPickerDialog` 213,
  `PhotoDetailDialog` 470.

### Verification
- `:app:compileDebugJavaWithJavac` — ✅ BUILD SUCCESSFUL; APK installed on device.
- On-device smoke test — ⬜ pending (verify: open photo, swipe between photos,
  change merged order/algorithm → live preview + persists, share single photo,
  backups thumbnails + restore).

### StartupDialog — deferred (decision)
The startup popup's two step labels are **live status**, updated from three
lifecycle points — USB attach (step 1 green + kick the cartridge poll), USB detach
(reset both), and `onGalleryLoaded` (step 2 green) — plus the async
`doStartupCartridgeCheck` poll (background open of the USB device + native probe,
re-armed every 12 s). Unlike every other dialog, it is a long-lived view bound to
the activity's hardware lifecycle, so it stays in `MainActivity` for now (the plan
flags it as the hardest). Revisit alongside Phase 4b if the controller ends up
owning the USB-lifecycle reactions.

---

## Milestone after Phase 5

`MainActivity`: **2642 → 1064 lines (−60%)**. Extracted, all compiling and the
APK installed on device:

| New class | Lines | Role |
| --- | ---: | --- |
| `PhotoDetailDialog` | 470 | detail view + swipe + merge preview |
| `SettingsDialog` | 320 | settings + RGB pickers + action rows |
| `BackupPickerDialog` | 213 | backup list + thumbnail mosaic |
| `UsbDeviceController` | 164 | USB discovery + permission flow |
| `AboutDialog` | 159 | about/links/license |
| `ManualMergeStore` | 180 | manual-merges.json + inject |
| `BackupRepository` | 171 | dumps backups + import/export |
| `GalleryPipeline` | 108 | decode transform chain |
| `PaletteCatalog` | 89 | native palette tables |
| `AppFiles` / `EmptyImageCache` / `ShareSizeDialog` | 40 / 38 / 31 | leaf helpers |

**Remaining:** Phase 4b (`GalleryController` — move the ~40
`MainScreen.Listener` + operation-callback methods out; the largest single change
in the plan), optional StartupDialog, and Phase 6 (split `MainScreen`).
