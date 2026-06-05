# Android app refactoring plan

How to break up the `fyi.r0.gbxcam` Java code into focused, testable units.
This is a structural refactor only — **no behavior should change**. Every phase
must still compile and `just android-apk-install` cleanly.

## TL;DR

`MainActivity` (2642 lines) and `MainScreen` (1313 lines) hold ~60% of the code
and do almost everything. The biggest win is pulling the business logic out of
`MainActivity` into a controller plus a handful of single-purpose collaborators,
and pulling the five large inline dialogs plus the share-size picker out of
`MainActivity` into their own classes.
Everything else is already reasonably cohesive.

For the cross-language split, see **§7** — short version: the boundary is mostly
right, but `RgbMergeDetector.java` (~700 lines of image processing) belongs in
the Rust core, and palette→RGB rendering is currently done on *both* sides.

---

## 1. Current state

| File | Lines | Role today | Verdict |
| --- | ---: | --- | --- |
| `MainActivity.java` | 2642 | Activity + controller + USB + 5 large dialogs + share-size picker + merge orchestration + file I/O + palette loading + threading | **God object — split** |
| `MainScreen.java` | 1313 | All UI construction + busy dialog + palette menu + tile grid + action-button state machine | **Too big — split view from state** |
| `RgbMergeDetector.java` | 707 | RGB/CRGB detection + merge image processing | OK (cohesive, large) |
| `UiStyle.java` | 599 | Static UI factory + theme `Palette` | OK |
| `PhotoExporter.java` | 356 | Export/share images + copy save files | OK (trim dead overloads) |
| `AppSettings.java` | 284 | `SharedPreferences` wrapper | OK (grab-bag, optional split) |
| `GalleryState.java` | 214 | Gallery data + selection queries + JSON parse + CSV building | Split mixed concerns |
| `GbcamOperationRunner.java` | 156 | USB operations on a worker thread w/ callbacks | **Model to copy** |
| `GalleryPhoto.java` | 139 | Photo data class (3 telescoping ctors) | Add builder |
| `PhotoRenderer.java` | 63 | Indexed pixels → `Bitmap`/PNG | OK |
| `NativeGbcam.java` | 54 | JNI declarations | OK |
| `GbxCartDevices.java` | 40 | Find CH340/GBxCart by VID/PID | OK |
| `GifView.java` | 38 | Animated GIF view | OK |

### The core problem: `MainActivity` does eight jobs

Reading [MainActivity.java](../apps/android/app/src/main/java/fyi/r0/gbxcam/MainActivity.java)
top to bottom, it is simultaneously:

1. **Activity** — lifecycle, `onActivityResult`, back handling.
2. **USB host** — `BroadcastReceiver`, device discovery, permission flow,
   startup cartridge polling (`doStartupCartridgeCheck`).
3. **`MainScreen.Listener`** — 20 user-action callbacks (the controller).
4. **`GbcamOperationRunner.Callback`** — log/progress/busy/error/loaded.
5. **Dialog factory** — `showStartupPopup`, `showSettingsDialog`,
   `showAboutDialog`, `showBackupPicker`, `showPhotoDetail`, the smaller
   `showShareSizeDialog`, and their dozens of private `View`-builder helpers
   (~900 lines of pure UI).
6. **Gallery pipeline** — `applyLocallyDeletedSlots` → `applyAutoRgbMerge` →
   `injectManualMerges` → `showGallery`, open-coded across the direct
   load/recolor paths.
7. **Manual-merge repository** — `loadManualMerges` / `saveManualMerges`
   (JSON file I/O), `addManualMergeVariant`, `injectManualMerges`, insert-index math.
8. **Palette catalog + file plumbing + threading** — `paletteLabels()`,
   `paletteColors()`, `paletteColorsForIndex`, `dumpsDir`, `appFilesDir`,
   `previewExecutor`, `backgroundExecutor`, `postToUi`, `runInBackground`.

### Specific smells

