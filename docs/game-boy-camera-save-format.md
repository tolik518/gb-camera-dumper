# Game Boy Camera Save Format

Source: Raphael Boichot, [Inject pictures in your Game Boy Camera saves](https://github.com/Raphael-Boichot/Inject-pictures-in-your-Game-Boy-Camera-saves).

This document summarizes the known 128 KiB SRAM save layout for the retail Game Boy Camera / Pocket Camera, plus the known differences for the Hello Kitty Pocket Camera prototype ROM and the Debagame Tester - Second Impact prototype.

All offsets are absolute offsets into the 128 KiB save file, with `0x00000` as the first byte. The Game Boy CPU only maps one 8 KiB SRAM bank at `0xA000-0xBFFF` at a time; writing `0x00-0x0F` to mapper register `0x4000` selects SRAM bank 0-15. Writing `0x10` selects the camera I/O register area instead of SRAM.

## Global Layout

| Offset range | Size | Meaning |
| --- | ---: | --- |
| `0x00000-0x00FFF` | `0x1000` | Last image seen by the sensor, also used as a camera exchange buffer. Stored as a 128x128 image, 256 tiles. When saving is active, the camera keeps `0x00100-0x00EFF`, corresponding to 128x112 pixels. |
| `0x01000-0x010D8` | `0x0D9` | Animation, Trippy-H sound, minigame, image counter, print intensity, and checksum block. |
| `0x010D9-0x011B1` | `0x0D9` | Echo of `0x01000-0x010D8`. |
| `0x011B2-0x011D6` | `0x025` | Image state vector and checksum. |
| `0x011D7-0x011FB` | `0x025` | Echo of `0x011B2-0x011D6`. |
| `0x011FC-0x01FFB` | `0xE00` | Game Face image, 128x112 pixels. Not erased by START+SELECT boot reset. |
| `0x01FFC-0x01FFF` | `0x004` | Possible camera tag. `00 56 56 53` unlocks CoroCoro features in Pocket Camera. Not erased by START+SELECT boot reset. |
| `0x02000-0x1FFFF` | `0x1E000` | Thirty 4 KiB image slots. Slot 1 starts at `0x02000`; slot 30 starts at `0x1F000`. |

## Image Buffer

`0x00000-0x00FFF` stores the last sensor frame or exchange-buffer image.

| Offset range | Meaning |
| --- | --- |
| `0x00000-0x00FFF` | 128x128 tile image, 256 Game Boy 2bpp tiles. |
| `0x00100-0x00EFF` | 128x112 region retained by the camera when save data is active. |

The Mitsubishi M64282FP sensor's effective visual resolution is reported as 128x123. The lower 5 lines return saturation-voltage data rather than normal image data.

## Animation, Sound, Minigame, and Counter Block

Primary block: `0x01000-0x010D8`.

Echo block: `0x010D9-0x011B1`.

| Offset range | Meaning |
| --- | --- |
| `0x01000-0x0102E` | Animation slots 1-47. Values index image numbers 0-29; the most significant bit selects album B. |
| `0x0102F` | Animation loop flag. |
| `0x01030-0x0105E` | Animation loop data. Loop start is `0x80 + loop_time`; loop end is `0x40 + loop_time`; loop time range is `0x02-0x32`. |
| `0x0105F` | Animation speed. |
| `0x01060` | Animation border. |
| `0x01061` | SOUND I control: bits 7-6 unknown, bits 5-4 duty length, bits 3-0 gate. |
| `0x01062` | SOUND I envelope: bit 7 up/down, bits 6-4 time, bits 3-0 gain. |
| `0x01063` | SOUND I modulation: bit 7 square/sine, bits 6-0 depth. |
| `0x01064` | SOUND I modulation frequency: bit 7 unknown, bits 6-0 frequency. |
| `0x01065-0x01074` | SOUND I notes, range `0x01-0x25`. |
| `0x01075-0x01078` | SOUND I stereo settings for 16 notes, 2 bits per note. Default `0x55`, centered. |
| `0x01079-0x01088` | SOUND II wave envelope, 32 values packed into 16 bytes, 4 bits per level. |
| `0x01089` | SOUND II control: bits 7-6 unknown, bits 5-4 wave pattern, bits 3-0 envelope gain. |
| `0x0108A` | SOUND II modulation: bit 7 square/sine, bits 6-0 depth. |
| `0x0108B` | SOUND II modulation frequency: bit 7 unknown, bits 6-0 frequency. |
| `0x0108C-0x0109B` | SOUND II notes, range `0x01-0x25`. |
| `0x0109C-0x0109F` | SOUND II stereo settings for 16 notes, 2 bits per note. Default `0x55`, centered. |
| `0x010A0` | Loop count: bits 7-4 SOUND I, bits 3-0 SOUND II. |
| `0x010A1` | NOISE envelope: bit 7 up/down, bits 6-4 unknown, bits 3-0 gain. |
| `0x010A2` | NOISE control: bits 7-4 gate, bits 3-0 loop count. |
| `0x010A3-0x010B2` | NOISE notes, range `0x01-0x25`. |
| `0x010B3-0x010B6` | NOISE stereo settings for 16 notes, 2 bits per note. Default `0x55`, centered. |
| `0x010B7-0x010B8` | Unknown, apparently unused. |
| `0x010B9` | Tempo. |
| `0x010BA` | Partition saved flag: `0x01` if any partition is saved, otherwise `0x00`. |
| `0x010BB-0x010BC` | Images taken counter, 2x2 reversed digits. |
| `0x010BD-0x010BE` | Images erased counter, 2x2 reversed digits. |
| `0x010BF-0x010C0` | Images transferred counter, 2x2 reversed digits. |
| `0x010C1-0x010C2` | Images printed counter, 2x2 reversed digits. |
| `0x010C3-0x010C4` | Pictures received by male/female users, 2x2 digits. |
| `0x010C5-0x010C6` | Space Fever II score, 4x2 reversed digits. |
| `0x010C7-0x010C8` | Not documented in the source map. |
| `0x010C9-0x010CA` | Ball score, 2x2 reversed digits. |
| `0x010CB-0x010CC` | Run! Run! Run! score, 2x2 reversed digits. The visible score is `99 - stored_value`. |
| `0x010CD-0x010CF` | Unknown, apparently never used. |
| `0x010D0` | Printing intensity. `0x00` minimum, `0x40` typical, `0x7F` maximum. |
| `0x010D1` | Unknown. |
| `0x010D2-0x010D6` | ASCII `Magic`. |
| `0x010D7-0x010D8` | Two-byte checksum for this protected block. |

The checksum range is described as starting at `0x01000` and ending at the byte before the checksum field. The upstream document names `0x010D6` as the end byte for the `Magic` word.

## Image State Vector

Primary vector: `0x011B2-0x011D6`.

Echo vector: `0x011D7-0x011FB`.

| Offset range | Meaning |
| --- | --- |
| `0x011B2-0x011CF` | Thirty slot entries. Each byte maps a physical image slot to the displayed image number minus one. `0x00-0x1D` are valid displayed numbers. `0xFF` marks an erased or never-used slot. |
| `0x011D0-0x011D4` | ASCII `Magic`. |
| `0x011D5-0x011D6` | Two-byte checksum for the state vector. |

The display number is not the same thing as the physical slot number. Deleting a picture writes `0xFF` into the matching state-vector entry, then the camera dynamically renumbers the remaining visible pictures. The underlying tile data remains in its physical slot until another picture overwrites it.

When taking a new picture, the camera chooses a slot marked unused in the state vector, preferring the lowest physical slot address.

Known all-slots-active vector:

```text
0x011B2-0x011CF = 00 01 02 ... 1C 1D
0x011D5-0x011D6 = E2 14
```

The same state-vector data and checksum are echoed at `0x011D7-0x011FB`.

## Checksum Format

Protected blocks use:

| Field | Meaning |
| --- | --- |
| Low-address checksum byte | 8-bit sum. |
| High-address checksum byte | 8-bit XOR. |
| Required marker | ASCII `Magic` immediately before the checksum in each protected block. |
| Seed | ASCII `Magic`, followed by `0x2F 0x15`, when calculating from an all-zero data range. |

When changing one byte inside a protected region:

```text
new_sum = old_sum + (new_value - old_value)   mod 256
new_xor = old_xor XOR old_value XOR new_value
```

The `Magic` marker is mandatory. A correct checksum without the marker still causes the camera to treat the save as invalid. If a protected region and its checksum disagree, the camera can reset save data to defaults at boot.

Primary copies appear to take priority over echo copies, but echo priority was not exhaustively documented by the source.

If `Magic` is included in a checksum calculation but replaced with five `0x00` bytes, the resulting checksum is `0x4E 0x54`.

## Image Slots

There are 30 image slots. Each slot is 4 KiB.

| Slot | Start | End |
| ---: | --- | --- |
| 1 | `0x02000` | `0x02FFF` |
| 2 | `0x03000` | `0x03FFF` |
| ... | ... | ... |
| 30 | `0x1F000` | `0x1FFFF` |

For slot `n` from 1 to 30:

```text
slot_base = 0x02000 + ((n - 1) * 0x1000)
```

For historical notation in the source, image slots occupy `0xXX000-0xXXFFF`, where `XX` ranges from `0x02` through `0x1F`.

### Slot Layout

Offsets below are relative to a slot base. The absolute addresses shown are for slot 1.

| Relative range | Slot 1 range | Size | Meaning |
| --- | --- | ---: | --- |
| `+0x000-+0xDFF` | `0x02000-0x02DFF` | `0xE00` | Main image tiles, 128x112 pixels, 224 Game Boy 2bpp tiles. |
| `+0xE00-+0xEFF` | `0x02E00-0x02EFF` | `0x100` | Thumbnail, 32x32 pixels, 16 Game Boy 2bpp tiles. |
| `+0xF00-+0xFFF` | `0x02F00-0x02FFF` | `0x100` | Image tag / metadata. |

Main image and thumbnail tile data are not protected by the checksum system. They can be replaced byte-for-byte in an active slot without updating protected metadata, provided the state vector already marks the slot as active.

Tile data is stored in normal Game Boy 2bpp tile format, tile by tile, in reading order.

### Image Metadata Layout

Offsets below are relative to a slot base. The absolute addresses shown are for slot 1.

| Relative range | Slot 1 range | Meaning |
| --- | --- | --- |
| `+0xF00-+0xF5B` | `0x02F00-0x02F5B` | Image owner data, comments, hotspots, border, and checksum. |
| `+0xF5C-+0xFB7` | `0x02F5C-0x02FB7` | Echo of image owner data. |
| `+0xFB8-+0xFD0` | `0x02FB8-0x02FD0` | Camera owner data. Documented as meaningful below slot 1; in other slots it is usually `0xAA`. |
| `+0xFD1-+0xFE9` | `0x02FD1-0x02FE9` | Echo of camera owner data. Documented as meaningful below slot 1; in other slots it is usually `0xAA`. |
| `+0xFEA-+0xFFF` | `0x02FEA-0x02FFF` | End-of-slot padding / unused bytes / calibration bytes in specific slots. |

#### Image Owner Data

| Relative range | Slot 1 range | Meaning |
| --- | --- | --- |
| `+0xF00-+0xF03` | `0x02F00-0x02F03` | User ID, 4 bytes. The source describes this as `11 + series of two digits among 8 in reading order`. |
| `+0xF04-+0xF0C` | `0x02F04-0x02F0C` | Username. Character encoding uses the first-character-stamp tileset; `0x56` is `A`, `0xC8` is `@`. |
| `+0xF0D` | `0x02F0D` | Gender and blood type. Gender: `0x00` none, `0x01` male, `0x02` female. Japanese blood type adds `0x04` A, `0x08` B, `0x0C` O, or `0x10` AB. |
| `+0xF0E-+0xF11` | `0x02F0E-0x02F11` | Birthdate. Year uses 2x2 bytes, day uses 2 bytes, month uses 2 bytes; each digit byte is stored as digit + 11. |
| `+0xF12-+0xF14` | `0x02F12-0x02F14` | Unknown. |
| `+0xF15-+0xF2F` | `0x02F15-0x02F2F` | Comment text, same character encoding as username. |
| `+0xF30-+0xF32` | `0x02F30-0x02F32` | `0x00`. |
| `+0xF33` | `0x02F33` | Original/copy flag. `0x00` original, `0x01` copy. |
| `+0xF34-+0xF35` | `0x02F34-0x02F35` | Probable image-data checksum used to detect image modification and trigger the save menu. Identical image copies have matching values. |
| `+0xF36-+0xF3A` | `0x02F36-0x02F3A` | Five hotspot enable flags. `0x01` enabled, `0x00` disabled. |
| `+0xF3B-+0xF3F` | `0x02F3B-0x02F3F` | Hotspot X positions, range `0x00-0x0E`. |
| `+0xF40-+0xF44` | `0x02F40-0x02F44` | Hotspot Y positions, range `0x00-0x0C`. |
| `+0xF45-+0xF49` | `0x02F45-0x02F49` | Hotspot sound/music IDs, range `0x00-0x3F`; `0xFF` means off. |
| `+0xF4A-+0xF4E` | `0x02F4A-0x02F4E` | Hotspot visual effect IDs, range `0x00-0x06`; `0xFF` means off. |
| `+0xF4F-+0xF53` | `0x02F4F-0x02F53` | Hotspot jump target image numbers, range `0x00-0x1D`; `0xFF` means off. |
| `+0xF54` | `0x02F54` | Border number associated with the image. |
| `+0xF55-+0xF59` | `0x02F55-0x02F59` | ASCII `Magic`. |
| `+0xF5A-+0xF5B` | `0x02F5A-0x02F5B` | Two-byte checksum for `+0xF00-+0xF59`. |

The echo of this range is at `+0xF5C-+0xFB7`.

#### Camera Owner Data

| Relative range | Slot 1 range | Meaning |
| --- | --- | --- |
| `+0xFB8-+0xFBB` | `0x02FB8-0x02FBB` | User ID. |
| `+0xFBC-+0xFC4` | `0x02FBC-0x02FC4` | Username, same encoding as image owner username. |
| `+0xFC5` | `0x02FC5` | Gender and blood type, same encoding as image owner data. |
| `+0xFC6-+0xFC9` | `0x02FC6-0x02FC9` | Birthdate, same encoding as image owner data. |
| `+0xFCA-+0xFCE` | `0x02FCA-0x02FCE` | ASCII `Magic`. |
| `+0xFCF-+0xFD0` | `0x02FCF-0x02FD0` | Two-byte checksum for `+0xFB8-+0xFCE`. |

The echo of this range is at `+0xFD1-+0xFE9`.

#### End-of-Slot Bytes

| Relative range | Slot 1 range | Meaning |
| --- | --- | --- |
| `+0xFEA-+0xFEF` | `0x02FEA-0x02FEF` | Usually repeated `0xAA`. |
| `+0xFF0-+0xFF1` | `0x02FF0-0x02FF1` | Usually repeated `0xAA`, but not always. |
| `+0xFF2-+0xFFF` | `0x02FF2-0x02FFF` | Usually repeated `0xAA`, except calibration bytes in specific slots. |
| `+0xFFF` | `0x02FFF` | Can change with hotspots and events. |

Long `0xAA` regions are understood as remnants of factory SRAM testing. These bytes are not included in camera checksums when unused by the camera.

## Calibration Bytes

The retail camera stores sensor calibration data in two places:

| Offset range | Meaning |
| --- | --- |
| `0x04FF2-0x04FFF` | Calibration vector inside image slot 5. |
| `0x11FF2-0x11FFF` | Echo of the calibration vector inside image slot 18. |

To trigger automatic calibration, fill these ranges with `0xAA`, boot the camera in the dark, wait for the calibration sequence to finish, then reboot. The calibration bytes are believed to guide auto-exposure behavior and are known to affect sensor register O / fine voltage tuning.

A full SRAM filled with `0xAA` can also enter the factory calibration flow. The camera displays a store/wait sequence and plays a completion jingle.

## Injecting Image Data

For an already-active image slot, only the tile data must be replaced:

| Slot-relative range | Meaning |
| --- | --- |
| `+0x000-+0xDFF` | Main 128x112 image. |
| `+0xE00-+0xEFF` | 32x32 thumbnail. |

The metadata range `+0xF00-+0xFFF` should be left untouched unless the related checksums are also updated. The thumbnail can be regenerated by the camera after an image is edited and saved in-camera.

For the Game Face area, use `0x011FC-0x01FFB` for a 128x112 face image. The source also mentions preparing 128x128 images when using slot `-1`, which refers to the sensor buffer at address 0.

## Save Validation Behavior

Retail Game Boy Camera / Pocket Camera saves are protected by multiple checksummed data blocks:

| Protected data | Location |
| --- | --- |
| Minigames, animation, counters, print settings | `0x01000-0x010D8` and echo |
| Image state vector | `0x011B2-0x011D6` and echo |
| Image owner metadata | Each slot's `+0xF00-+0xF5B` and echo |
| Camera owner metadata | Slot metadata `+0xFB8-+0xFD0` and echo, documented mainly for slot 1 |

Raw picture tile data and Game Face tile data are not protected by these checksums.

## Hello Kitty Pocket Camera Save Differences

The Hello Kitty Pocket Camera prototype save is mostly compatible with the retail Game Boy Camera layout, but `0x01000-0x011B1` is different and is not protected by the same checksum scheme.

| Offset range | Meaning |
| --- | --- |
| `0x00000-0x00FFF` | Same as retail Game Boy Camera. |
| `0x01000-0x011B1` | Hello Kitty game save data, not protected. |
| `0x011B2-0x011D6` | Same state vector as retail Game Boy Camera, protected by checksum. |
| `0x011D7-0x011FB` | Echo of the state vector. |
| `0x011FC-0x0187B` | Three animated user-profile photos, each 40x56 pixels / 5x7 tiles, stored consecutively. |
| `0x0187C-0x01FFF` | `0x00`. |
| `0x02000-0x1FFFF` | Same image-slot layout as retail Game Boy Camera. |

Hello Kitty game save sublayout:

| Offset range | Meaning |
| --- | --- |
| `0x01000-0x01001` | Images taken counter, 2x2 reversed digits. |
| `0x01002-0x01003` | Images erased counter, 2x2 reversed digits. |
| `0x01004-0x01005` | Images transferred counter, 2x2 reversed digits. |
| `0x01006-0x01007` | Images printed counter, 2x2 reversed digits. |
| `0x01008-0x01009` | Pictures received by male/female users, 2x2 digits. |
| `0x0100A-0x0100C` | Kitts counter, 3x2 reversed digits. |
| `0x0100D-0x01011` | Unknown. |
| `0x01012-0x01016` | ASCII `Magic`, but with no checksum after it. |
| `0x01017-0x011B1` | `0x00`. |

Because Hello Kitty writes game data into the region used by retail animation/minigame data, switching from a Hello Kitty ROM back to a retail ROM can make retail records fail validation if the Hello Kitty data changed. Images themselves remain in the same slots and are conserved.

## Debagame Tester - Second Impact Save Differences

The known Debagame Tester prototype version, 10.24, has a simpler save behavior. Images are stored at the same physical offsets as retail Game Boy Camera saves, but the prototype does not maintain the same state vector or data protection.

| Offset range | Meaning |
| --- | --- |
| `0x00000-0x00FFF` | Same exchange / image-buffer data as retail Game Boy Camera. |
| `0x01000-0x01FFF` | Remnants of RAM read/write and aging tests. |
| `0x02000-0x02DFF` | First image slot main image tiles, 128x112 pixels, 224 tiles. |
| `0x02E00-0x02FFF` | First image slot metadata/tag area, mostly empty; `0x02FE8-0x02FFF` contains data of unknown purpose. |
| `0x02000-0x1FFFF` | Overall image storage at the same offsets as retail Game Boy Camera. |

Observed behavior:

| Behavior | Notes |
| --- | --- |
| RAM writes at boot | The prototype does not appear to write RAM at boot. |
| Gallery access | GALLERY mode can access any memory slot. Occupied slots are not tracked in SRAM with a retail-style state vector. |
| Protection | No known retail-style data protection or state-vector checksum. |
| Aging test | Requires specific SRAM patterns. |
| MOVIE function | Can try sensor register configurations and dithering patterns unavailable in the retail ROM. |
| COM function | Synchronizes two Debagame cartridges over serial and increments an on-screen counter; it does not send normal Game Boy Printer packets. |
| Metadata | Image metadata does not appear to store sensor registers at first glance; some unknown control sums or comments may exist. |

## Notes for Implementations

- Treat all offsets as absolute SRAM offsets.
- Use the state vector to decide which physical image slots are active in retail saves.
- Do not assume displayed image number equals physical slot number.
- Preserve unknown bytes unless deliberately rebuilding the save.
- When modifying any protected region, update both checksum bytes and usually the echo copy.
- Raw main image tiles, thumbnails, and Game Face data can be modified without the retail checksum system, but slot activation still depends on the protected state vector.
- Retail Game Boy Camera and Pocket Camera saves are considered intercompatible by the source.
- Prototype ROMs are not fully compatible with all retail validation behavior, even when image data is stored at the same offsets.
