package fyi.r0.gbxcam;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thin Activity shell: builds the collaborators, owns the lifecycle, and hosts the
 * USB receiver reactions. All gallery business logic lives in {@link GalleryController}.
 */
public class MainActivity extends Activity implements UsbDeviceController.Listener {
    private static final String TAG = "GbcamApp";

    private UsbDeviceController usb;
    private AppSettings settings;
    private MainScreen screen;
    private GbcamOperationRunner operationRunner;
    private GalleryController controller;
    private StartupDialog startupDialog;
    private final ExecutorService previewExecutor = Executors.newFixedThreadPool(3);
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usb = new UsbDeviceController(this, this);
        operationRunner = new GbcamOperationRunner();
        settings = new AppSettings(this);
        ManualMergeStore mergeStore = new ManualMergeStore(this);
        BackupRepository backups = new BackupRepository(this, settings);
        GalleryPipeline pipeline = new GalleryPipeline(settings, this::log);
        int defaultPaletteIndex = NativeGbcam.defaultPaletteIndex();
        PaletteCatalog palettes = PaletteCatalog.load();
        int paletteIndex = settings.paletteIndex(defaultPaletteIndex);
        paletteIndex = Math.max(0, Math.min(paletteIndex, palettes.labels.length - 1));
        screen = new MainScreen(
                this,
                palettes.labels,
                palettes.colors,
                settings.paletteFavorites(palettes.labels),
                settings.recentPalettes(palettes.labels),
                defaultPaletteIndex);
        controller = new GalleryController(this, screen, usb, operationRunner, settings, mergeStore,
                backups, pipeline, palettes, previewExecutor, backgroundExecutor, paletteIndex,
                () -> { if (startupDialog != null) startupDialog.markCameraLoaded(); });
        startupDialog = new StartupDialog(
                this, screen, usb, settings, backgroundExecutor, () -> controller.onLoadRequested());
        screen.setListener(controller);
        screen.setBitmapExecutor(previewExecutor);
        screen.setPaletteIndex(paletteIndex);
        screen.setLogsVisibleFromSettings(settings.showLogs());
        screen.setShowMeta(settings.showPhotoMeta());
        UiStyle.setLogger(this::log);
        setContentView(screen.view());

        usb.register();
        log("Rust core loaded: " + NativeGbcam.version());
        boolean deviceFound = usb.refresh();
        screen.setDeviceConnected(deviceFound);
        if (deviceFound && settings.autoLoad()) {
            controller.autoLoadCamera();
        } else if (settings.loadCache()) {
            controller.loadCachedGallery();
        }
        if (settings.showStartupPopup()) {
            startupDialog.show();
        }
    }

    @Override
    public void onBackPressed() {
        if (screen != null && screen.isSelectMode()) {
            screen.selectAll(false);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (startupDialog != null) startupDialog.dispose();
        if (controller != null) controller.dispose();
        usb.unregister();
        UiStyle.setLogger(null);
        operationRunner.shutdown();
        backgroundExecutor.shutdownNow();
        previewExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        boolean deviceFound = usb.refresh();
        screen.setDeviceConnected(deviceFound);
        if (deviceFound && settings.autoLoad()) {
            controller.autoLoadCamera();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        controller.handleActivityResult(requestCode, resultCode, data);
    }

    // --- UsbDeviceController.Listener: host reactions to USB events ------------

    @Override
    public void onUsbLog(String message) {
        log(message);
    }

    @Override
    public void onConnectionChanged(boolean connected) {
        screen.setDeviceConnected(connected);
    }

    @Override
    public void onDeviceAttached(boolean found) {
        screen.setDeviceConnected(found);
        if (found && settings.autoLoad()) {
            controller.autoLoadCamera();
        }
        if (startupDialog != null) startupDialog.markDeviceAttached(found);
    }

    @Override
    public void onDeviceDetached(boolean wasDisconnected) {
        if (wasDisconnected) {
            log("Cartridge reader disconnected.");
            screen.setDeviceConnected(false);
        }
        if (startupDialog != null) startupDialog.markDeviceDetached();
    }

    @Override
    public void onPermissionGranted() {
        log("USB permission granted.");
    }

    @Override
    public void onPermissionDenied() {
        Log.w(TAG, "USB permission denied");
        log("USB permission denied.");
    }

    /** Forwards a log line to the controller (which owns the on-screen log). */
    private void log(String message) {
        if (controller != null) {
            controller.onLog(message);
        } else {
            Log.i(TAG, message);
        }
    }
}
