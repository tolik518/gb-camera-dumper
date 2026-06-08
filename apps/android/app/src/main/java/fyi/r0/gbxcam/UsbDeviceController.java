package fyi.r0.gbxcam;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

/**
 * Owns USB device discovery, the attach/detach/permission {@link BroadcastReceiver},
 * and the permission-gated action flow. All UI reactions (screen connection state,
 * auto-load, startup-dialog steps, logging) are delegated to a {@link Listener} so
 * this class stays free of view code.
 */
final class UsbDeviceController {
    private static final String ACTION_USB_PERMISSION = "fyi.r0.gbxcam.USB_PERMISSION";

    /** Host reactions to USB lifecycle events. */
    interface Listener {
        /** A user-facing log line. */
        void onUsbLog(String message);

        /** A supported reader is ready for cartridge operations. */
        void onConnectionChanged(boolean connected);

        /** A known reader was attached. {@code supported} is false for detected-but-unsupported hardware. */
        void onReaderAttached(GbxCartDevices.ReaderDetection detection);

        /** A device was detached; {@code wasDisconnected} is true when it was our active device. */
        void onDeviceDetached(boolean wasDisconnected);

        /** A connected reader was identified but is not supported yet. */
        void onUnsupportedReader(String message);

        void onPermissionGranted();

        void onPermissionDenied();
    }

