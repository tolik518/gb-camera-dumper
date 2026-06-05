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
| 1 | Leaf helpers (AppFiles, PaletteCatalog) + dead code | ⬜ pending |
| 2 | UsbDeviceController | ⬜ pending |
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
