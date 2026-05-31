package com.tolik518.gbcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.io.File;

public class MainActivity extends Activity implements MainScreen.Listener, GbcamOperationRunner.Callback {
    private static final String TAG = "GbcamApp";
    private static final String ACTION_USB_PERMISSION = "com.tolik518.gbcam.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbDevice selectedDevice;
    private PendingOperation pendingOperation = PendingOperation.NONE;
    private MainScreen screen;
    private GbcamOperationRunner operationRunner;
    private boolean autoLoadAttempted;

    private enum PendingOperation {
        NONE,
        LOAD,
        DELETE
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                return;
            }

            UsbDevice device = usbDeviceFrom(intent);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (!granted || device == null) {
                Log.w(TAG, "USB permission denied");
                onLog("USB permission denied.");
                pendingOperation = PendingOperation.NONE;
                return;
            }

            selectedDevice = device;
            onLog("USB permission granted.");
            PendingOperation operation = pendingOperation;
            pendingOperation = PendingOperation.NONE;
            runOperation(operation);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        operationRunner = new GbcamOperationRunner();
        screen = new MainScreen(this, this);
        setContentView(screen.view());

        registerUsbReceiver();
        onLog("Rust core loaded: " + NativeGbcam.version());
        if (refreshDevice()) {
            autoLoadCamera();
        } else {
            loadCachedGallery();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(usbReceiver);
        operationRunner.shutdown();
        super.onDestroy();
    }

    @Override
    public void onLoadRequested() {
        startOperation(PendingOperation.LOAD);
    }

    @Override
    public void onSelectAllRequested() {
        screen.selectAll(true);
    }

    @Override
    public void onDeselectAllRequested() {
        screen.selectAll(false);
    }

    @Override
    public void onSaveSelectedRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null || gallery.selectedCount() == 0) {
            return;
        }
        try {
            String out = PhotoExporter.exportSelected(this, gallery);
            onLog("Saved " + gallery.selectedCount() + " photo(s) to:\n" + out);
        } catch (Exception e) {
            onLog("Save failed: " + e.getMessage());
        }
    }

    @Override
    public void onDeleteSelectedRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null || gallery.selectedCount() == 0) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Delete selected photos?")
                .setMessage("This removes " + gallery.selectedCount() + " photo(s) from the camera album. A save backup is kept in the app dumps folder.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> startOperation(PendingOperation.DELETE))
                .show();
    }

    @Override
    public void onPhotoSelectionChanged(GalleryPhoto photo, boolean selected) {
        screen.updateActions();
    }

    @Override
    public void onLog(String message) {
        Log.i(TAG, message);
        screen.appendLog(message);
    }

    @Override
    public void onBusyChanged(boolean busy, String message) {
        screen.setBusy(busy, message);
    }

    @Override
    public void onGalleryLoaded(GalleryState gallery) {
        screen.showGallery(gallery);
        onLog("Loaded " + gallery.photos.size() + " camera photo(s).");
    }

    private boolean refreshDevice() {
        selectedDevice = GbxCartDevices.find(usbManager);
        if (selectedDevice == null) {
            onLog("GBxCart RW not found. Connect it, then tap Load Camera.");
            return false;
        }

        onLog(String.format(
                "GBxCart RW found: VID 0x%04X, PID 0x%04X",
                selectedDevice.getVendorId(),
                selectedDevice.getProductId()));
        return true;
    }

    private void autoLoadCamera() {
        if (autoLoadAttempted || screen.gallery() != null) {
            return;
        }
        autoLoadAttempted = true;
        onLog("Loading camera automatically...");
        startOperation(PendingOperation.LOAD);
    }

    private void startOperation(PendingOperation operation) {
        if (selectedDevice == null) {
            refreshDevice();
        }
        if (selectedDevice == null) {
            return;
        }

        if (!usbManager.hasPermission(selectedDevice)) {
            pendingOperation = operation;
            onLog("Requesting USB permission...");
            usbManager.requestPermission(selectedDevice, permissionIntent());
            return;
        }

        runOperation(operation);
    }

    private void runOperation(PendingOperation operation) {
        switch (operation) {
            case LOAD:
                operationRunner.loadGallery(usbManager, selectedDevice, dumpsDir(), this);
                break;
            case DELETE:
                GalleryState gallery = screen.gallery();
                if (gallery != null) {
                    operationRunner.deletePhotos(usbManager, selectedDevice, gallery, dumpsDir(), this);
                }
                break;
            case NONE:
                break;
        }
    }

    private File dumpsDir() {
        return new File(getExternalFilesDir(null), "dumps");
    }

    private void loadCachedGallery() {
        File save = new File(dumpsDir(), "GAMEBOYCAMERA.sav");
        if (!save.isFile()) {
            return;
        }

        try {
            GalleryState gallery = GalleryState.fromJson(NativeGbcam.loadGalleryFromSave(
                    save.getAbsolutePath(),
                    dumpsDir().getAbsolutePath()));
            screen.showGallery(gallery);
            onLog("Loaded cached gallery from:\n" + save.getAbsolutePath());
        } catch (Exception e) {
            onLog("Cached gallery load failed: " + e.getMessage());
        }
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    private PendingIntent permissionIntent() {
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(this, 0, intent, flags);
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice usbDeviceFrom(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }
}
