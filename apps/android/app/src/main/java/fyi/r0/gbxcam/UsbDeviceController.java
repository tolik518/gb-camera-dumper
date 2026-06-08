package fyi.r0.gbxcam;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
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

        /** Connection state observed while starting a permission-gated action. */
        void onConnectionChanged(boolean connected);

        /** A device was attached; {@code found} is true when it is our supported GBxCart. */
        void onDeviceAttached(boolean found);

        /** A device was detached; {@code wasDisconnected} is true when it was our active device. */
        void onDeviceDetached(boolean wasDisconnected);

        void onPermissionGranted();

        void onPermissionDenied();
    }

    private final Context context;
    private final UsbManager usbManager;
    private final Listener listener;
    private UsbDevice selectedDevice;
    private GbxCartDevices.Candidate selectedCandidate;
    private Runnable pendingAction;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                listener.onDeviceAttached(refresh());
                return;
            }
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                GbxCartDevices.Candidate candidate = GbxCartDevices.find(usbManager);
                boolean disconnected = selectedDevice != null
                        && (candidate == null || candidate.device != selectedDevice);
                if (disconnected) {
                    selectedDevice = null;
                    selectedCandidate = null;
                    pendingAction = null;
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
            Runnable pending = pendingAction;
            pendingAction = null;
            if (pending != null) {
                pending.run();
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

    boolean isConnected() {
        GbxCartDevices.Candidate candidate = GbxCartDevices.find(usbManager);
        return candidate != null && candidate.canAttemptNativeSession();
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

    /** Re-discovers the device, logs the result, and returns true when found. */
    boolean refresh() {
        GbxCartDevices.Candidate candidate = GbxCartDevices.find(usbManager);
        if (candidate == null) {
            selectedDevice = null;
            selectedCandidate = null;
            listener.onUsbLog("Supported cartridge reader not found. Connect GBxCart RW 1.4, then tap Load Camera.");
            return false;
        }
        if (!candidate.canAttemptNativeSession()) {
            selectedDevice = null;
            selectedCandidate = null;
            listener.onUsbLog(candidate.label() + " found. " + candidate.supportNote());
            return false;
        }
        selectedCandidate = candidate;
        selectedDevice = candidate.device;
        listener.onUsbLog(String.format(
                "%s found: VID 0x%04X, PID 0x%04X. %s",
                candidate.label(),
                selectedDevice.getVendorId(),
                selectedDevice.getProductId(),
                candidate.supportNote()));
        return true;
    }

    /** Runs {@code action} once the device is present and USB permission is granted. */
    void withPermission(Runnable action) {
        if (!refresh()) {
            listener.onConnectionChanged(false);
            return;
        }
        listener.onConnectionChanged(true);
        if (!usbManager.hasPermission(selectedDevice)) {
            pendingAction = action;
            listener.onUsbLog("Requesting USB permission for " + selectedCandidate.label() + "...");
            usbManager.requestPermission(selectedDevice, permissionIntent());
            return;
        }
        action.run();
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