- **Duplicated load pipeline.** Roughly
  `(clear|apply)LocallyDeletedSlots → applyAutoRgbMerge → injectManualMerges → showGallery`
  is open-coded in three places — `onGalleryLoaded`, `loadCachedGallery`, and
  `recolorCachedGallery` — each with its own variation (a fresh camera read
  *clears* locally-deleted slots; cache/recolor *apply* them; only the first two
  call `loadManualMerges`/`rememberBackupPalette`). `loadBackupSave` doesn't
  duplicate it — it delegates to `loadCachedGallery`. Bugs must still be fixed in
  three places.
- **Telescoping constructors.** `GalleryState` is rebuilt by hand 8 times in
  `MainActivity` (plus once in `RgbMergeDetector`) —
  `new GalleryState(connected, savePath, outputDir, paletteIndex, paletteName,
  validationErrors, validationWarnings, photos)` — usually just to swap the
  `photos` list. `GalleryPhoto` has three overlapping constructors.
- **Fragile domain rule.** `GalleryPhoto.isManualMerge()` decides identity with
  `path.contains("rgb-merged-manual")` — a string match on a file path.
- **Scattered threading.** Three executors (`previewExecutor`,
  `backgroundExecutor`, `GbcamOperationRunner.io`) plus two `Handler`s, with no
  single owner. `destroyed` is checked ad hoc in `postToUi`/callbacks.
- **Palette logic split** between `MainActivity` (native load/parse, fallback,
  `paletteColorsForIndex`) and `MainScreen` (accent derivation, swatches, menu).
- **`MainScreen` mixes** view construction, the busy/loading dialog, the palette
  popup, tile rendering, **and** the `updateActions` button state machine.
- **Dead/unused API.** `PhotoExporter` has five package-private static overloads
  with no caller outside `PhotoExporter` — `exportSelected(ctx, gallery)`,
  `exportSelected(ctx, gallery, boolean)`, `exportAll`, and two
  `exportPhotos(...)` overloads. The reachable export path is
  `exportSelected(ctx, gallery, palette, includeDeleted)`, which delegates to the
  `int[] palette, selectedOnly, includeDeleted` implementation — keep that path.
  `MainActivity.recolorCachedGallery()` (no-arg) and `previewButton` are unused
  (confirmed: no callers).

---

## 2. Target architecture

Introduce light layering with packages. No DI framework — plain constructor
wiring from `MainActivity`.

```
fyi.r0.gbxcam
├── ui/                       view + Android glue
│   ├── MainActivity          thin: lifecycle + wiring only
│   ├── MainScreen            view tree + render (no business logic)
│   ├── GalleryActions        the action-button state machine (from MainScreen)
│   ├── dialog/
│   │   ├── StartupDialog
│   │   ├── SettingsDialog
│   │   ├── AboutDialog
│   │   ├── BackupPickerDialog
│   │   ├── PhotoDetailDialog
│   │   └── ShareSizeDialog
│   ├── palette/PaletteMenu   (from MainScreen.showPaletteMenu)
│   ├── UiStyle, GifView
├── control/
│   └── GalleryController     implements MainScreen.Listener (from MainActivity)
├── domain/
│   ├── GalleryPipeline       load/transform chain (dedup direct call sites)
│   ├── RgbMergeDetector
│   ├── PhotoRenderer
│   └── PaletteCatalog        native labels/colors + fallback + lookup
├── data/
│   ├── GalleryState, GalleryPhoto (+ builders)
│   ├── AppSettings
│   ├── ManualMergeStore      manual-merges.json read/write (from MainActivity)
│   └── BackupRepository      dumps dir, backup files, previews, import/export
├── usb/
│   ├── UsbDeviceController    receiver + permission + discovery (from MainActivity)
│   ├── GbcamOperationRunner
│   ├── GbxCartDevices, NativeGbcam
└── io/
    ├── PhotoExporter
    └── AppFiles              dumpsDir / appFilesDir / safe-name helpers
```

> Packages are optional if you'd rather keep one flat package — the important
> part is the **class split**, not the folders. If you do move files, do it in a
> dedicated "move only" commit so later diffs stay readable.

### Responsibility map (who owns what)

