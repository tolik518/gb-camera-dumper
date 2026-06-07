# Domain refactoring plan

A follow-up to [android-refactoring.md](android-refactoring.md). That refactor
split the god class by **technical layer** (ui / control / data / usb / io). It
never asked *what the domain is*. This plan re-centers the Java side on the
**business** — the ubiquitous language, the bounded contexts, and the value
objects — without re-litigating the file split that already landed.

As before: **structural only, no behavior change.** Every phase compiles
(`:app:compileDebugJavaWithJavac`) and `just android-apk-install`s clean, and is
its own commit.

## TL;DR

Three findings drive everything below.

1. **The real domain model already exists — in Rust.** `gbcam-core` has
   `GbcamSave`, `Photo`, `StateVectorReport`, `SaveValidationReport`, `PaletteId`,
   `photo_slot_base`, and typed errors that *are* the album invariants
   (`DuplicatePhysicalSlot`, `InvalidDisplayPosition`, `NoFreeDisplayPosition`).
   This is the **core domain** and it is healthy. We do not move it.

2. **The Java side is an anemic, stringly-typed read model.** `GalleryState` /
   `GalleryPhoto` are data bags; business values are primitives —
   `int paletteIndex` threaded through **9 files / 107 references**, album slots as
   **CSV strings**, merge order/algorithm/kind as bare `String`s validated against
   `String[]` tables in three different classes. Rules live wherever the data is
   read, not on the data.

3. **The FFI is an unmanaged context boundary.** Core internals cross as ints,
   CSVs, and JSON; the Java read model is coupled to that wire shape. It should be
   a deliberate, translated boundary (a Published Language with a thin
   Anti-Corruption Layer), not a leak.

**The one principle everything else follows from:** *the domain model lives in
Rust; Java is a **read model** + **working state** (selection, chosen merge
variant) + presentation.* Once that's accepted, the Java work is tactical, not
architectural.

The work, in order of value: **(A–D) introduce value objects** to kill primitive
obsession and pull rules onto the data; **(E) extract `Selection`** so the read
model is immutable; **(F) harden the FFI as a context boundary** (folds in the
merge→core and palette-PNG items already scoped in `android-refactoring.md` §7).
Trivial value objects and a read-model rename are *opportunistic*, not phases.

---

## 1. Ubiquitous language

The vocabulary the app — and a Game Boy Camera user — actually speaks. Today these
concepts exist but are spelled as primitives; the plan gives each a home.

| Term | Meaning | Where it lives today |
| --- | --- | --- |
| **Save / SRAM** | The 128 KiB cartridge memory (`SAVE_SIZE`). | `gbcam-core::GbcamSave` (good); raw `savePath` string in Java. |
| **Album** | The ordered set of up to 30 photo slots + its state-vector/order table. | Rust core functions on `&[u8]`; **no Java concept** — a flat `List<GalleryPhoto>`. |
| **Photo** | One 128×112 indexed-color image. | `core::Photo` / Java `GalleryPhoto`. |
| **Slot** (physical) | Storage position 0–29; identity of a photo in the album. | `int physicalSlot`, `-1` = "not album-backed". |
| **Display index** | Where a photo appears in album order (≠ slot). | `int displayIndex`, `-1` sentinel. |
| **Order / State vector** | The album ordering, with primary+echo copies, magic, checksum. | `core::StateVectorReport` (good); invisible in Java. |
| **Palette** | Mapping of the 4 grayscale indices → RGB; named presets. | `core::PaletteId`; Java: `(int paletteIndex, String paletteName)` pair. |
| **Merge (RGB / CRGB)** | 3 or 4 mono photos aligned + composited into one color photo. | `RgbMergeDetector` (Java). |
| **Auto vs manual merge** | Auto-detected merge groups vs user-picked ones. | `boolean manualMerge` + `manual-merges.json`. |
| **Merge order** | Channel sequence: `RGB`…`BGR`, `CRGB`…`BGRC`. | `String`, validated vs `RGB3_ORDERS`/`RGB4_ORDERS`. |
| **Merge algorithm** | Compositing method (basic, norm, adaptive…). | `String` id vs `ALGORITHM_IDS`. |
| **Backup** | A `.sav` dump on disk in the dumps dir. | `File` + a `backup-palette:` pref key. |
| **Locally-deleted slot** | Marked deleted in-app, not yet written to cart. | `Set<String>` of slot numbers in prefs. |
| **Validation** | Save-integrity findings (errors/warnings). | `core::SaveValidationReport`; Java: two `int`s. |
| **Cartridge / GBxCart** | The inserted cart and the USB reader. | `gbcam-usb`, `UsbDeviceController`, `GbxCartDevices`. |

