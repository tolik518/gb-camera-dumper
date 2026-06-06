package fyi.r0.gbxcam;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class GbcamOperationRunner {
    interface Callback {
        void onLog(String message);

        void onProgress(String message);

        void onBusyChanged(boolean busy, String message);

        void onError(String message);

        void onGalleryLoaded(GalleryState gallery);
    }

    private static final String TAG = "GbcamApp";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    void loadGallery(
            UsbManager usbManager,
            UsbDevice device,
            File outputDir,
            int paletteIndex,
            Callback callback) {
        runUsbOperation(usbManager, device, "Loading photos from camera...", callback, connection -> {
            String json = NativeGbcam.loadGalleryFromFd(
                    connection.getFileDescriptor(),
                    outputDir.getAbsolutePath(),
                    paletteIndex,
                    makeProgress(callback));
            return GalleryState.fromJson(json);
        });
    }

    void deletePhotos(
            UsbManager usbManager,
            UsbDevice device,
            GalleryState gallery,
            File outputDir,
            int paletteIndex,
            Callback callback) {
        String slots = SlotSet.selected(gallery, false).toCsv();
        runUsbOperation(usbManager, device, "Deleting selected photos...", callback, connection -> {
            String json = NativeGbcam.deletePhotosFromFd(
                    connection.getFileDescriptor(),
                    gallery.savePath,
                    outputDir.getAbsolutePath(),
                    slots,
                    paletteIndex,
                    makeProgress(callback));
            return GalleryState.fromJson(json);
        });
    }

    void recoverPhotos(
            UsbManager usbManager,
            UsbDevice device,
            GalleryState gallery,
            File outputDir,
            int paletteIndex,
            Callback callback) {
        String slots = SlotSet.selected(gallery, true).toCsv();
        runUsbOperation(usbManager, device, "Recovering selected deleted photos...", callback, connection -> {
            String json = NativeGbcam.recoverPhotosFromFd(
                    connection.getFileDescriptor(),
                    gallery.savePath,
                    outputDir.getAbsolutePath(),
                    slots,
                    paletteIndex,
                    makeProgress(callback));
            return GalleryState.fromJson(json);
        });
    }

    void reorderPhotos(
            UsbManager usbManager,
            UsbDevice device,
            GalleryState gallery,
            File outputDir,
            int paletteIndex,
            String physicalSlotsCsv,
            String busyMessage,
            Callback callback) {
        runUsbOperation(usbManager, device, busyMessage, callback, connection -> {
            String json = NativeGbcam.reorderPhotosFromFd(
                    connection.getFileDescriptor(),
                    gallery.savePath,
                    outputDir.getAbsolutePath(),
                    physicalSlotsCsv,
                    paletteIndex,
                    makeProgress(callback));
            return GalleryState.fromJson(json);
        });
    }

    void shutdown() {
        io.shutdownNow();
    }

    private void runUsbOperation(
            UsbManager usbManager,
            UsbDevice device,
            String busyMessage,
            Callback callback,
            UsbWork work) {
        callback.onBusyChanged(true, busyMessage);
        callback.onLog(busyMessage);
        Log.i(TAG, busyMessage + " device=" + GbxCartDevices.describe(device));

        io.execute(() -> {
            UsbDeviceConnection connection = null;
            try {
                connection = usbManager.openDevice(device);
                if (connection == null) {
                    throw new IllegalStateException("Could not open USB device.");
                }

                GalleryState gallery = work.run(connection);
                main.post(() -> callback.onGalleryLoaded(gallery));
            } catch (Throwable t) {
                Log.e(TAG, "USB operation failed", t);
                String errorMsg = t.getMessage() != null ? t.getMessage() : t.toString();
                main.post(() -> callback.onError("Error: " + errorMsg));
            } finally {
                if (connection != null) {
                    connection.close();
                }
                main.post(() -> callback.onBusyChanged(false, null));
            }
        });
    }

    private NativeGbcam.Progress makeProgress(Callback callback) {
        return message -> main.post(() -> {
            callback.onLog(message);
            callback.onProgress(message);
        });
    }

    private interface UsbWork {
        GalleryState run(UsbDeviceConnection connection) throws Exception;
    }
}