- **`MainActivity`** shrinks to: create collaborators, register/unregister the
  USB controller, route `onActivityResult`, forward lifecycle. Target < 250 lines.
- **`GalleryController`** becomes the `MainScreen.Listener` and
  `GbcamOperationRunner.Callback`. It owns the current `GalleryState` flow and
  orchestrates load/delete/recover/reorder/merge by delegating to the domain and
  data classes. No `View` code.
- **`UsbDeviceController`** owns `usbManager`, `selectedDevice`, the
  `BroadcastReceiver`, permission requests (`runWithPermission`), and the startup
  cartridge poll. Exposes callbacks like `onAttached/onDetached/onPermission`.
- **`GalleryPipeline`** is the single implementation of the transform chain so
  loading from camera, cache, or backup all share one path.
- **`ManualMergeStore`** owns `manual-merges.json` and the in-memory list.
- **`BackupRepository`** owns the dumps folder, backup enumeration, thumbnail
  previews, and import/export-save copying.
- **`PaletteCatalog`** owns native palette labels/colors, fallbacks, and
  `colorsForIndex`/`labelForIndex` — used by both the controller and the screen.
- **Dialog classes** each build and show one dialog, taking what they need via
  constructor and reporting results via a small callback interface.

---

## 3. Phased migration

Each phase is independently shippable and should be its own commit. Order is
chosen so the highest-risk God object shrinks first, with the safest moves up front.

### Phase 0 — Builders & data hygiene (safe, mechanical)
- Add `GalleryState.Builder` (or `copyWith(List<GalleryPhoto>)`) and replace the
  ~10 hand-written `new GalleryState(...)` copies in `MainActivity`/`RgbMergeDetector`.
- Add `GalleryPhoto.Builder`; collapse the three telescoping constructors.
- Replace `isManualMerge()` path-matching with an explicit `boolean manualMerge`
  field set at creation (persisted in `manual-merges.json`, which already round-trips
  every other merge field).
- **Win:** removes the noisiest boilerplate before bigger moves; pure mechanical.

### Phase 1 — Extract leaf helpers (no logic change)
- `AppFiles` ← `dumpsDir`, `appFilesDir`, `safeFilePart`/`safeFolderName`
  (dedupe the copy in `PhotoExporter`).
- `PaletteCatalog` ← `paletteLabels()`, `paletteColors()`, `parsePaletteRow`,
  `fallbackPaletteColors`, `paletteColorsForIndex`, `paletteLabel`. `MainScreen`
  and the controller both take a `PaletteCatalog`.
- Delete confirmed-dead `PhotoExporter` overloads and unused `MainActivity` helpers.

### Phase 2 — Extract the USB layer
- `UsbDeviceController` ← the `usbReceiver`, `registerUsbReceiver`,
  `permissionIntent`, `refreshDevice`, `runWithPermission`, `usbDeviceFrom`, and
  the startup cartridge poll (`doStartupCartridgeCheck` + its handler).
- It exposes `isConnected()`, `withPermission(Runnable)`, and an interface for
  attach/detach/permission events. `MainActivity` just creates it and forwards
  `onCreate`/`onDestroy`/`onNewIntent`.

### Phase 3 — Extract the data stores
- `ManualMergeStore` ← `loadManualMerges`, `saveManualMerges`,
  `addManualMergeVariant`, `replaceManualMergeVariant`, `injectManualMerges`,
  `mergeInsertIndex`, the `manualMerges` list.
- `BackupRepository` ← `showBackupPicker`'s data side: backup enumeration,
  `backupPreviewPhotos`, `loadCachedPreviews`, `backupPreviewIndices`, and the
  import/export-save file copies from `onActivityResult`. (The picker *dialog*
  moves in Phase 5.)

### Phase 4 — Extract the controller and the pipeline
- `GalleryPipeline` ← `applyLocallyDeletedSlots`, `applyAutoRgbMerge`,
  `filterEmptyDeletedPhotos`, `monoSourcePhotos`, `isEmptyImage`/cache,
  `injectManualMerges` glue. One method, e.g.
  `GalleryState process(GalleryState raw, Options opts)`, called by every load path.
