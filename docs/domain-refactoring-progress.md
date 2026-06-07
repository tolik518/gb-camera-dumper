# Domain refactoring — progress log

Tracks execution of the plan in [domain-refactoring.md](domain-refactoring.md).
Each phase is its own commit; every phase must compile
(`:app:compileDebugJavaWithJavac`) and is smoke-tested on device.

**Branch:** `refactor/domain-value-objects` (off `main`).

| Phase | Scope | Status |
| --- | --- | --- |
| A1 | `MergeAlgorithm` enum (algorithm value object) | ✅ done |
| A2 | `MergeOrder` value object | ✅ done |
| A3 | `MergeIdentity` | ↪ folded into Phase D (see plan) |
| B | `Palette` value object | ✅ done |
| C | `SlotSet` | ✅ done |
| D | `MergeInfo` + slim `GalleryPhoto` (incl. `MergeIdentity`) | ✅ done |
| E | extract `Selection` off `GalleryPhoto` | ✅ done |
| Slot | `Slot` value object | ✅ done |
| F | FFI as a context boundary (merge → core) | ⬜ not started |

Phases A–E landed; F not started.

### Result so far
- Two value objects introduced as the single source of truth for the RGB-merge
  parameters; **no behavior change**, all persisted strings byte-identical.
- `MainActivity`/`MainScreen` untouched in size; the win is *de-duplication and
  type-safety*, not line count — four scattered copies of algorithm labels and the
  order tables collapsed to one definition each.

---

## Phase A1 — `MergeAlgorithm` enum

**Commit:** `49f6229`

**Goal:** replace the parallel `ALGORITHM_IDS` / `ALGORITHM_LABELS` /
`ALGORITHM_SHORT_LABELS` arrays and the scattered algorithm-string handling with a
single enum.

### Changes
- New `MergeAlgorithm` enum — the single source of truth for the persisted ids
  (`basic`…`adaptive`), the full / short / compact labels, clear-channel
  compatibility (`compatible`/`compatibleIds`/`compatibleLabels`), and the
  no-clear resolve fallback (`resolve`). Default = `NORM_CLEAR_LUM`.
- `RgbMergeDetector` — keeps its public helper *names* (`algorithmLabel`,
  `algorithmShortLabel`, `compatibleAlgorithm*`, `ALGORITHM_IDS/_LABELS`) as thin
  delegators; the pixel dispatch (`mergePixels`/`writeMergedPng`) now switches on
  the enum; `resolveAlgorithm`/`isAlgorithmId`/`ALGO_*` constants removed.
- `AppSettings` — validates/defaults the merge algorithm via `MergeAlgorithm`
  (`validAlgorithm` helper) instead of `validChoice` against the arrays.
- `MainScreen.compactAlgorithmLabel` — via `MergeAlgorithm.compactLabel()`
  (removed its hand-written `if/else` ladder, the 3rd label copy).

### Verification
- `:app:compileDebugJavaWithJavac` — ✅ BUILD SUCCESSFUL.
- On-device — ✅ (see combined Phase A verification below).

---

## Phase A2 — `MergeOrder` value object

**Commit:** `4ad723e`

**Goal:** centralize the channel-order permutation tables, per-size defaults, and
validation.

### Changes
- New `MergeOrder` — owns `ORDERS_3` (6) / `ORDERS_4` (24), `DEFAULT_3`/`DEFAULT_4`,
  and `optionsFor(count)` / `defaultFor(count)` / `valid(order, count)`. (Static
  order authority for now; gains an instance/value form in Phase D when
  `MergeInfo` holds a `MergeOrder`.)
- `AppSettings` — `rgb3Order`/`rgb4Order`/`saveSettings` validate via `MergeOrder`;
  dropped the order tables, `DEFAULT_*` constants, and the now-unused `validChoice`.
- `SettingsDialog` / `PhotoDetailDialog` — use `MergeOrder.optionsFor/defaultFor`,
  removing the repeated `count == 4 ? *4 : *3` selection.
- Also finished the algorithm short-label dedup: `SettingsDialog`'s hand-written
  short-label array (the 4th copy) now derives from
  `MergeAlgorithm.allShortLabels()`.

### Verification
- `:app:compileDebugJavaWithJavac` — ✅ BUILD SUCCESSFUL.
- On-device — ✅ (see combined Phase A verification below).

---

## Phase A — on-device verification

