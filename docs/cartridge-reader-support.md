# Cartridge Reader Support

GBxCAM Viewer currently supports CH340-based GBxCart RW and GBFlash readers.
GBxCart RW 1.4 is the known-good target. GBxCart RW 1.3 and GBFlash are probed
separately and routed through reader profiles so legacy-specific behavior can be
isolated.

The Android device filter and native USB implementation are intentionally narrow:

- Android discovers WCH CH340 devices with VID `0x1A86` and PID `0x7523`,
  which covers GBxCart RW and GBFlash-family serial devices.
- Android also recognizes Joey Jr's STM virtual COM port VID `0x0483` and PID
  `0x5740`, but currently reports it as detected but unsupported.
- Native USB setup initializes CH340 directly and assumes bulk endpoints
  `0x02` and `0x82`.
- The command flow is the shared LK DMG path used for Game Boy Camera SRAM
  reads and writes.

Non-CH340 cartridge readers are not supported by the current native
implementation.

## FlashGBX Reference

FlashGBX models supported hardware as separate reader modules:

- `hw_GBxCartRW.py` detects insideGadgets GBxCart RW devices.
- `hw_GBFlash.py` detects Geeksimon GBFlash devices.
- `hw_JoeyJr.py` detects BennVenn Joey Jr devices.

Each module probes matching USB serial ports, validates firmware identity, and
then exposes a common device API inherited from `LK_Device.py`. Higher-level ROM
and save operations call that common API instead of hard-coding one reader.

Important differences from the current GBxCAM Viewer implementation:

- GBxCart RW and GBFlash can both appear as CH340 devices, so native firmware
  probing identifies the concrete reader after Android selects the USB device.
- Joey Jr uses a different VID/PID (`0x0483:0x5740`) and a different initial
  probe before using the shared LK command set.
- GBxCart RW 1.3 is treated as a legacy GBxCart path. FlashGBX avoids waiting
  for newer acknowledged-command responses when firmware support is older.
- Buffer sizes and feature flags are reader/firmware dependent.

## Local Implementation Direction

The local code has started growing a reader-driver boundary:

- `gbcam-usb::CartridgeReader` is the session wrapper used by native callers.
- `gbcam-usb::CartridgeReaderInfo` describes the connected hardware reader.
- The live hardware variants are currently `GbFlash`, `GbxCartRw13`, and
  `GbxCartRw14`. They forward to the shared LK `UsbDev` implementation with
  reader-specific profiles for power control and buffer limits.
- Android-side discovery can identify known reader USB IDs before native
  protocol probing exists. Unsupported readers are reported instead of being
  treated as missing GBxCart devices.

The next implementation steps are:

1. Keep GBxCart RW 1.4 behavior stable while testing GBxCart RW 1.3 and
   GBFlash on hardware.
2. Move Android interface and endpoint selection into the native session
   boundary so Joey Jr can use its own USB transport instead of the hard-coded
   CH340 endpoints.
3. Add the Joey Jr firmware handshake before selecting the shared LK command
   implementation.
4. Expose only the Game Boy Camera operations to the rest of the app:
   read header, dump save, write SRAM windows, and finish/cleanup.

Until the transport boundary carries interface and endpoint metadata, Joey Jr
support should remain disabled even though the USB VID/PID is detected.
