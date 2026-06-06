# Android refactoring — progress log

Tracks execution of the plan in [android-refactoring.md](android-refactoring.md).
Each phase is a separate commit; every phase must compile
(`:app:compileDebugJavaWithJavac`) and ideally smoke-test on device.

**Verification loop used:** early phases were verified with a Java compile
(`:app:compileDebugJavaWithJavac`); later phases and fixes were verified with
`just android-apk` or `just android-apk-install` when a device was attached.

| Phase | Scope | Status |
| --- | --- | --- |
| 0 | Builders & data hygiene | ✅ done |
| 1 | Leaf helpers (AppFiles, PaletteCatalog) + dead code | ✅ done |
| 2 | UsbDeviceController | ✅ done |
| 3 | ManualMergeStore + BackupRepository | ✅ done |
| 4a | GalleryPipeline (decode transform chain) | ✅ done |
| 5 (i) | Dialogs: About, ShareSize, Settings | ✅ done |
| 5 (ii) | Dialogs: BackupPicker, PhotoDetail | ✅ done |
| 5 (iii) | Dialog: Startup (USB-entangled) | ✅ done |
| 4b | GalleryController (listener/callback orchestration) | ✅ done |
| 6 | MainScreen split (GalleryActions, PaletteMenu, BusyDialog) | ✅ done |
| 7.3 | Boundary cleanup: blank flag + dead dump binding | ✅ done |

All planned Java-side phases are complete. Two follow-up fixes landed from
on-device testing: the `MainScreen` listener NPE (`bcde6be`) and the photo-detail
share committing pending merge changes (`746cbfd`).

### Final result
- `MainActivity` **2642 → 160 lines (−94%)**; `MainScreen` **1318 → 997 lines (−24%)**.
- 19 focused classes extracted across the layers (ui/dialogs, control, domain,
  data, usb, io), each compiling and exercised on device where possible.

### Phase 5 (iii) — StartupDialog
- New `StartupDialog` owns the startup connection guide UI, step label state,
  the USB cartridge polling handler, `connectionStep`, and `boldSpan`.
- `MainActivity` forwards USB attach/detach and successful gallery-load events
  into the dialog (`markDeviceAttached`, `markDeviceDetached`, `markCameraLoaded`)
  and keeps only lifecycle/wiring.
- The existing load button behavior is preserved: if no GBxCart is connected, it
  delegates to the controller so the existing toast/log behavior runs and the
  popup stays open.

### Verification
- `just android-apk-install` — ✅ BUILD SUCCESSFUL and APK installed on attached phone.

### §7.3 — Rust/Java Boundary Cleanup
- Rust gallery JSON now includes `blank` for each album photo, computed directly
  from `pixels_indexed`.
- Java parses `GalleryPhoto.blank` and `GalleryPipeline` uses it to filter blank
  deleted photos, removing the PNG re-decode path.
- `EmptyImageCache` was deleted. Backup preview caching now writes/reads a small
  `preview-photos.txt` manifest listing non-deleted, nonblank preview files
  instead of decoding PNGs to test flat images.
- Removed the unused `NativeGbcam.dumpFromFd` Java declaration and the matching
  unused JNI export/private helper in `gbcam-ffi`.

### Verification
- `cargo test -p gbxcam-ffi` — ✅ 3 passed
- `just android-apk-install` — ✅ BUILD SUCCESSFUL and APK installed on attached phone
- On-device smoke — ✅ startup dialog renders; cached gallery loads; auto-merged
  tiles are visible; no app fatal exception in logcat.

### Phase 6 — MainScreen split
- `GalleryActions` — the toolbar button enable/visible/availability state machine
  (`update(gallery, busy, selectMode, deviceConnected)`); buttons stay created/wired
  in `MainScreen`. Verified: select 3 mergeable → Save/Share/Delete/Merge appear.
- `PaletteMenu` — the palette popup, reading live palette state and reporting
  selection/favorite toggles via a `PaletteMenu.Host` on `MainScreen`. Verified:
  favorite star toggle + palette switch on device.
- `BusyDialog` — the busy overlay (gif, warning, percent, slow hint, error state);
  `MainScreen` keeps the `busy` flag and delegates show/dismiss/error/progress.
  Verified: manual merge drives show/dismiss without crash.

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
  `usb.withPermission(...)`, `usb.isConnected()`. At this phase, the startup
  cartridge poll stayed in `MainActivity`; Phase 5 (iii) later moved it into
  `StartupDialog`.
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
| `AppFiles` / `ShareSizeDialog` | 40 / 31 | leaf helpers |

**Remaining:** Phase 4b (`GalleryController` — move the ~40
`MainScreen.Listener` + operation-callback methods out; the largest single change
in the plan), optional StartupDialog, and Phase 6 (split `MainScreen`).

---

## Phase 4b — Extract GalleryController

**Goal (the plan's headline):** pull the gallery business logic out of
`MainActivity` so it becomes a thin Activity shell.

### Changes
- New `GalleryController` implements `MainScreen.Listener`,
  `GbcamOperationRunner.Callback`, `SettingsDialog.Host`, and
  `PhotoDetailDialog.Host`. It owns the gallery flow: every listener/callback
  method, load/delete/recover/reorder/merge orchestration, the selected
  `paletteIndex`, the `recolorGeneration` guard, `runInBackground`/`postToUi`,
  import/export coordination (`handleActivityResult`), and `autoLoadCamera`/
  `loadCachedGallery`. Bodies moved verbatim; `this`(Context)→`activity`,
  callback/`MainActivity.this`→`this`, Activity calls→`activity.*`.
- `MainScreen.listener` is now settable (`setListener`) — read lazily by click
  handlers, so the controller (which needs `screen`) can be created first and
  wired after. Verified no `listener.*` call runs during construction.
- `onGalleryLoaded`'s startup-label poke is delegated through an `onCameraLoaded`
  `Runnable` hook supplied by `MainActivity`.
- `MainActivity` now implements only `UsbDeviceController.Listener`. It keeps the
  lifecycle and USB-event reactions, delegating logging/auto-load to the
  controller via a small `log(...)` forwarder. After Phase 5 (iii),
  `StartupDialog` owns the startup popup and cartridge polling.

### Result
- **`MainActivity` 1064 → 371 lines** (2642 → 371 overall, **−86%**); new
  `GalleryController` 773 lines.

### Verification
- `:app:compileDebugJavaWithJavac` + `:app:assembleDebug` — ✅ BUILD SUCCESSFUL.
- **On-device smoke test (adb, no cartridge attached) — ✅ passed** after one fix:
  cache load on launch, startup popup, photo detail + live order/algorithm
  re-merge with persist ("Merge updated"), settings dialog, about dialog, palette
  switch (instant recolor — mono tiles greyscale, RGB merges stay colored),
  backups picker (thumbnail mosaics load), long-press selection → action bar.
  Camera-dependent ops (load/delete/recover/reorder to cartridge) not exercised
  — no GBxCart attached — but their code paths moved verbatim.
- **Bug found & fixed** (commit after this one): the `MainScreen` constructor's
  `Listener` parameter shadowed the field, so header/action buttons built in the
  constructor captured the (null) parameter rather than the field set by
  `setListener`. Tile taps worked (field) but the gear/Save/Delete crashed with an
  NPE. Fix: drop the constructor parameter so those lambdas read the field.