- `GalleryController` ← all `MainScreen.Listener` and
  `GbcamOperationRunner.Callback` methods, `loadCachedGallery`, `loadBackupSave`,
  `recolorCachedGallery`, manual-merge orchestration
  (`onManualMergeRequested`, `applyManualMergeChanges`, `applyOrSaveDetailChanges`),
  and the locally-deleted mark/recover logic. It holds the executors and `postToUi`.
- After this, `MainActivity` is mostly wiring.

### Phase 5 — Extract the dialogs
Pull each `show*Dialog` plus its private builder helpers into its own class under
`ui/dialog/`. Each takes `Context`/`Activity`, a `PaletteCatalog`, and a result
callback:
- `StartupDialog` (incl. `connectionStep`, `boldSpan`, cartridge-check hooks).
- `SettingsDialog` (incl. `settingsActionRow`, `settingsPickerRow`,
  `settingsIdPickerRow`, `saveAll`).
- `AboutDialog` (incl. `aboutSection`, `aboutRow`, `openUrl`).
- `BackupPickerDialog` (incl. `backupRow`, `backupMosaic`; data via `BackupRepository`).
- `PhotoDetailDialog` (incl. `buildDetailScrollContent`, swipe nav,
  order/algo dropdowns, `runPreviewMerge`, the title/subtitle builders).
- `ShareSizeDialog` ← `showShareSizeDialog`.
- **Win:** removes ~900 lines from `MainActivity`; dialogs become reusable/testable.

### Phase 6 — Split `MainScreen`
- `GalleryActions` ← `updateActions` and the button enable/visibility/availability
  state machine; `MainScreen` calls `actions.update(galleryState, busy, deviceConnected)`.
- `PaletteMenu` ← `showPaletteMenu`, `paletteMenuItem`, `paletteMenuOrder`,
  swatch helpers.
- Optionally `BusyDialog` ← `showBusyDialog`/`updateBusyProgress`/`showBusyError`
  and the percent parser. `MainScreen` keeps the view tree + tile rendering.

---

## 4. Concrete extraction examples

**Collapse `GalleryState` copies** (Phase 0):

```java
// before — repeated 8× in MainActivity
return new GalleryState(gallery.connected, gallery.savePath, gallery.outputDir,
        gallery.paletteIndex, gallery.paletteName,
        gallery.validationErrors, gallery.validationWarnings, photos);

// after
return gallery.withPhotos(photos);
```

**One load pipeline** (Phase 4) replaces the three near-identical sequences in
`onGalleryLoaded`, `loadCachedGallery`, and `recolorCachedGallery`
(`loadBackupSave` already delegates to `loadCachedGallery`):

```java
GalleryState shown = pipeline.process(raw, new Pipeline.Options(
        settings, manualMergeStore, paletteIndex));
screen.showGallery(shown);
```

**Controller owns the listener, Activity just wires** (Phase 4):

```java
// MainActivity.onCreate, after refactor
usb        = new UsbDeviceController(this, usbCallbacks);
controller = new GalleryController(this, screen, runner, pipeline,
                 settings, manualMergeStore, backups, palettes, usb);
screen.setListener(controller);
runner.setCallback(controller);
```

---

## 5. Lower-priority cleanups

- `AppSettings` could split into `UiPrefs`, `MergePrefs`, `PalettePrefs`,
  `AlbumStatePrefs` if it keeps growing, but a single class is acceptable.
- Move CSV builders (`selectedPhysicalSlotsCsv`, `activePhysicalSlotsCsv`,
  `selectedActiveFirstPhysicalSlotsCsv`) out of the `GalleryState` *data* class
  into the controller or a small `AlbumOrder` helper — they encode operation
  params, not gallery state.
- Centralize threading: one `AppExecutors` (io / preview / main) injected where
  needed, replacing the three ad-hoc executors and manual `destroyed` guards.

---

## 6. Risks & verification

- **No XML layouts** — all UI is built in code, so dialog extraction is plain
  Java moves with low regression risk, but there are no layout tests. Verify each
  phase **manually on device**: load camera, palette change, select/merge/delete/
  recover, backups, import/export, share, photo-detail swipe + order/algo change.
