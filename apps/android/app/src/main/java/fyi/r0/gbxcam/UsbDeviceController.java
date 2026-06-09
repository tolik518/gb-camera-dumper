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

        /** A supported reader is ready for cartridge operations. */
        void onConnectionChanged(boolean connected);

        /** A known reader was attached. {@code canAttemptCameraLoad} is false for unsupported hardware. */
        void onReaderAttached(GbxCartDevices.ReaderStatus status);

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
    private GbxCartDevices.ReaderStatus selectedReaderStatus;
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
                        && (candidate == null || !sameUsbDevice(candidate.device, selectedDevice));
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
                } else if (selectedReaderStatus != null && !selectedReaderStatus.canAttemptCameraLoad) {
                    listener.onUnsupportedReader(selectedReaderStatus.unsupportedMessage);
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

    GbxCartDevices.ReaderStatus readerStatus() {
        return selectedReaderStatus;
    }

    boolean isReaderPresent() {
        return selectedDevice != null;
    }

    boolean isConnected() {
        return selectedReaderStatus != null && selectedReaderStatus.canAttemptCameraLoad;
    }

    boolean hasPendingAction() {
        return pendingAction != null;
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

    /** Re-discovers the device and returns true when a known reader USB family is present. */
    boolean refresh() {
        return handleRefresh();
    }

    /** Runs {@code action} once a supported reader is present and USB permission is granted. */
    void withPermission(Runnable action) {
        if (!handleRefresh()) {
            listener.onConnectionChanged(false);
            return;
        }
        if (selectedReaderStatus != null && !selectedReaderStatus.canAttemptCameraLoad) {
            listener.onConnectionChanged(false);
            listener.onUnsupportedReader(selectedReaderStatus.unsupportedMessage);
            return;
        }
        if (!usbManager.hasPermission(selectedDevice)) {
            pendingAction = action;
            String label = selectedReaderStatus != null ? selectedReaderStatus.label : selectedCandidate.label();
            listener.onUsbLog("Requesting USB permission for " + label + "...");
            usbManager.requestPermission(selectedDevice, permissionIntent());
            return;
        }
        listener.onConnectionChanged(true);
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
        boolean sameDevice = sameUsbDevice(selectedDevice, candidate.device);
        selectedDevice = candidate.device;

        if (sameDevice && selectedReaderStatus != null) {
            listener.onConnectionChanged(isConnected());
            return true;
        }

        if (!candidate.canAttemptCameraLoad()) {
            selectedReaderStatus = candidate.readerStatus();
            listener.onUsbLog(candidate.label() + " detected: " + GbxCartDevices.describe(selectedDevice));
            listener.onReaderAttached(selectedReaderStatus);
            listener.onConnectionChanged(isConnected());
            if (!selectedReaderStatus.canAttemptCameraLoad) {
                listener.onUnsupportedReader(selectedReaderStatus.unsupportedMessage);
            }
            return true;
        }

        listener.onUsbLog(candidate.label() + " detected: " + GbxCartDevices.describe(selectedDevice));
        if (!usbManager.hasPermission(selectedDevice)) {
            selectedReaderStatus = null;
            listener.onReaderAttached(null);
            listener.onConnectionChanged(false);
            listener.onUsbLog("Grant USB permission to identify this cartridge reader.");
            return true;
        }

        selectedReaderStatus = candidate.readerStatus();
        listener.onReaderAttached(selectedReaderStatus);
        listener.onConnectionChanged(isConnected());
        listener.onUsbLog("Supported USB reader family: " + selectedReaderStatus.label);
        return true;
    }

    private void clearSelection() {
        selectedDevice = null;
        selectedCandidate = null;
        selectedReaderStatus = null;
        pendingAction = null;
    }

    private static boolean sameUsbDevice(UsbDevice left, UsbDevice right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        String leftName = left.getDeviceName();
        String rightName = right.getDeviceName();
        return left.getVendorId() == right.getVendorId()
                && left.getProductId() == right.getProductId()
                && (leftName == null ? rightName == null : leftName.equals(rightName));
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
