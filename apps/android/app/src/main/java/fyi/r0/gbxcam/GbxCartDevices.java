package fyi.r0.gbxcam;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;

final class GbxCartDevices {
    static final String CONTACT_DEVELOPER_SUFFIX =
            " Please contact the developer if you need this reader supported.";

    private static final int VID_WCH = 0x1A86;
    private static final int PID_CH340 = 0x7523;
    private static final int VID_STM = 0x0483;
    private static final int PID_STM_VCP = 0x5740;

    enum ReaderFamily {
        CH340_GB_READER(
                "CH340 cartridge reader",
                "GBxCart RW or GBFlash",
                true),
        JOEY_JR(
                "Joey Jr",
                "BennVenn Joey Jr",
                false);

        final String usbLabel;
        final String flashGbxName;
        final boolean requiresNativeProbe;

        ReaderFamily(String usbLabel, String flashGbxName, boolean requiresNativeProbe) {
            this.usbLabel = usbLabel;
            this.flashGbxName = flashGbxName;
            this.requiresNativeProbe = requiresNativeProbe;
        }
    }

    static final class Candidate {
        final UsbDevice device;
        final ReaderFamily family;

        Candidate(UsbDevice device, ReaderFamily family) {
            this.device = device;
            this.family = family;
        }

        boolean requiresNativeProbe() {
            return family.requiresNativeProbe;
        }

        String usbLabel() {
            return family.usbLabel;
        }

        String flashGbxName() {
            return family.flashGbxName;
        }

        NativeTransport nativeTransport() {
            return nativeTransportFor(family);
        }

        ReaderDetection staticDetection() {
            if (family == ReaderFamily.JOEY_JR) {
                return ReaderDetection.unsupported(
                        family.flashGbxName,
                        family.flashGbxName + " is connected but not supported by GBxCAM Viewer yet."
                                + CONTACT_DEVELOPER_SUFFIX);
            }
            return null;
        }
    }

    static final class ReaderDetection {
        final String label;
        final boolean supported;
        final String unsupportedMessage;

        ReaderDetection(String label, boolean supported, String unsupportedMessage) {
            this.label = label;
            this.supported = supported;
            this.unsupportedMessage = unsupportedMessage;
        }

        static ReaderDetection unsupported(String label, String unsupportedMessage) {
            return new ReaderDetection(label, false, unsupportedMessage);
        }

        static ReaderDetection fromJson(String json) throws Exception {
            JSONObject root = new JSONObject(json);
            String label = root.getString("label");
            boolean supported = root.getBoolean("supported");
            String unsupportedMessage = root.optString("unsupportedMessage", "");
            if (!supported && unsupportedMessage.isEmpty()) {
                unsupportedMessage = label + " is connected but not supported by GBxCAM Viewer yet."
                        + CONTACT_DEVELOPER_SUFFIX;
            }
            return new ReaderDetection(label, supported, unsupportedMessage);
        }
    }

    static final class NativeTransport {
        final int interfaceNumber;
        final int epOut;
        final int epIn;
        final boolean initializeCh340;

        NativeTransport(int interfaceNumber, int epOut, int epIn, boolean initializeCh340) {
            this.interfaceNumber = interfaceNumber;
            this.epOut = epOut;
            this.epIn = epIn;
            this.initializeCh340 = initializeCh340;
        }
    }

    private GbxCartDevices() {
    }

    static Candidate find(UsbManager usbManager) {
        if (usbManager == null) {
            return null;
        }
        for (Map.Entry<String, UsbDevice> entry : usbManager.getDeviceList().entrySet()) {
            Candidate candidate = candidateFor(entry.getValue());
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
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

    static NativeTransport nativeTransport(UsbDevice device) {
        Candidate candidate = candidateFor(device);
        if (candidate == null || !candidate.requiresNativeProbe()) {
            return null;
        }
        return candidate.nativeTransport();
    }

    private static NativeTransport nativeTransportFor(ReaderFamily family) {
        if (family == ReaderFamily.CH340_GB_READER) {
            return new NativeTransport(0, 0x02, 0x82, true);
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
