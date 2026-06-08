package fyi.r0.gbxcam;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.util.Locale;
import java.util.Map;

final class GbxCartDevices {
    private static final int VID_WCH = 0x1A86;
    private static final int PID_CH340 = 0x7523;

    private GbxCartDevices() {
    }

    static UsbDevice find(UsbManager usbManager) {
        if (usbManager == null) {
            return null;
        }
        for (Map.Entry<String, UsbDevice> entry : usbManager.getDeviceList().entrySet()) {
            UsbDevice device = entry.getValue();
            // Current native code initializes CH340 directly and only implements
            // the GBxCart RW 1.4 command flow.
            if (device.getVendorId() == VID_WCH && device.getProductId() == PID_CH340) {
                return device;
            }
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