> Naming rule going forward: code should use these words. `slots` not `csv`,
> `Palette` not `paletteIndex`, `MergeOrder` not `order` (String).

---

## 2. Bounded contexts & the context map

Six subdomains. Classifying them (core / supporting / generic) tells us where to
spend modelling effort — and where *not* to.

| Context | Kind | Owns | Lives in |
| --- | --- | --- | --- |
| **Album Editing** | **Core** | Parse SRAM → album; delete/recover/reorder with checksum/echo/validation integrity. The reason the app exists. | `gbcam-core` |
| **RGB Merge** | Supporting (the differentiator) | Detect mergeable groups; align (pHash/DCT/NCC); composite channels. | `RgbMergeDetector` (Java) → move to core: Phase F / `android-refactoring.md` §7.1 |
| **Imaging / Palette** | Supporting | Indexed→RGB, palette presets, **instant in-memory recolor**. | core + `PhotoRenderer`/`PaletteCatalog` |
| **Backup / Archive** | Supporting | `.sav` import/export, dumps enumeration, previews. | `BackupRepository` |
| **Cartridge Transport** | Generic | CH340/GBxCart protocol over a raw fd; discovery; permission. | `gbcam-usb`, `UsbDeviceController` |
| **Settings** | Generic | Preferences persistence. | `AppSettings` |

Plus the **Gallery Presentation** shell (Activity, screen, dialogs, controller) —
not a domain, it *composes* the above.

### The map (who talks to whom, and how)

```text
                         ┌─────────────────────────────┐
   Cartridge Transport   │     Album Editing (CORE)     │   the heart;
   (gbcam-usb)  ───fd──▶ │  GbcamSave · Album · Photo   │   owns all
                         │  StateVector · Validation    │   integrity
                         └──────────────┬──────────────┘
                                        │  gbcam-ffi  ← ★ the boundary to fix
            ┌───────────────────────────┴───────────────────────────┐
            │              Gallery Presentation (Java)               │
            │  GalleryController · MainScreen · dialogs · pipeline   │
            └───┬───────────────┬───────────────┬───────────────┬───┘
            Imaging         RGB Merge          Backup          Settings
```

Two relationships need a decision:

- **Java ⇄ gbcam-ffi ⇄ core** is today an accidental *shared kernel*: the Java
  read model mirrors the wire JSON field-for-field, and operation params (slots,
  palette) cross as the core's internal representation (CSV, raw index). Treat the
  FFI as a **Published Language** (a small, named DTO/JSON contract) with a thin
  **Anti-Corruption Layer** on the Java side — `GalleryState.fromJson` plus the
  value-object constructors below — so the rest of Java never touches the wire
  shape. This is mostly a *framing* change that the value objects implement.
