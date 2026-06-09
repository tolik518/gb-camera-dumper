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
    private String lastUnsupportedReaderMessage;
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
                this, screen, usb, settings, () -> controller.onLoadRequested());
        screen.setListener(controller);
        screen.setBitmapExecutor(previewExecutor);
        screen.setPaletteIndex(paletteIndex);
        screen.setLogsVisibleFromSettings(settings.showLogs());
        screen.setShowMeta(settings.showPhotoMeta());
        UiStyle.setLogger(this::log);
        setContentView(screen.view());

        usb.register();
        log("Rust core loaded: " + NativeGbcam.version());
        usb.refresh();
        updateReaderUi();
        if (usb.isConnected() && settings.autoLoad()) {
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
        usb.refresh();
        updateReaderUi();
        if (usb.isConnected() && settings.autoLoad()) {
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
    public void onReaderAttached(GbxCartDevices.ReaderStatus status) {
        updateReaderUi();
        if (usb.isConnected() && settings.autoLoad() && !usb.hasPendingAction()) {
            controller.autoLoadCamera();
        }
        if (startupDialog != null) {
            startupDialog.markDeviceAttached(usb.isReaderPresent());
        }
    }

    @Override
    public void onUnsupportedReader(String message) {
        if (message == null || message.equals(lastUnsupportedReaderMessage)) {
            return;
        }
        lastUnsupportedReaderMessage = message;
        UiStyle.messageDialog(this, "Unsupported cartridge reader", message);
    }

    @Override
    public void onDeviceDetached(boolean wasDisconnected) {
        if (wasDisconnected) {
            log("Cartridge reader disconnected.");
            lastUnsupportedReaderMessage = null;
            updateReaderUi();
        }
        if (startupDialog != null) startupDialog.markDeviceDetached();
    }

    private void updateReaderUi() {
        GbxCartDevices.ReaderStatus status = usb.readerStatus();
        if (status != null) {
            screen.setReaderStatus(true, status.canAttemptCameraLoad, status.label);
        } else if (usb.isReaderPresent()) {
            screen.setReaderStatus(true, true, "Cartridge reader");
        } else {
            screen.setReaderStatus(false, false, "");
        }
        screen.setDeviceConnected(usb.isConnected());
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