- **Threading correctness** — preserve the generation counters
  (`recolorGeneration`, `displayGeneration`) and the `destroyed`/UI-thread guards
  exactly when moving them; these prevent stale async results clobbering newer state.
- **`isManualMerge` change** (Phase 0) touches persistence — keep reading the old
  path-based heuristic as a fallback for `manual-merges.json` written before the
  field existed.
- Keep each phase a separate commit; run `just android-apk-install` and smoke-test
  before moving on. Do file moves separately from logic changes.

### Suggested commit sequence
1. data builders + `withPhotos` + explicit `manualMerge` field
2. `AppFiles` + `PaletteCatalog`; delete dead code
3. `UsbDeviceController`
4. `ManualMergeStore` + `BackupRepository`
5. `GalleryPipeline` + `GalleryController` (the big one)
6. dialog classes
7. `MainScreen` split (`GalleryActions`, `PaletteMenu`, `BusyDialog`)

---

## 7. Rust ↔ Java boundary: what should move

The workspace splits into three Rust crates plus the Java app:

| Layer | Owns today |
| --- | --- |
| `gbcam-core` | Save parsing, tile/image decode, palette presets, album-order math, validation, PNG writing |
| `gbcam-usb` | CH340 + GBxCart RW protocol over a raw fd (`UsbDev`) |
| `gbcam-ffi` | JNI bridge: opens an fd-backed session, calls core, returns a JSON gallery + writes PNGs |
| Java app | UI, device/permission lifecycle, prefs, export/share, **RGB merge** |

**The boundary is mostly drawn correctly.** Rust owns the bytes-and-pixels lower
layer; Java owns the Android UI and lifecycle. There is essentially **nothing
that should move Rust → Java**. But there is one clear misplacement going the
other way, and one duplicated responsibility.

### 7.1 Java → Rust (strong): move `RgbMergeDetector` into `gbcam-core`

[RgbMergeDetector.java](../apps/android/app/src/main/java/fyi/r0/gbxcam/RgbMergeDetector.java)
(707 lines) is pure CPU image processing / computer vision living in Java:

- perceptual hashing — `pHash` with a hand-rolled 2D **DCT** (`dctCoefficient`),
  `dHash`, Hamming-distance thresholds (`RGB_PHASH_MAX_DISTANCE`, etc.);
- alignment — normalized cross-correlation (`ncc`) over a shift search window;
- a box `blur`, nearest-neighbour `resize`, grayscale conversion;
- channel composition — `mergePixels` building R/G/B(/Clear) output per algorithm.

It is the largest Java image-processing path, and it does its work the slow way:
`ImageData.from` calls `BitmapFactory.decodeFile(photo.path)` to read back the
**mono PNGs that Rust already wrote** (see `monoSourcePhotos` re-running
`loadGalleryFromSave` into a `rgb-merge-mono` dir purely to feed the merger). So
a single merge round-trips: Rust decodes SRAM → writes mono PNGs → Java
re-decodes PNGs → processes → writes merged PNG.

**Why move it:**

- It belongs next to the decode/palette code it conceptually extends; `gbcam-core`
  already holds `decode_image_indices`, `indexed_to_rgb8`, `write_palette_png`.
- Rust already has the source pixels in memory (`Photo.pixels_indexed` from
  `extract_photos`) — the merger could consume those directly and **eliminate the
  mono-PNG export + BitmapFactory re-decode entirely**.
- It becomes unit-testable in the Rust suite (today it has zero tests; detection
  thresholds and DCT are exactly what you want fixtures for) and faster.
- It removes the largest Java `BitmapFactory`/`getPixels` image-processing path,
  simplifying the Java refactor in §1–§6. The remaining fallback/blank-detection
  decodes are covered separately in §7.2 and §7.3.

**Suggested shape:** a core API like
`detect_rgb_sets(&[Photo], Options) -> Vec<MergeCandidate>` and
`merge_rgb(sources, order, algorithm) -> MergedImage`, surfaced through `gbcam-ffi`
(e.g. `autoMergeFromSave` returning merged entries in the gallery JSON, and a
`mergeRgb(...)` for manual merges / detail-view re-merges).