Built and installed with `just android-apk-install` on a Huawei P30 (`ELE-L29`,
1080×2340). Verified the installed APK's dex actually contains `MergeAlgorithm`
and `MergeOrder` and that the removed `ALGORITHM_SHORT_LABELS` is gone (guards
against a stale APK, since the changes are behavior-preserving and look identical
at runtime).

Driven via `adb` (screenshots + `uiautomator`), **no cartridge attached** — same
constraint as the prior refactor's on-device tests:

- **Launch** — ✅ no `FATAL`/app crash in logcat; cached gallery (32 photos) loads.
- **Gallery captions** — ✅ "Manual Adapt 01-04" and "Manual Basic 08-10" render
  the compact algorithm label via `MergeAlgorithm.compactLabel()`; accessibility
  labels read "Merged CRGB, Manual Adapt 01-04" and "Merged BGR, Manual Basic
  08-10" (`mergedKind` order string + compact algo label).
- **Settings dialog** — ✅ 4-shot order = "CRGB", 3-shot order = "RGB" (from
  `MergeOrder`), default algorithm = "Norm+Clear" (`MergeAlgorithm.shortLabel` via
  the deduped `allShortLabels()`); the 4-shot dropdown lists the CRGB permutations
  from `MergeOrder.ORDERS_4`.
- **Photo detail (manual CRGB merge)** — ✅ title "Merged CRGB", subtitle "Manual
  merge · 01-04 · Adaptive ★", chips "Merged CRGB / Manually merged / Adaptive ★ /
  Sources 01-04", "Order: CRGB" dropdown, and the algorithm dropdown
  "Experimental Adaptive ★" (`MergeAlgorithm.label()`).

### Not exercised
- **Mutating** a merge variant (changing order/algorithm in the detail dropdown →
  live re-merge + persist) — dropdowns were opened but values left unchanged to
  avoid altering saved state. Worth a manual pass.
- **Camera-dependent ops** (load/delete/recover/reorder, fresh auto-merge) — no
  GBxCart cartridge attached; those code paths are unchanged by Phase A but should
  be confirmed when hardware is available.

---

## Phase B — `Palette` value object

**Commit:** `59e1fe9`

**Goal:** collapse the `(paletteIndex, paletteName)` pair into a value object.

### Changes
- New `Palette { index, name }` (colours stay a `PaletteCatalog` lookup, mirroring
  the core `PaletteId` thin-id design).
- `GalleryState` now carries a single `palette` field instead of the two; the
  constructor, `fromJson`, `withPalette(Palette)`, and `withPhotos` use it.
- Readers updated to `gallery.palette.index` / `gallery.palette.name`:
  `GalleryController`, `MainScreen`, `GalleryPipeline`, `PhotoExporter`.
- Left as `int` (legitimately): the FFI (`NativeGbcam`/`GbcamOperationRunner`),
  prefs (`AppSettings`/`BackupRepository`), and `MainScreen`'s array-indexing
  internals; `GalleryController` keeps its selected-index `int` field.

### Verification
- `:app:compileDebugJavaWithJavac` — ✅ BUILD SUCCESSFUL.
- On-device — ⬜ pending: re-confirm instant palette switching has zero latency
  (the §7.2 hard constraint) and that backup/export folder names still use the
  palette name.

---

## Phase C — `SlotSet`

**Commit:** `e1b5f0f`

**Goal:** move the slot-CSV builders off `GalleryState` (the read model) — they
encode operation parameters, not gallery state.

### Changes
- New `SlotSet` value object: `selected(gallery, deleted)` / `active(gallery)` /
  `selectedActiveFirst(gallery)` factories + `toCsv()`, producing the same
  comma-separated wire form the FFI's `parse_physical_slots` expects.
- `GalleryState`: dropped `selectedPhysicalSlotsCsv`, `activePhysicalSlotsCsv`,
  `selectedActiveFirstPhysicalSlotsCsv`, and `appendActiveSlots`.
- Callers updated: `GbcamOperationRunner` (delete/recover), `GalleryController`
  (recover-from-cache / move-first / compact); the "clear" op still passes `""`.

**Deferred:** the standalone `Slot` value object (`int physicalSlot` + `-1`
sentinel) — wide, and already abstracted by `isAlbumBacked()` /
`isActiveAlbumPhoto()`; folded into the Phase D `GalleryPhoto` slimming.

### Verification
- `:app:compileDebugJavaWithJavac` — ✅ BUILD SUCCESSFUL.
- On-device — ⬜ pending: delete / recover / move-first / compact / clear still
  target the right slots.

