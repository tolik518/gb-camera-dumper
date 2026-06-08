# Cartridge Reader Support

GBxCAM Viewer currently supports the GBxCart RW CH340 path. GBxCart RW 1.4 is
the known-good target. GBxCart RW 1.3 is detected separately and routed through
the same command implementation so legacy-specific fixes can be isolated.

The Android device filter and native USB implementation are intentionally narrow:

- Android discovers WCH CH340 devices with VID `0x1A86` and PID `0x7523`,
  which covers GBxCart RW and GBFlash-family serial devices.
- Android also recognizes Joey Jr's STM virtual COM port VID `0x0483` and PID
  `0x5740`, but currently reports it as detected but unsupported.
- Native USB setup initializes CH340 directly and assumes bulk endpoints
  `0x02` and `0x82`.
- The command flow is the GBxCart RW DMG path used for Game Boy Camera SRAM
  reads and writes.

Other cartridge readers are not supported by the current native implementation.

## FlashGBX Reference

FlashGBX models supported hardware as separate reader modules:

- `hw_GBxCartRW.py` detects insideGadgets GBxCart RW devices.
- `hw_GBFlash.py` detects Geeksimon GBFlash devices.
- `hw_JoeyJr.py` detects BennVenn Joey Jr devices.

Each module probes matching USB serial ports, validates firmware identity, and
then exposes a common device API inherited from `LK_Device.py`. Higher-level ROM
and save operations call that common API instead of hard-coding one reader.

Important differences from the current GBxCAM Viewer implementation:

- GBxCart RW and GBFlash can both appear as CH340 devices, so VID/PID alone is
  not enough to identify the reader.
- Joey Jr uses a different VID/PID (`0x0483:0x5740`) and a different initial
  probe before using the shared LK command set.
- GBxCart RW 1.3 is treated as a legacy GBxCart path. FlashGBX avoids waiting
  for newer acknowledged-command responses when firmware support is older.
- Buffer sizes and feature flags are reader/firmware dependent.

## Local Implementation Direction

The local code has started growing a reader-driver boundary:

- `gbcam-usb::CartridgeReader` is the session wrapper used by native callers.
- `gbcam-usb::CartridgeReaderInfo` describes the connected hardware reader.
- The live hardware variants are currently `GbxCartRw13` and `GbxCartRw14`.
  Both forward to the existing `UsbDev` implementation for now.
- Android-side discovery can identify known reader USB IDs before native
  protocol probing exists. Unsupported readers are reported instead of being
  treated as missing GBxCart devices.

The next implementation steps are:

1. Open the device and probe firmware identity in native code.
2. Select a concrete reader implementation:
   - GBxCart RW 1.4 driver, using the current code.
   - GBxCart RW 1.3 legacy behavior, with firmware-dependent command waits and
     buffer limits if hardware testing shows the shared path is insufficient.
   - GBFlash driver, if its Game Boy Camera SRAM path works with the shared LK
     commands.
   - Joey Jr driver, with its separate USB identity/probe path.
3. Expose only the Game Boy Camera operations to the rest of the app:
   read header, dump save, write SRAM windows, and finish/cleanup.

Until that boundary exists, adding another reader directly to `UsbDev` risks
mixing transport setup, firmware probing, and camera SRAM logic in one module.