**What stays in Java:** the *merge-variant selection* — which order/algorithm a
user picked per image, the manual-merge list, and `manual-merges.json`
persistence — is UI state, not an algorithm. Keep it in Java
(`ManualMergeStore`, §3); it just calls the Rust merge instead of `RgbMergeDetector`.

**Caveat:** this is the largest single change in this document and it spans the
FFI. Do it as its own project *after* the Java-side refactor (§1–§6) settles, not
interleaved. Keep `RgbMergeDetector.java` until the Rust merge matches its output
on real saves (the algorithms must produce byte-identical or visually-equivalent
results, especially Adaptive).

### 7.2 Duplicated responsibility: palette → RGB rendering

> **Hard constraint: instant palette switching must stay instant.** Changing the
> palette re-colors the whole visible gallery with zero perceptible latency —
> `MainScreen.setPaletteIndex` → `refreshGalleryPalette` re-renders every tile
> **in memory from the base64 `indexedPixels`**, with no disk read and no Rust/JNI
> round-trip. This is the *reason* `indexedPixels` is shipped in the gallery JSON,
> and it is non-negotiable UX. Any change here must preserve it.

Coloring indexed pixels happens on **both** sides:

- **Rust** `write_palette_png` (called in `gallery_json`) renders every album
  photo to an RGB PNG on disk with the chosen palette.
- **Java** `PhotoRenderer.renderBitmap` re-renders the same photo in memory from
  the base64 `indexedPixels` that `gallery_json` *also* ships in the JSON.

The live gallery and export already prefer the indexed-pixel path; the on-disk
RGB PNG is only used for backup thumbnails (`backupRow` `setImageURI`), merged
photos, and as a fallback when indexed pixels are absent. Because Rust bakes a
*specific* palette into that PNG, it goes **stale** the moment the user switches
palette in-app — backup thumbnails and any fallback then show old colors.

**Decision — Java owns all live coloring (this is the only option compatible with
the constraint above):**

- **Keep** `indexedPixels` in the JSON and keep `PhotoRenderer` doing the
  in-memory recolor. Do not touch the instant-switch path.
- Make Rust's on-disk PNG **palette-independent** — write it grayscale/indexed
  once (cheaper than `indexed_to_rgb8` + RGB zlib) and treat it purely as a
  derived cache/fallback, so it can never diverge from the live palette.
- Colorize from `indexedPixels` at the remaining display sites too (backup
  thumbnails), instead of loading the baked RGB PNG, so thumbnails track the live
  palette as well.

**Rejected:** *Rust owns coloring* (drop `indexedPixels`, display the baked PNG,
re-render via a Rust/JNI call on palette change). This would put a disk write +
native round-trip on every palette switch — exactly the latency the current
design exists to avoid. Do not do this.

Note this also keeps the merge-to-Rust move (§7.1) compatible: RGB merges are
computed on grayscale and are palette-independent, so a palette switch must
**not** trigger a re-merge — keep the merged result cached and let only the mono
tiles recolor.

### 7.3 Smaller boundary items

- **Dead native binding.** `NativeGbcam.dumpFromFd` is declared in Java with **no
  caller**; its Rust counterparts (`Java_..._dumpFromFd`, `dump_from_fd`,
  `UsbDev::erase_save`/`write_photos_to_dir`) are reachable only through it. Either
  wire up the "dump & erase" feature or delete the binding + its FFI export.
- **Blank-photo detection.** Java's `decodeAndCheckEmpty` re-decodes a PNG to test
  if all pixels are equal; Rust already has the indexed pixels and even does blank
  detection in `apply_album_rebuild_from_nonblank_slots`. Adding a `blank: true`
  flag to the gallery JSON would delete the Java decode + `emptyImageCache`.
- **Stringly-typed interface.** Slots cross as CSV (`parse_physical_slots`),
  palettes as newline/comma strings (`parsePaletteRow`), pixels as hand-rolled
  base64. It works and is low-risk; only worth structuring if it churns.
