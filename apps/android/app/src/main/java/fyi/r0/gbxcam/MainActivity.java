package fyi.r0.gbxcam;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity
        implements MainScreen.Listener, GbcamOperationRunner.Callback, UsbDeviceController.Listener,
        SettingsDialog.Host, PhotoDetailDialog.Host {
    private static final String TAG = "GbcamApp";
    private static final int REQUEST_IMPORT_SAVE = 1;
    private static final int REQUEST_EXPORT_SAVE = 2;

    private UsbDeviceController usb;
    private AppSettings settings;
    private MainScreen screen;
    private GbcamOperationRunner operationRunner;
    private boolean autoLoadAttempted;
    private int paletteIndex;
    private PaletteCatalog palettes;
    private File pendingSaveExport;
    private final ExecutorService previewExecutor = Executors.newFixedThreadPool(3);
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger recolorGeneration = new AtomicInteger(0);
    private final EmptyImageCache emptyImages = new EmptyImageCache();
    private ManualMergeStore mergeStore;
    private BackupRepository backups;
    private GalleryPipeline pipeline;
    private boolean destroyed;

    private TextView startupStep1Label;
    private TextView startupStep2Label;
    private int startupStepDoneColor;
    private int startupStepDefaultColor;
    private boolean startupStep2Checking;
    private final Handler startupHandler = new Handler(Looper.getMainLooper());
    private final Runnable startupCartridgeCheckRunnable = this::doStartupCartridgeCheck;

    // --- UsbDeviceController.Listener: host reactions to USB events ------------

    @Override
    public void onUsbLog(String message) {
        onLog(message);
    }

    @Override
    public void onConnectionChanged(boolean connected) {
        screen.setDeviceConnected(connected);
    }

    @Override
    public void onDeviceAttached(boolean found) {
        screen.setDeviceConnected(found);
        if (found && settings.autoLoad() && !autoLoadAttempted) {
            autoLoadCamera();
        }
        if (found && startupStep1Label != null) {
            startupStep1Label.setTextColor(startupStepDoneColor);
            startupHandler.removeCallbacks(startupCartridgeCheckRunnable);
            startupStep2Checking = false;
            doStartupCartridgeCheck();
        }
    }

    @Override
    public void onDeviceDetached(boolean wasDisconnected) {
        if (wasDisconnected) {
            onLog("GBxCart RW disconnected.");
            screen.setDeviceConnected(false);
        }
        if (startupStep1Label != null) startupStep1Label.setTextColor(startupStepDefaultColor);
        if (startupStep2Label != null) startupStep2Label.setTextColor(startupStepDefaultColor);
        startupHandler.removeCallbacks(startupCartridgeCheckRunnable);
        startupStep2Checking = false;
    }

    @Override
    public void onPermissionGranted() {
        onLog("USB permission granted.");
    }

    @Override
    public void onPermissionDenied() {
        Log.w(TAG, "USB permission denied");
        onLog("USB permission denied.");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usb = new UsbDeviceController(this, this);
        operationRunner = new GbcamOperationRunner();
        settings = new AppSettings(this);
        mergeStore = new ManualMergeStore(this);
        backups = new BackupRepository(this, settings, emptyImages);
        pipeline = new GalleryPipeline(settings, emptyImages, this::onLog);
        int defaultPaletteIndex = NativeGbcam.defaultPaletteIndex();
        palettes = PaletteCatalog.load();
        paletteIndex = settings.paletteIndex(defaultPaletteIndex);
        paletteIndex = Math.max(0, Math.min(paletteIndex, palettes.labels.length - 1));
        screen = new MainScreen(
                this,
                this,
                palettes.labels,
                palettes.colors,
                settings.paletteFavorites(palettes.labels),
                settings.recentPalettes(palettes.labels),
                defaultPaletteIndex);
        screen.setBitmapExecutor(previewExecutor);
        screen.setPaletteIndex(paletteIndex);
        screen.setLogsVisibleFromSettings(settings.showLogs());
        screen.setShowMeta(settings.showPhotoMeta());
        UiStyle.setLogger(this::onLog);
        setContentView(screen.view());

        usb.register();
        onLog("Rust core loaded: " + NativeGbcam.version());
        boolean deviceFound = usb.refresh();
        screen.setDeviceConnected(deviceFound);
        if (deviceFound && settings.autoLoad()) {
            autoLoadCamera();
        } else if (settings.loadCache()) {
            loadCachedGallery();
        }
        if (settings.showStartupPopup()) {
            showStartupPopup();
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
        destroyed = true;
        startupHandler.removeCallbacks(startupCartridgeCheckRunnable);
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
        if (deviceFound && settings.autoLoad() && !autoLoadAttempted) {
            autoLoadCamera();
        }
    }

    @Override
    public void onManualMergeRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null) return;
        List<GalleryPhoto> sources = new ArrayList<>();
        for (GalleryPhoto photo : gallery.photos) {
            if (photo.selected && !photo.deleted && !photo.mergedRgb && photo.physicalSlot >= 0) {
                sources.add(photo);
            }
        }
        int count = sources.size();
        if (count != 3 && count != 4) return;
        sortByDisplayIndex(sources);
        String order = count == 3 ? settings.rgb3Order() : settings.rgb4Order();
        screen.setBusy(true, "Merging " + count + " photos...");
        runInBackground(() -> {
            GalleryPhoto merged = RgbMergeDetector.manualMerge(
                    sources.toArray(new GalleryPhoto[0]),
                    count, order,
                    new File(gallery.outputDir),
                    settings.mergeAlgorithm());
            postToUi(() -> {
                screen.setBusy(false, null);
                if (merged == null) {
                    onLog("Manual merge failed.");
                    return;
                }
                // Use the current gallery, not the stale capture from before the background task,
                // so we insert into whatever state (palette change, etc.) is showing right now.
                GalleryState current = screen.gallery();
                if (current == null) {
                    onLog("Manual merge aborted: gallery unavailable.");
                    return;
                }
                List<GalleryPhoto> photos = new ArrayList<>(current.photos);
                int insertAt = ManualMergeStore.insertIndex(photos, merged);
                if (insertAt < 0) {
                    onLog("Manual merge aborted: source photos not found in gallery.");
                    return;
                }
                photos.add(insertAt, merged);
                mergeStore.addVariant(merged);
                screen.showGallery(current.withPhotos(photos));
                onLog("Merged " + count + " photos → " + merged.name);
            });
        });
    }

    @Override
    public void onLoadRequested() {
        if (!usb.isConnected()) {
            Toast.makeText(this,
                    "No GBxCart RW connected. Plug in the cartridge reader and try again.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        usb.withPermission(() -> operationRunner.loadGallery(usb.manager(), usb.device(), dumpsDir(), paletteIndex, this));
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
            PhotoExporter.ExportResult result = PhotoExporter.exportSelected(
                    this, gallery, palettes.colorsFor(gallery.paletteIndex), settings.exportDeleted());
            onLog("Saved " + result.imageCount + " " + plural(result.imageCount, "photo") + ":\n" + result.summary());
        } catch (Exception e) {
            onLog("Save failed: " + e.toString());
        }
    }

    @Override
    public void onShareSelectedRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null || gallery.selectedCount() == 0) return;
        ShareSizeDialog.show(this, settings, scale -> doShareSelected(gallery, scale));
    }

    private void doShareSelected(GalleryState gallery, int scale) {
        int selected = gallery.selectedCount();
        if (selected == 0) return;
        try {
            PhotoExporter.ExportResult result = PhotoExporter.exportSelectedScaled(
                    this, gallery, palettes.colorsFor(gallery.paletteIndex),
                    settings.exportDeleted(), scale);
            if (result.imageUris.isEmpty()) {
                onLog("Share unavailable for this Android version. Saved:\n" + result.summary());
                return;
            }
            Intent share;
            if (result.imageUris.size() == 1) {
                share = new Intent(Intent.ACTION_SEND);
                share.putExtra(Intent.EXTRA_STREAM, result.imageUris.get(0));
            } else {
                share = new Intent(Intent.ACTION_SEND_MULTIPLE);
                share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, result.imageUris);
            }
            share.setType("image/jpeg");
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // ClipData is required on Android 10+ for receiving apps to read the URIs.
            android.content.ClipData clip = android.content.ClipData.newRawUri("", result.imageUris.get(0));
            for (int i = 1; i < result.imageUris.size(); i++) {
                clip.addItem(new android.content.ClipData.Item(result.imageUris.get(i)));
            }
            share.setClipData(clip);
            startActivity(Intent.createChooser(share, "Share photos"));
            onLog("Shared " + result.imageCount + " " + plural(result.imageCount, "photo") + " at " + scale + "×.");
        } catch (Exception e) {
            onLog("Share failed: " + e.toString());
        }
    }

    @Override
    public void onBackupsRequested() {
        File[] saves = backups.listBackups();
        if (saves.length == 0) {
            onLog("No save backups found.");
            return;
        }
        new BackupPickerDialog(this, screen, backups, previewExecutor, this::postToUi, this::loadBackupSave)
                .show(saves);
    }

    @Override
    public void onImportSaveRequested() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT_SAVE);
    }

    @Override
    public void onExportSaveRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null) {
            return;
        }
        pendingSaveExport = new File(gallery.savePath);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "GAMEBOYCAMERA.sav");
        startActivityForResult(intent, REQUEST_EXPORT_SAVE);
    }

    @Override
    public void onSettingsRequested() {
        new SettingsDialog(this, settings, screen, this).show();
    }

    @Override
    public void shareLogs() {
        shareCurrentLogs();
    }

    @Override
    public void applyPaletteIcon() {
        runInBackground(() -> PaletteIcons.applyIfChanged(MainActivity.this, paletteIndex));
    }

    @Override
    public void recolorGallery() {
        recolorCachedGallery(paletteIndex, false);
    }

    @Override
    public void onAboutRequested() {
        new AboutDialog(this, this::onLog).show(screen.gallery(), usb.device() != null);
    }

    @Override
    public void onDeleteSelectedRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null) return;
        int activeCount = gallery.selectedActiveCount();
        int manualCount = gallery.selectedManualMergeCount();
        if (activeCount == 0 && manualCount == 0) return;

        String detail;
        if (activeCount > 0 && manualCount > 0) {
            detail = "Mark " + activeCount + " " + plural(activeCount, "photo") + " as deleted (recoverable from camera) "
                    + "and permanently delete " + manualCount + " " + plural(manualCount, "merged image") + "?";
        } else if (activeCount > 0) {
            detail = "Mark " + activeCount + " " + plural(activeCount, "photo") + " as deleted? " +
                    (activeCount == 1 ? "It stays" : "They stay") + " recoverable from the cartridge.";
        } else {
            detail = "Permanently delete " + manualCount + " " + plural(manualCount, "merged image") + "? This cannot be undone.";
        }

        boolean cameraAvailable = usb.isConnected();

        Runnable doDelete = () -> {
            GalleryState g = screen.gallery();
            if (g == null) return;
            GalleryState after = g.selectedManualMergeCount() > 0 ? deleteSelectedManualMerges(g) : g;
            if (after.selectedActiveCount() > 0) {
                if (cameraAvailable) {
                    usb.withPermission(() -> {
                        GalleryState current = screen.gallery();
                        if (current != null)
                            operationRunner.deletePhotos(usb.manager(), usb.device(), current, dumpsDir(), paletteIndex, MainActivity.this);
                    });
                } else {
                    after = markSelectedCameraPhotosDeletedLocally(after);
                    screen.showGallery(after);
                }
            } else {
                screen.showGallery(after);
            }
        };

        if (settings.confirmAlbumWrites()) {
            UiStyle.confirmDialog(this, "Delete selected?", detail, "Delete", doDelete);
        } else {
            doDelete.run();
        }
    }

    private GalleryState deleteSelectedManualMerges(GalleryState gallery) {
        List<GalleryPhoto> photos = new ArrayList<>(gallery.photos);
        boolean changed = false;
        for (int i = photos.size() - 1; i >= 0; i--) {
            GalleryPhoto p = photos.get(i);
            if (!p.selected || !p.isManualMerge()) continue;
            photos.remove(i);
            new File(p.path).delete();
            mergeStore.removeByPath(p.path);
            changed = true;
        }
        if (changed) mergeStore.save();
        return gallery.withPhotos(photos);
    }

    private GalleryState markSelectedCameraPhotosDeletedLocally(GalleryState gallery) {
        List<GalleryPhoto> photos = new ArrayList<>(gallery.photos);
        Set<String> newSlots = new HashSet<>();
        boolean changed = false;
        for (int i = 0; i < photos.size(); i++) {
            GalleryPhoto p = photos.get(i);
            if (!p.selected || !p.isActiveAlbumPhoto() || p.mergedRgb) continue;
            photos.set(i, p.withDeleted(true));
            newSlots.add(String.valueOf(p.physicalSlot));
            changed = true;
        }
        if (!changed) return gallery;
        settings.addLocallyDeletedSlots(newSlots);
        return gallery.withPhotos(photos);
    }

    private GalleryState recoverSelectedCameraPhotosLocally(GalleryState gallery) {
        Set<String> localDeleted = settings.locallyDeletedSlots();
        if (localDeleted.isEmpty()) return gallery;
        List<GalleryPhoto> photos = new ArrayList<>(gallery.photos);
        Set<String> toRestore = new HashSet<>();
        boolean changed = false;
        for (int i = 0; i < photos.size(); i++) {
            GalleryPhoto p = photos.get(i);
            if (!p.selected || !p.isDeletedAlbumPhoto() || p.mergedRgb) continue;
            if (!localDeleted.contains(String.valueOf(p.physicalSlot))) continue;
            photos.set(i, p.withDeleted(false));
            toRestore.add(String.valueOf(p.physicalSlot));
            changed = true;
        }
        if (!changed) return gallery;
        settings.removeLocallyDeletedSlots(toRestore);
        return gallery.withPhotos(photos);
    }

    @Override
    public void onRecoverSelectedRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null) return;
        int deletedCount = gallery.selectedDeletedCount();
        if (deletedCount == 0) return;

        String detail = "Restore " + deletedCount + " deleted " + plural(deletedCount, "slot")
                + " into the camera album. A save backup is kept in the app dumps folder.";

        boolean cameraAvailable = usb.isConnected();

        confirmOrRun("Recover deleted?", detail, "Recover", () -> {
            GalleryState g = screen.gallery();
            if (g == null) return;
            if (cameraAvailable) {
                usb.withPermission(() -> {
                    GalleryState current = screen.gallery();
                    if (current != null)
                        operationRunner.recoverPhotos(usb.manager(), usb.device(), current, dumpsDir(), paletteIndex, MainActivity.this);
                });
            } else {
                GalleryState after = recoverSelectedCameraPhotosLocally(g);
                screen.showGallery(after);
            }
        });
    }

    @Override
    public void onMoveSelectedFirstRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null || gallery.selectedActiveCount() == 0) {
            return;
        }

        String csv = gallery.selectedActiveFirstPhysicalSlotsCsv();
        confirmOrRun(
                "Move selected photos first?",
                "This rewrites the album order so selected active photos appear first. A save backup is kept in the app dumps folder.",
                "Move",
                () -> usb.withPermission(() -> {
                    GalleryState g = screen.gallery();
                    if (g != null) operationRunner.reorderPhotos(usb.manager(), usb.device(), g, dumpsDir(), paletteIndex, csv, "Moving selected photos to front...", this);
                }));
    }

    @Override
    public void onCompactAlbumRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null) {
            return;
        }

        String csv = gallery.activePhysicalSlotsCsv();
        confirmOrRun(
                "Compact album order?",
                "This rewrites active photos into contiguous album positions and leaves deleted slots hidden. A save backup is kept in the app dumps folder.",
                "Compact",
                () -> usb.withPermission(() -> {
                    GalleryState g = screen.gallery();
                    if (g != null) operationRunner.reorderPhotos(usb.manager(), usb.device(), g, dumpsDir(), paletteIndex, csv, "Compacting album order...", this);
                }));
    }

    @Override
    public void onClearAlbumRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null) {
            return;
        }

        String csv = "";
        confirmOrRun(
                "Clear camera album?",
                "This hides every album slot by writing an empty state vector. Image data remains in SRAM for recovery/export until overwritten. A save backup is kept in the app dumps folder.",
                "Clear",
                () -> usb.withPermission(() -> {
                    GalleryState g = screen.gallery();
                    if (g != null) operationRunner.reorderPhotos(usb.manager(), usb.device(), g, dumpsDir(), paletteIndex, csv, "Clearing album order...", this);
                }));
    }

    @Override
    public void onPhotoSelectionChanged(GalleryPhoto photo, boolean selected) {
        screen.updateActions();
    }

    @Override
    public void onPhotoOpenRequested(GalleryPhoto photo) {
        new PhotoDetailDialog(this, screen, settings, pipeline, previewExecutor, this::postToUi,
                () -> palettes.colorsFor(paletteIndex), this).show(photo);
    }

    @Override
    public void onPaletteChanged(int paletteIndex) {
        this.paletteIndex = Math.max(0, Math.min(paletteIndex, palettes.labels.length - 1));
        settings.savePaletteIndex(this.paletteIndex);
        GalleryState current = screen.gallery();
        if (current != null) {
            backups.rememberPalette(new File(current.savePath), this.paletteIndex);
        }
        settings.rememberRecentPalette(palettes.labels, this.paletteIndex);
        screen.setRecentPalettes(settings.recentPalettes(palettes.labels));
        onLog("Palette changed: " + palettes.labelFor(this.paletteIndex));
    }

    @Override
    public void onPaletteFavoriteToggled(int paletteIndex) {
        if (paletteIndex < 0 || paletteIndex >= palettes.labels.length) {
            return;
        }
        boolean favorite = settings.togglePaletteFavorite(palettes.labels, paletteIndex);
        screen.setPaletteFavorite(paletteIndex, favorite);
    }

    @Override
    public void onProgress(String message) {
        if (destroyed) return;
        screen.updateBusyProgress(message);
    }

    @Override
    public void onLog(String message) {
        Log.i(TAG, message);
        if (!destroyed && screen != null) {
            screen.appendLog(message);
        }
    }

    @Override
    public void onBusyChanged(boolean busy, String message) {
        if (destroyed) return;
        screen.setBusy(busy, message);
    }

    @Override
    public void onError(String message) {
        if (destroyed) return;
        onLog(message);
        screen.showBusyError(message);
    }

    @Override
    public void onGalleryLoaded(GalleryState gallery) {
        if (destroyed) return;
        // Fresh camera read: clear the locally-deleted set rather than re-applying it.
        settings.clearLocallyDeletedSlots();
        gallery = pipeline.process(gallery, false);
        mergeStore.load();
        gallery = mergeStore.inject(gallery);
        backups.rememberPalette(new File(gallery.savePath), gallery.paletteIndex);
        screen.showGallery(gallery);
        if (startupStep2Label != null) startupStep2Label.setTextColor(startupStepDoneColor);
        int loaded = gallery.photos.size();
        onLog("Loaded " + loaded + " camera " + (loaded == 1 ? "photo." : "photos."));
        if (gallery.validationErrors > 0 || gallery.validationWarnings > 0) {
            onLog("Save validation: " + gallery.validationErrors + " error(s), "
                    + gallery.validationWarnings + " warning(s).");
        }
    }

    private void showStartupPopup() {
        startupHandler.removeCallbacks(startupCartridgeCheckRunnable);
        startupStep2Checking = false;
        Dialog dialog = UiStyle.baseDialog(this);
        UiStyle.Palette colors = UiStyle.palette(this);
        int accent = screen.accentColor();

        LinearLayout content = UiStyle.dialog(this, dialog, "How to connect", "Game Boy Camera + GBxCart RW + phone");

        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        steps.setPadding(0, dp(12), 0, 0);
        startupStepDoneColor = Color.rgb(76, 175, 80);
        startupStepDefaultColor = colors.textPrimary;

        startupStep1Label = new TextView(this);
        startupStep2Label = new TextView(this);
        TextView step3Label = new TextView(this);
        steps.addView(connectionStep("1", boldSpan("Connect GBxCart RW to your phone via USB (USB-C to USB-C cable).", "USB-C to USB-C"), startupStep1Label, colors, accent));
        steps.addView(connectionStep("2", boldSpan("Insert the Game Boy Camera cartridge into GBxCart RW.", "Game Boy Camera"), startupStep2Label, colors, accent));
        steps.addView(connectionStep("3", boldSpan("Tap \"Load Camera\".", "Load Camera"), step3Label, colors, accent));

        if (usb.device() != null) {
            startupStep1Label.setTextColor(startupStepDoneColor);
            if (screen.gallery() != null) {
                startupStep2Label.setTextColor(startupStepDoneColor);
            }
            doStartupCartridgeCheck();
        }
        content.addView(steps, matchWidthWrapContent());

        TextView cartWarning = new TextView(this);
        cartWarning.setText("Only the Game Boy Camera cartridge is supported.");
        cartWarning.setTextColor(colors.textMuted);
        cartWarning.setTextSize(11);
        cartWarning.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams warnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        warnParams.setMargins(0, dp(8), 0, 0);
        content.addView(cartWarning, warnParams);

        CheckBox dontShow = UiStyle.settingsCheckBox(
                this, "Don't show on startup", null, false, accent);
        View cbRow = UiStyle.settingsRow(this, dontShow);
        LinearLayout.LayoutParams cbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        cbParams.setMargins(0, dp(12), 0, 0);
        content.addView(cbRow, cbParams);

        Button loadCamera = UiStyle.button(this, "Load Camera", accent, colors.surfaceRaised, accent);
        loadCamera.setOnClickListener(v -> {
            if (!usb.isConnected()) {
                onLoadRequested(); // shows toast; popup stays open
                return;
            }
            if (dontShow.isChecked()) {
                settings.saveShowStartupPopup(false);
            }
            startupHandler.removeCallbacks(startupCartridgeCheckRunnable);
            startupStep1Label = null;
            startupStep2Label = null;
            startupStep2Checking = false;
            dialog.dismiss();
            onLoadRequested();
        });
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        okParams.setMargins(0, dp(10), 0, 0);
        content.addView(loadCamera, okParams);

        dialog.setOnDismissListener(d -> {
            startupHandler.removeCallbacks(startupCartridgeCheckRunnable);
            startupStep1Label = null;
            startupStep2Label = null;
            startupStep2Checking = false;
        });
        dialog.setContentView(content);
        UiStyle.sizeDialog(dialog, this, 32, 480);
        dialog.show();
    }

    private void doStartupCartridgeCheck() {
        if (startupStep2Label == null || usb.device() == null || startupStep2Checking) return;
        if (screen != null && screen.isBusy()) {
            startupHandler.postDelayed(startupCartridgeCheckRunnable, 5_000);
            return;
        }
        startupStep2Checking = true;
        UsbDevice device = usb.device();
        runInBackground(() -> {
            UsbDeviceConnection conn = null;
            boolean isCamera = false;
            try {
                conn = usb.manager().openDevice(device);
                if (conn != null) {
                    isCamera = NativeGbcam.isGameBoyCameraInserted(conn.getFileDescriptor());
                }
            } catch (Throwable t) {
                Log.w(TAG, "Startup cartridge check failed", t);
            } finally {
                if (conn != null) {
                    conn.close();
                }
            }
            boolean finalIsCamera = isCamera;
            postToUi(() -> {
                startupStep2Checking = false;
                if (startupStep2Label == null || usb.device() != device) return;
                startupStep2Label.setTextColor(finalIsCamera ? startupStepDoneColor : startupStepDefaultColor);
                if (startupStep2Label.isAttachedToWindow()) {
                    startupHandler.postDelayed(startupCartridgeCheckRunnable, 12_000);
                }
            });
        });
    }

    private static SpannableString boldSpan(String text, String substring) {
        SpannableString s = new SpannableString(text);
        int i = text.indexOf(substring);
        if (i >= 0) {
            s.setSpan(new StyleSpan(Typeface.BOLD), i, i + substring.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    private View connectionStep(String number, CharSequence text, TextView label, UiStyle.Palette colors, int accent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
        row.setLayoutParams(params);

        TextView badge = new TextView(this);
        badge.setText(number);
        badge.setTextColor(accent);
        badge.setTextSize(12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(UiStyle.rounded(this, colors.surfaceRaised, accent, 999, 1));
        row.addView(badge, new LinearLayout.LayoutParams(dp(24), dp(24)));

        label.setText(text);
        label.setTextColor(colors.textPrimary);
        label.setTextSize(13);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        labelParams.setMargins(dp(12), 0, 0, 0);
        label.setLayoutParams(labelParams);
        row.addView(label);

        return row;
    }

    private void confirmOrRun(String title, String message, String action, Runnable runnable) {
        if (!settings.confirmAlbumWrites()) {
            runnable.run();
            return;
        }

        UiStyle.confirmDialog(this, title, message, action, runnable);
    }

    private void runInBackground(Runnable action) {
        backgroundExecutor.execute(action);
    }

    private void postToUi(Runnable action) {
        runOnUiThread(() -> {
            if (!destroyed) {
                action.run();
            }
        });
    }

    private void autoLoadCamera() {
        if (autoLoadAttempted || screen.gallery() != null) {
            return;
        }
        autoLoadAttempted = true;
        onLog("Loading camera automatically...");
        usb.withPermission(() -> operationRunner.loadGallery(usb.manager(), usb.device(), dumpsDir(), paletteIndex, this));
    }

    private File dumpsDir() {
        return AppFiles.dumpsDir(this);
    }

    private File appFilesDir(String type) {
        return AppFiles.appFilesDir(this, type);
    }

    private void loadCachedGallery() {
        File save = new File(dumpsDir(), "GAMEBOYCAMERA.sav");
        if (!save.isFile()) {
            return;
        }

        try {
            GalleryState gallery = GalleryState.fromJson(NativeGbcam.loadGalleryFromSave(
                    save.getAbsolutePath(),
                    dumpsDir().getAbsolutePath(),
                    paletteIndex));
            gallery = pipeline.process(gallery, true);
            mergeStore.load();
            gallery = mergeStore.inject(gallery);
            backups.rememberPalette(save, paletteIndex);
            screen.showGallery(gallery);
            onLog("Loaded cached gallery from:\n" + save.getAbsolutePath());
        } catch (Exception e) {
            onLog("Cached gallery load failed: " + e.toString());
        }
    }

    private void loadBackupSave(File save) {
        try {
            File current = new File(dumpsDir(), "GAMEBOYCAMERA.sav");
            int backupPalette = backups.paletteIndexFor(save);
            paletteIndex = backupPalette;
            settings.savePaletteIndex(paletteIndex);
            if (!save.getCanonicalPath().equals(current.getCanonicalPath())) {
                PhotoExporter.copyFile(save, current);
            }
            backups.rememberPalette(current, paletteIndex);
            loadCachedGallery();
            onLog("Loaded save backup:\n" + save.getAbsolutePath());
        } catch (Exception e) {
            onLog("Backup load failed: " + e.toString());
        }
    }

    @Override
    public void applyOrSaveDetailChanges(GalleryPhoto photo, String order, String algorithm) {
        if (isManualMerge(photo)) {
            applyManualMergeChanges(photo, order, algorithm);
        } else {
            // Auto-merged: order is fixed by detection; only algorithm can change.
            settings.saveMergeAlgorithmOverride(photo, algorithm);
            recolorCachedGallery(paletteIndex, false);
        }
    }

    private void shareCurrentLogs() {
        String logText = screen.getLogs();
        if (logText == null || logText.trim().isEmpty()) {
            Toast.makeText(this, "No logs to share.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File logsDir = new File(appFilesDir(null), "logs");
            if (!logsDir.mkdirs() && !logsDir.isDirectory()) throw new Exception("Could not create logs dir.");
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
            File logFile = new File(logsDir, "gbcam-log-" + stamp + ".txt");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(logFile)) {
                out.write(logText.getBytes("UTF-8"));
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, "GBxCAM Viewer log " + stamp);
            share.putExtra(Intent.EXTRA_TEXT, logText);
            startActivity(Intent.createChooser(share, "Share logs"));
        } catch (Exception e) {
            onLog("Share logs failed: " + e);
        }
    }

    @Override
    public void shareSinglePhoto(GalleryPhoto photo) {
        GalleryState gallery = screen.gallery();
        if (gallery == null) return;
        ShareSizeDialog.show(this, settings, scale -> {
            // Save and temporarily replace selection so exportSelectedScaled picks up just this photo.
            boolean[] prev = new boolean[gallery.photos.size()];
            for (int i = 0; i < gallery.photos.size(); i++) {
                prev[i] = gallery.photos.get(i).selected;
                gallery.photos.get(i).selected = (gallery.photos.get(i) == photo);
            }
            doShareSelected(gallery, scale);
            for (int i = 0; i < gallery.photos.size(); i++) gallery.photos.get(i).selected = prev[i];
            screen.showGallery(gallery);
        });
    }

    private static LinearLayout.LayoutParams matchWidthWrapContent() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void recolorCachedGallery(int paletteIndex, boolean logChange) {
        GalleryState previous = screen.gallery();
        if (previous == null) {
            return;
        }
        int generation = recolorGeneration.incrementAndGet();
        previewExecutor.submit(() -> {
            if (recolorGeneration.get() != generation) return;
            try {
                GalleryState gallery = GalleryState.fromJson(NativeGbcam.loadGalleryFromSave(
                        previous.savePath,
                        previous.outputDir,
                        paletteIndex));
                if (recolorGeneration.get() != generation) return;
                gallery = pipeline.process(gallery, true);
                // mergeStore.inject and copySelectionFrom run on the UI thread so they
                // always see the latest manual-merge state (e.g. a merge added while this
                // background recolor was in flight).
                GalleryState finalGallery = gallery;
                postToUi(() -> {
                    if (recolorGeneration.get() != generation) return;
                    GalleryState withMerges = mergeStore.inject(finalGallery);
                    withMerges.copySelectionFrom(previous);
                    screen.showGallery(withMerges);
                    if (logChange) onLog("Palette changed: " + withMerges.paletteName);
                });
            } catch (Exception e) {
                postToUi(() -> {
                    if (recolorGeneration.get() != generation) return;
                    onLog("Palette change failed: " + e);
                });
            }
        });
    }

    private boolean isManualMerge(GalleryPhoto photo) {
        return photo.isManualMerge();
    }

    private void applyManualMergeChanges(GalleryPhoto photo, String order, String algorithm) {
        GalleryState gallery = screen.gallery();
        if (gallery == null) return;
        int start = photo.mergedSourceStartDisplayIndex;
        int count = photo.mergedSourceCount;
        List<GalleryPhoto> sources = new ArrayList<>();
        for (GalleryPhoto p : gallery.photos) {
            if (!p.mergedRgb && !p.deleted && p.physicalSlot >= 0
                    && p.displayIndex >= start && p.displayIndex < start + count) {
                sources.add(p);
            }
        }
        if (sources.size() != count) { onLog("Source photos not found for merge update."); return; }
        sortByDisplayIndex(sources);
        // Capture the timestamp before queuing so we can detect if a concurrent
        // onManualMergeRequested has re-created the old file while we were running.
        long taskStartedAt = System.currentTimeMillis();
        runInBackground(() -> {
            GalleryPhoto updated = RgbMergeDetector.manualMerge(
                    sources.toArray(new GalleryPhoto[0]),
                    count, order, new File(gallery.outputDir), algorithm);
            postToUi(() -> {
                if (updated == null) { onLog("Merge update failed."); return; }
                // If the old file was written after this task started, a concurrent
                // onManualMergeRequested has already (re-)created it. Discard this
                // stale result to avoid deleting the newer file or overwriting the gallery.
                File oldFile = new File(photo.path);
                if (oldFile.exists() && oldFile.lastModified() > taskStartedAt) {
                    new File(updated.path).delete();
                    return;
                }
                updated.selected = photo.selected;
                if (!mergeStore.replaceVariant(photo.path, updated)) {
                    new File(updated.path).delete();
                    return;
                }
                GalleryState current = screen.gallery();
                if (current == null) return;
                List<GalleryPhoto> photos = new ArrayList<>(current.photos);
                for (int i = 0; i < photos.size(); i++) {
                    if (photos.get(i).path.equals(photo.path)) { photos.set(i, updated); break; }
                }
                screen.showGallery(current.withPhotos(photos));
                onLog("Merge updated: " + order + " / " + RgbMergeDetector.algorithmShortLabel(algorithm));
            });
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQUEST_EXPORT_SAVE) {
                pendingSaveExport = null;
            }
            return;
        }

        Uri uri = data.getData();
        try {
            if (requestCode == REQUEST_IMPORT_SAVE) {
                File target = backups.importSave(uri, paletteIndex);
                loadCachedGallery();
                onLog("Imported save:\n" + target.getAbsolutePath());
            } else if (requestCode == REQUEST_EXPORT_SAVE && pendingSaveExport != null) {
                backups.exportSave(uri, pendingSaveExport);
                onLog("Exported save file.");
            }
        } catch (Exception e) {
            onLog("File operation failed: " + e.toString());
        } finally {
            if (requestCode == REQUEST_EXPORT_SAVE) {
                pendingSaveExport = null;
            }
        }
    }

    private static String plural(int n, String singular) {
        return n == 1 ? singular : singular + "s";
    }

    private int dp(int value) {
        return UiStyle.dp(this, value);
    }

    private static void sortByDisplayIndex(List<GalleryPhoto> photos) {
        Collections.sort(photos, (left, right) -> compareInts(left.displayIndex, right.displayIndex));
    }

    private static int compareInts(int left, int right) {
        if (left < right) return -1;
        if (left > right) return 1;
        return 0;
    }

}