    private final Context context;
    private final UsbManager usbManager;
    private final Listener listener;
    private UsbDevice selectedDevice;
    private GbxCartDevices.Candidate selectedCandidate;
    private GbxCartDevices.ReaderDetection selectedDetection;
    private Runnable pendingAction;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                handleRefresh();
                return;
            }
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                GbxCartDevices.Candidate candidate = GbxCartDevices.find(usbManager);
                boolean disconnected = selectedDevice != null
                        && (candidate == null || candidate.device != selectedDevice);
                if (disconnected) {
                    clearSelection();
                }
                listener.onDeviceDetached(disconnected);
                return;
            }
            if (!ACTION_USB_PERMISSION.equals(action)) {
                return;
            }
            UsbDevice device = usbDeviceFrom(intent);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (!granted || device == null) {
                listener.onPermissionDenied();
                pendingAction = null;
                return;
            }
            selectedDevice = device;
            listener.onPermissionGranted();
            handleRefresh();
            Runnable pending = pendingAction;
            pendingAction = null;
            if (pending != null) {
                if (isConnected()) {
                    pending.run();
                } else if (selectedDetection != null && !selectedDetection.supported) {
                    listener.onUnsupportedReader(selectedDetection.unsupportedMessage);
                }
            }
        }
    };

    UsbDeviceController(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    UsbManager manager() {
        return usbManager;
    }

    UsbDevice device() {
        return selectedDevice;
    }

    GbxCartDevices.ReaderDetection detection() {
        return selectedDetection;
    }

    boolean isReaderPresent() {
        return selectedDevice != null;
    }

    boolean isConnected() {
        return selectedDetection != null && selectedDetection.supported;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    void register() {
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }
    }

    void unregister() {
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    /** Re-discovers the device, probes when possible, and returns true when a reader is present. */
    boolean refresh() {
        return handleRefresh();
    }

    /** Runs {@code action} once a supported reader is present and USB permission is granted. */
    void withPermission(Runnable action) {
        if (!handleRefresh()) {
            listener.onConnectionChanged(false);
            return;
        }
        if (selectedDetection != null && !selectedDetection.supported) {
            listener.onConnectionChanged(false);
            listener.onUnsupportedReader(selectedDetection.unsupportedMessage);
            return;
        }
        if (selectedDetection == null) {
            listener.onConnectionChanged(false);
            listener.onUsbLog("USB permission is required to identify the connected cartridge reader.");
            pendingAction = action;
            if (!usbManager.hasPermission(selectedDevice)) {
                listener.onUsbLog("Requesting USB permission for " + selectedCandidate.flashGbxName() + "...");
                usbManager.requestPermission(selectedDevice, permissionIntent());
            }
            return;
        }
        listener.onConnectionChanged(true);
        if (!usbManager.hasPermission(selectedDevice)) {
            pendingAction = action;
            listener.onUsbLog("Requesting USB permission for " + selectedDetection.label + "...");
            usbManager.requestPermission(selectedDevice, permissionIntent());
            return;
        }
        action.run();
    }

    private boolean handleRefresh() {
        GbxCartDevices.Candidate candidate = GbxCartDevices.find(usbManager);
        if (candidate == null) {
            clearSelection();
            listener.onUsbLog("No cartridge reader found. Connect a GBxCart RW, GBFlash, or Joey Jr device.");
            listener.onConnectionChanged(false);
            return false;
        }

        selectedCandidate = candidate;
        selectedDevice = candidate.device;

        if (!candidate.requiresNativeProbe()) {
            selectedDetection = candidate.staticDetection();
            listener.onUsbLog(candidate.flashGbxName() + " detected: " + GbxCartDevices.describe(selectedDevice));
            listener.onReaderAttached(selectedDetection);
            listener.onConnectionChanged(isConnected());
            if (!selectedDetection.supported) {
                listener.onUnsupportedReader(selectedDetection.unsupportedMessage);
            }
            return true;
        }

        listener.onUsbLog(candidate.flashGbxName() + " detected: " + GbxCartDevices.describe(selectedDevice));
        if (!usbManager.hasPermission(selectedDevice)) {
            selectedDetection = null;
            listener.onReaderAttached(null);
            listener.onConnectionChanged(false);
            listener.onUsbLog("Grant USB permission to identify this cartridge reader.");
            return true;
        }

        selectedDetection = probeReader(selectedDevice);
        listener.onReaderAttached(selectedDetection);
        listener.onConnectionChanged(isConnected());
        if (selectedDetection != null) {
            listener.onUsbLog((selectedDetection.supported ? "Supported reader: " : "Unsupported reader: ")
                    + selectedDetection.label);
            if (!selectedDetection.supported) {
                listener.onUnsupportedReader(selectedDetection.unsupportedMessage);
            }
        }
        return true;
    }

    private GbxCartDevices.ReaderDetection probeReader(UsbDevice device) {
        UsbDeviceConnection connection = null;
        try {
            connection = usbManager.openDevice(device);
            if (connection == null) {
                return GbxCartDevices.ReaderDetection.unsupported(
                        "CH340 cartridge reader",
                        "Could not open the USB device."
                                + GbxCartDevices.CONTACT_DEVELOPER_SUFFIX);
            }
            GbxCartDevices.NativeTransport transport = GbxCartDevices.nativeTransport(device);
            if (transport == null) {
                return GbxCartDevices.ReaderDetection.unsupported(
                        "CH340 cartridge reader",
                        "This cartridge reader transport is not configured yet."
                                + GbxCartDevices.CONTACT_DEVELOPER_SUFFIX);
            }
            String json = NativeGbcam.detectReaderFromFd(
                    connection.getFileDescriptor(),
                    transport.interfaceNumber,
                    transport.epOut,
                    transport.epIn,
                    transport.initializeCh340,
                    message -> listener.onUsbLog(message));
            return GbxCartDevices.ReaderDetection.fromJson(json);
        } catch (Exception e) {
            return GbxCartDevices.ReaderDetection.unsupported(
                    "CH340 cartridge reader",
                    "Could not identify the cartridge reader: " + e.getMessage()
                            + GbxCartDevices.CONTACT_DEVELOPER_SUFFIX);
        } finally {
            if (connection != null) {
                connection.close();
            }
        }
    }

    private void clearSelection() {
        selectedDevice = null;
        selectedCandidate = null;
        selectedDetection = null;
        pendingAction = null;
    }

    private PendingIntent permissionIntent() {
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(context, 0, intent, flags);
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice usbDeviceFrom(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }
}