---

## Phase D — `MergeInfo` + slim `GalleryPhoto`

**Commit:** `5c418c0`

**Goal:** collapse `GalleryPhoto`'s 6 merge fields into one value object and kill
the `mergedRgb` boolean discriminator.

### Changes
- New `MergeInfo { kind, sourceCount, sourceStartDisplayIndex, algorithm, manual }`
  + `identity()`; owns the `"kind:start:count"` override key (moved off
  `GalleryPhoto`, also removing the static `mergeIdentity`).
- `GalleryPhoto`: a single `merge` field (null = ordinary photo); `isMerge()`
  replaces `mergedRgb`; `isManualMerge`/`isMergeableSource`/`mergeIdentity`
  delegate to it. The **Builder API is unchanged** — `build()` assembles
  `MergeInfo` — so every construction site (`GalleryState.fromJson`,
  `ManualMergeStore.load`, `RgbMergeDetector`) is untouched.
- Null-safe read accessors (`mergedKind()`/`mergedSourceCount()`/…) return the
  former field defaults, making every reader a mechanical field→method change the
  compiler fully checks.
- Readers updated across `PhotoDetailDialog`, `GalleryController`, `MainScreen`,
  `GalleryState`, `ManualMergeStore`, `RgbMergeDetector`, `GalleryPipeline`,
  `PhotoRenderer` (~140 sites, via a negative-lookahead pass to skip Builder
  setters).

**Deferred for behavior-safety:** typing `kind`→`MergeOrder` and
`algorithm`→`MergeAlgorithm` inside `MergeInfo`, and the `Slot` value object.

### Verification
- `:app:compileDebugJavaWithJavac` — ✅ BUILD SUCCESSFUL.
- Persistence: `manual-merges.json` keys and the gallery JSON parse are
  byte-identical (Builder/`save()` keys unchanged).
- On-device — ⬜ pending, and **more important here than B/C**: auto-merge detection,
  manual merge create, photo-detail order/algorithm change + persist, and a
  manual-merges round-trip across an app restart.

---

## Next (when resumed)
Phase F — move RGB merge into `gbcam-core` (largest; spans Rust).

---

## Phase E — `Selection`

**Commit:** `3e96cf4`

**Goal:** remove the last mutable field from `GalleryPhoto` by moving the
working selection into its own value object.

### Changes
- New `Selection` value object: album photos are selected by physical slot, merged
  photos by output path, with a fallback transient key for non-album/non-merge
  photos. It owns select-all, toggle, retention against a new photo list, and the
  selection count projections.
- `GalleryPhoto`: removed mutable `selected`; photos are now immutable read-model
  values.
- `GalleryState`: now carries `Selection`; the existing `selected*Count()` APIs
  delegate to it, and `copySelectionFrom` is now a non-mutating state copy.
- Readers updated across `MainScreen`, `GalleryController`, `SlotSet`, and
  `PhotoExporter`. Single-photo sharing no longer temporarily mutates every photo;
  it passes a one-photo `Selection` to the exporter.

### Verification
- `:app:compileDebugJavaWithJavac` — ✅ BUILD SUCCESSFUL.
- On-device — ⬜ pending: selection mode/toggle/select-all/deselect-all, save/share
  selected, delete/recover selected, move selected first, manual merge create, and
  selection retention across palette recolor/manual merge update.

---

## Slot value object

**Commit:** `1442c74`

**Goal:** finish the C/D-deferred slot typing by moving the `-1` sentinel out of
normal gallery readers.

### Changes
- New nullable `Slot` value object on `GalleryPhoto`; `null` means the photo is
  not album-backed. The builder still accepts the JSON `physicalSlot` int, so the
  FFI/gallery wire shape is unchanged.
- `SlotSet` now carries `Slot` values internally and only converts to zero-indexed
  CSV at the FFI boundary.
- Presentation, selection identity, locally-deleted-slot keys, merge file naming,
  and merge detection now read through `Slot` / `isAlbumBacked()` instead of direct
  `physicalSlot >= 0` checks or `+ 1` arithmetic.

### Verification
- `:app:compileDebugJavaWithJavac` — ✅ BUILD SUCCESSFUL.
- On-device — ⬜ pending: same slot-sensitive pass as Phase C/E (delete, recover,
  move selected first, manual merge filenames/source labels, detail slot labels).