- **RGB Merge** physically sits in the UI app but is a **Customer/Supplier** of
  the Album core (it consumes album photos' pixels). `android-refactoring.md` §7.1
  already argues it should move into `gbcam-core`; in context terms that's
  realigning the code location with the context boundary. Keep merge-*variant
  selection* (which order/algorithm the user picked, `manual-merges.json`) in the
  Presentation context — that's UI state, not the algorithm.

### What this map actually buys (so it isn't decoration)

The map does three concrete jobs, even though Phases A–E never cross a boundary:

1. **It declares what's off-limits.** Album Editing (core) and Cartridge
   Transport are healthy and owned elsewhere — the Java refactor must not grow a
   parallel album model or re-validate slots. This is *why* §6 says "don't build a
   Java Album aggregate."
2. **It scopes the value-object work.** Every VO in §3 belongs to a context;
   almost all land in the **Presentation read model**, which is the only context
   we're reshaping. The map is what tells you that.
3. **It frames Phase F.** The one real boundary change (merge → core, FFI as
   Published Language) is a context-map move, not a tactical one.

---

## 3. Value objects (the headline)

The fix for primitive obsession. Each pulls a rule that's currently scattered
onto a type that owns it, and makes illegal states harder to represent. All are
small, immutable, Java-side, and behavior-preserving. They live in the **flat
`fyi.r0.gbxcam` package** like everything else — a `model.` subpackage is the only
folder split that would add value, and only if the count grows past Tier 1.

**Mirror the Rust precedent, don't out-model it.** Core's `PaletteId` is a *thin
id* (`{ index }`) with `colors()`/`label()` as **derived lookups** over
`PALETTE_PRESETS`. The Java VOs should match: identifiers stay thin; bulky/derived
data (colors) is looked up via the catalog, not carried in the value.

### Tier 1 — do these (high ROI, they delete real duplication)

| Concept | Today | Value object | Absorbs / replaces |
| --- | --- | --- | --- |
| **Palette** | `(int paletteIndex, String paletteName)` pair travelling together; 107 refs / 9 files | `Palette { int index; String name }` — colors via `PaletteCatalog.colorsFor(palette)` | the index↔name pairing (`withPalette(int,String)`, `fromJson`, logging, folder names), index clamping; **not** colors (stay a derived lookup, like `PaletteId.colors()`) |
| **Merge order** | `String` vs `RGB3_ORDERS`/`RGB4_ORDERS` | `MergeOrder` (channel list; `hasClear()`, `size()`) | `validChoice`, the two `String[]` tables, ad-hoc `mergedKind`/`order` string-poking in `PhotoDetailDialog`/`MainScreen` |
| **Merge algorithm** | `String` id vs `ALGORITHM_IDS` | `MergeAlgorithm` (id, label, shortLabel, `compatibleWith(hasClear)`) | `ALGORITHM_IDS/_LABELS/_SHORT_LABELS`, `resolveAlgorithm`, `compatibleAlgorithm*`, `isAlgorithmId` |
| **Merge identity** | `String "kind:start:count"` | `MergeIdentity` *(Phase D, on `MergeInfo`)* | the static `GalleryPhoto.mergeIdentity(...)`, the `merge-algo-override:` pref-key building |
| **Merge descriptor** | 6 fields on `GalleryPhoto` (`mergedRgb`, `mergedKind`, `mergedSourceCount`, `mergedSourceStartDisplayIndex`, `mergedAlgorithm`, `manualMerge`) | `MergeInfo` value object; photo holds `MergeInfo merge` (null = ordinary photo) | collapses 6 fields → 1; `isManualMerge`/`isMergeableSource`/`mergeIdentity` move onto it; kills `mergedRgb` as a discriminator (it's just `merge != null`) |
| **Physical slot** | `int physicalSlot`, `-1` sentinel, scattered `>= 0` checks | `Slot` (0–29); absence stays explicit (`null`/`Optional`) | `isAlbumBacked()`, the `photo_slot_base` bound, `InvalidPhysicalSlot` meaning |
| **Slot set (op param)** | three CSV builders on `GalleryState` | `SlotSet` with `toCsv()` | `selectedPhysicalSlotsCsv`, `activePhysicalSlotsCsv`, `selectedActiveFirstPhysicalSlotsCsv` — the "op params, not gallery state" item. Its CSV is the input the **FFI's** `parse_physical_slots` (gbcam-ffi:665) already expects; wire format unchanged |

### Tier 2 — only if they earn it (small, do opportunistically)

| Concept | Today | Value object | Note |
| --- | --- | --- | --- |
| **Share size** | `int shareMultiplier` (4) | `ShareSize` | trivial; fold in when touching `ShareSizeDialog` |
| **Image size** | `int width, height` | `ImageSize` | only if dimension math spreads |
| **Validation** | `int validationErrors, validationWarnings` | `ValidationSummary` | mirrors core `SaveValidationReport`; thin win |
| **Backup** | `File` + `backup-palette:` pref | `Backup` (file, `Palette`, capturedAt) | useful if `BackupRepository` grows; `backupPaletteKey` = path+mtime+len identity |

Tier 1 maps cleanly onto §2's contexts: `MergeOrder`/`MergeAlgorithm`/
`MergeIdentity`/`MergeInfo` are the **RGB Merge** language, `Palette` is **Imaging**,
`Slot`/`SlotSet` are the **Album** projection the read model exposes. That's the
link between the strategic map and the tactical work.

The two that pay for themselves immediately: **`MergeInfo`** (turns `GalleryPhoto`
from a 20-field bag into photo-identity + an optional merge descriptor) and
**`Palette`** (deletes the single most widespread primitive in the codebase).

Sketch of the `MergeInfo` split:

```java
// before — 6 of GalleryPhoto's 20 fields
boolean mergedRgb; String mergedKind; int mergedSourceCount;
int mergedSourceStartDisplayIndex; String mergedAlgorithm; boolean manualMerge;

// after
final MergeInfo merge;   // null for ordinary photos
boolean isMerge()        { return merge != null; }
boolean isManualMerge()  { return merge != null && merge.manual; }
// MergeInfo owns: kind(MergeOrder), algorithm(MergeAlgorithm),
//                 sourceStartDisplayIndex, sourceCount, manual, identity()
```

---

## 4. Aggregate / read-model split

The most clarifying *conceptual* change — small in code, large in understanding.

- **The Album/Save aggregate is in Rust.** It enforces the invariants
  (unique slots, valid display positions, state-vector echo + checksum). All
  *writes* (delete/recover/reorder) already go through it. Good — leave it.
- **`GalleryState` / `GalleryPhoto` are a read model**, not the aggregate. They're
  a snapshot for display + the user's working selection. Today they're labelled
  "data" and carry methods that read like domain logic (`selectedMergeableCount`,
  CSV builders), which invites treating them as the source of truth. They aren't.
  **Action:** document this (and optionally rename `GalleryState` →
  `GalleryView`), and move the operation-param builders (`*Csv`) onto `SlotSet`
  (Phase C), leaving the read model with pure projections.
- **Selection is not a property of a photo.** `GalleryPhoto.selected` is the one
  mutable field on an otherwise immutable value, copied around via
  `copySelectionFrom`. Model it as a separate `Selection` (a `SlotSet` +
  merged-photo paths) held by the controller. Photos become fully immutable;
  `selected*Count` / `copySelectionFrom` become `Selection` operations.
  **Cost (measured): `.selected` is read/written at 58 sites across 7 files** —
  `MainScreen`, `UiStyle` (tile rendering), `GalleryActions` (button state
  machine), `GalleryState`, `GalleryController`, `PhotoExporter`,
  `GbcamOperationRunner`. This is the **second-largest change in the plan** after
  the merge move, not a cleanup — it's Phase E and it is genuinely optional. Do it
  only if the mutable flag starts causing bugs; the gain is purely "photos are now
  immutable values," which the rest of the plan doesn't depend on.

Net: photo-identity and pixels are immutable value/entity data; *selection* and
*chosen merge variant* are working state owned by the Presentation context; the
*album truth* is owned by core across the FFI.

---

## 5. Phased migration

Safest and most mechanical first; each independently shippable. Stop after any
phase — every one stands alone.

**Dependencies** (only one hard edge):

```text
A ──▶ D        (MergeInfo is built from MergeOrder/MergeAlgorithm)
B    C    E    (independent of each other and of A/D)
            └─▶ F   (do last; spans Rust)
```

So A→D must stay in order; B, C, and E can land in any order or be skipped.

### Phase A — merge-param value objects *(pure Java, no FFI, no UI change)*

`MergeOrder` and `MergeAlgorithm` (`MergeIdentity` is deferred to Phase D, where
it belongs on `MergeInfo` as `identity()` — building it standalone now would be
churn). Move the `RGB*_ORDERS` / `ALGORITHM_IDS` tables, `validChoice`,
`resolveAlgorithm`, `compatibleAlgorithm*`, and label lookups onto them.
`AppSettings`, `RgbMergeDetector`, `SettingsDialog`, `PhotoDetailDialog`, and
`MainScreen` consume them instead of bare strings / parallel arrays. Highest ROI,
lowest risk. *Interim:* `GalleryPhoto` still stores `String mergedKind`/
`mergedAlgorithm`, so a few `String`↔VO conversions remain at its edges until
Phase D removes them.

**Status:** ✅ landed — `MergeAlgorithm` (A1) and `MergeOrder` (A2), each its own
commit, `:app:compileDebugJavaWithJavac` green. On-device smoke test pending.

### Phase B — `Palette` value object

Collapse the `(paletteIndex, paletteName)` pair into `Palette { index, name }`;
`PaletteCatalog` becomes its factory (`palette(index)` / `byName`) and keeps
`colorsFor(palette)`. Replace the 107 raw refs; FFI calls read `palette.index()`
only at the boundary, and **colors stay a catalog lookup** (don't bundle them — see
§3). Mechanical but wide, so its own commit; if one sweep feels too big, add
`Palette` alongside the `int` first and migrate call sites, then delete the `int`.
**Verify the instant palette switch (§7.2 hard constraint) still has zero latency.**

### Phase C — `Slot` + `SlotSet`

Introduce `Slot` (0–29, absence explicit) and `SlotSet.toCsv()`. Move the three
CSV builders off `GalleryState` into `SlotSet`; replace `physicalSlot >= 0`
sentinel checks with the typed value. CSV still crosses the FFI unchanged — only
its *construction* is centralized.

### Phase D — `MergeInfo` + slim `GalleryPhoto`

Fold the 6 merge fields into `MergeInfo merge` (null = ordinary photo); move
`isManualMerge`/`isMergeableSource`/`mergeIdentity` onto it, and introduce
`MergeIdentity` here (the `kind:start:count` key, deferred from Phase A) as
`MergeInfo.identity()`. Update
`fromJson`/builder, `ManualMergeStore`, `RgbMergeDetector` outputs, and the detail
dialog. **Keep the on-disk `manual-merges.json` keys identical** and read legacy
values defensively (invalid `mergedKind`/`mergedAlgorithm` must still parse to a
sane `MergeOrder`/`MergeAlgorithm`), exactly as Phase 0 did for `manualMerge`.

### Phase E — extract `Selection` *(optional; the 58-site change — see §4)*

Pull the mutable `selected` flag off `GalleryPhoto` into a controller-held
`Selection`. Genuinely optional and **the second-largest change in the plan**; do
it only if the mutable flag is causing bugs. Nothing else here depends on it.

### Phase F — FFI as a context boundary *(largest; do last, spans Rust)*

Folds in `android-refactoring.md` §7, reframed as context-map work. Core now owns
RGB composition and auto-merge candidate detection. Android asks core for auto
candidates by default, validates them against the transformed Java read model,
and writes accepted auto/manual merge PNGs through the save-based FFI hook.
Cached album PNGs are palette-independent grayscale artifacts; live palette
rendering remains in Java from `indexedPixels`.

- **F5/F6 done:** core exposes `detect_auto_rgb_merge_candidates` and ports the
  old Java grayscale prep, pHash/dHash, shifted NCC, layout validation,
  reference-channel choice, candidate selection, threshold constants, and
  algorithm override identity resolution. Current tests cover synthetic
  candidates and rejection cases.
- **F7 done:** Android calls
  `NativeGbcam.detectRgbMergesFromSave(...)` first, validates physical slots and
  contiguous display indices in Java, and writes accepted PNGs through core.
- **F8 done:** after a device smoke test loaded the cached save and auto-merged 2
  RGB sets through the native path, the old Java production detector/fallback was
  removed. Java still keeps merge composition for photo-detail previews and the
  manual-merge non-save-backed fallback.
- **F9 done:** `gallery_json` writes grayscale album PNGs. Palette switching does
  not rewrite disk cache and RGB/CRGB merge PNGs remain RGB outputs because they
  are palette-independent merge results.
- **Invariant:** automatic merge remains contiguous by display index. Manual merge
  remains allowed to use arbitrary selected source slots.
- Treat the gallery JSON as a versioned **Published Language**; `fromJson` + the
  value objects above are the **ACL** so nothing else sees the wire shape.

**Not phases (opportunistic):** the Tier 2 value objects (§3), typing
`MergeInfo.kind`/`algorithm` internally as `MergeOrder`/`MergeAlgorithm`, and the
read-model rename (`GalleryState` → `GalleryView`, or just a class-doc note
saying it *is* a read model). Land these whenever you're already editing the
file; never as a standalone churn commit.

Remaining optional polish: add a core preview hook if photo-detail previews need
to match saved output exactly; otherwise keep the Java preview path documented as
UI-only.

---

## 6. What NOT to do (avoid over-engineering)

This is a solo-maintained app; DDD here means *clear language and tight values*,
not enterprise scaffolding.

- **No DI framework, no repository/factory interfaces-for-one-impl.** Plain
  constructor wiring, as today.
- **Don't build an Album aggregate in Java.** Core already is it; a parallel Java
  aggregate would duplicate invariants and risk divergence. Java stays a read
  model + selection.
- **Don't split `gbcam-core` into sub-crates** for "contexts." One core crate is
  the right size; contexts are a *thinking* tool here, not a packaging mandate.
- **Don't touch the instant-palette path** (§7.2 hard constraint) while adding the
  `Palette` value object — recolor must stay in-memory, zero-latency.
- **Keep `manual-merges.json` and pref keys on-disk-compatible** through the value
  object changes (read the old shape; the §0 `manualMerge` fallback precedent).

---

## 7. Risks & verification

- **Breadth over depth.** Phases B and D touch many files. Each is mechanical;
  keep them isolated commits and lean on the compiler. Do any rename as a
  separate "move only" commit (per the prior plan).
- **Persistence compatibility.** `MergeInfo` (D), `Palette` (B), and the `Backup`
  VO (Tier 2) touch `manual-merges.json`, the gallery JSON, and pref keys —
  preserve existing keys and add read-fallbacks, exactly as Phase 0 did for
  `manualMerge`.
- **No layout tests** — verify each phase **on device** with the same loop as
  before: load (cache + camera), palette switch (instant), select →
  merge/delete/recover, manual merge persists across restart, backups, photo
  detail order/algorithm change, import/export.
- Per project convention, run `just android-apk-install` and smoke-test before the
  next phase.
