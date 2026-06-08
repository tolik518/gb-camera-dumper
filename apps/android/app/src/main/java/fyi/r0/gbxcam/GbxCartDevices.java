package fyi.r0.gbxcam;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.util.Locale;
import java.util.Map;

final class GbxCartDevices {
    private static final int VID_WCH = 0x1A86;
    private static final int PID_CH340 = 0x7523;
    private static final int VID_STM = 0x0483;
    private static final int PID_STM_VCP = 0x5740;

    enum ReaderFamily {
        CH340_GB_READER(
                "GBxCart RW / GBFlash CH340 cartridge reader",
                "Native support currently targets GBxCart RW 1.4 on CH340.",
                true),
        JOEY_JR(
                "Joey Jr",
                "Detected, but native Joey Jr support is not implemented yet.",
                false);

        final String label;
        final String supportNote;
        final boolean canAttemptNativeSession;

        ReaderFamily(String label, String supportNote, boolean canAttemptNativeSession) {
            this.label = label;
            this.supportNote = supportNote;
            this.canAttemptNativeSession = canAttemptNativeSession;
        }
    }

    static final class Candidate {
        final UsbDevice device;
        final ReaderFamily family;

        Candidate(UsbDevice device, ReaderFamily family) {
            this.device = device;
            this.family = family;
        }

        boolean canAttemptNativeSession() {
            return family.canAttemptNativeSession;
        }

        String label() {
            return family.label;
        }

        String supportNote() {
            return family.supportNote;
        }
    }

    private GbxCartDevices() {
    }

    static Candidate find(UsbManager usbManager) {
        if (usbManager == null) {
            return null;
        }
        Candidate fallback = null;
        for (Map.Entry<String, UsbDevice> entry : usbManager.getDeviceList().entrySet()) {
            UsbDevice device = entry.getValue();
            Candidate candidate = candidateFor(device);
            if (candidate == null) {
                continue;
            }
            if (candidate.canAttemptNativeSession()) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    private static Candidate candidateFor(UsbDevice device) {
        int vid = device.getVendorId();
        int pid = device.getProductId();
        if (vid == VID_WCH && pid == PID_CH340) {
            return new Candidate(device, ReaderFamily.CH340_GB_READER);
        }
        if (vid == VID_STM && pid == PID_STM_VCP) {
            return new Candidate(device, ReaderFamily.JOEY_JR);
        }
        return null;
    }

    static String describe(UsbDevice device) {
        if (device == null) {
            return "null";
        }
        return String.format(Locale.US,
                "VID=0x%04X PID=0x%04X interfaces=%d deviceName=%s",
                device.getVendorId(),
                device.getProductId(),
                device.getInterfaceCount(),
                device.getDeviceName());
    }
}
