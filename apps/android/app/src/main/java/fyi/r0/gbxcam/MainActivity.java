package fyi.r0.gbxcam;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
        implements MainScreen.Listener, GbcamOperationRunner.Callback, UsbDeviceController.Listener {
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
        showShareSizeDialog(scale -> doShareSelected(gallery, scale));
    }

    private void showShareSizeDialog(UiStyle.ChoiceListener onPicked) {
        final int[] scales = { 1, 2, 4, 8 };
        int lastScale = settings.shareMultiplier();
        int selectedIdx = 0;
        for (int i = 0; i < scales.length; i++) {
            if (scales[i] == lastScale) { selectedIdx = i; break; }
        }
        String[] labels = {
            "1×  ·  128 × 112  (native pixel size)",
            "2×  ·  256 × 224",
            "4×  ·  512 × 448",
            "8×  ·  1024 × 896"
        };
        UiStyle.singleChoiceDialog(this, "Share size", labels, selectedIdx, which -> {
            settings.saveShareMultiplier(scales[which]);
            onPicked.onChoice(scales[which]);
        });
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
        showBackupPicker(saves);
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
        showSettingsDialog();
    }

    @Override
    public void onAboutRequested() {
        showAboutDialog();
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

    private GalleryState applyLocallyDeletedSlots(GalleryState gallery) {
        Set<String> deleted = settings.locallyDeletedSlots();
        if (deleted.isEmpty()) return gallery;
        List<GalleryPhoto> photos = new ArrayList<>(gallery.photos);
        boolean changed = false;
        for (int i = 0; i < photos.size(); i++) {
            GalleryPhoto p = photos.get(i);
            if (!p.isActiveAlbumPhoto() || p.mergedRgb) continue;
            if (!deleted.contains(String.valueOf(p.physicalSlot))) continue;
            photos.set(i, p.withDeleted(true));
            changed = true;
        }
        if (!changed) return gallery;
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
        showPhotoDetail(photo);
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
        settings.clearLocallyDeletedSlots();
        gallery = applyAutoRgbMerge(gallery);
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

    private void showSettingsDialog() {
        Dialog dialog = UiStyle.baseDialog(this);
        UiStyle.Palette colors = UiStyle.palette(this);
        int accent = screen.accentColor();

        LinearLayout content = UiStyle.dialog(this, dialog, "Settings", null);
        // Insert About button before Close in the dialog header
        LinearLayout header = (LinearLayout) content.getChildAt(0);
        Button headerAbout = UiStyle.button(this, "About", colors.textSecondary, colors.surfaceRaised, colors.border);
        headerAbout.setTextSize(13);
        headerAbout.setOnClickListener(v -> { dialog.dismiss(); showAboutDialog(); });
        LinearLayout.LayoutParams headerAboutParams = new LinearLayout.LayoutParams(dp(72), dp(44));
        headerAboutParams.setMargins(0, 0, dp(6), 0);
        header.addView(headerAbout, header.getChildCount() - 1, headerAboutParams);

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(0, dp(12), 0, 0);

        CheckBox autoLoad = UiStyle.settingsCheckBox(
                this,
                "Auto-load camera on launch",
                "Starts reading the camera automatically when a GBxCart RW is connected.",
                settings.autoLoad(),
                accent);
        CheckBox loadCache = UiStyle.settingsCheckBox(
                this,
                "Load last gallery when offline",
                "Shows the most recent save backup when no camera is connected or auto-load is off.",
                settings.loadCache(),
                accent);
        CheckBox showLogs = UiStyle.settingsCheckBox(
                this,
                "Show logs by default",
                "Keeps the operation log panel open after app startup.",
                settings.showLogs(),
                accent);
        CheckBox showPhotoMetaCb = UiStyle.settingsCheckBox(
                this,
                "Show photo metadata",
                "Shows slot number and merge info below each photo in the gallery.",
                settings.showPhotoMeta(),
                accent);
        CheckBox showStartupPopupCb = UiStyle.settingsCheckBox(
                this,
                "Show popup on startup",
                "Displays connection instructions when the app launches.",
                settings.showStartupPopup(),
                accent);
        CheckBox confirmWrites = UiStyle.settingsCheckBox(
                this,
                "Confirm album writes",
                "Asks before delete, recover, reorder, compact, or clear operations.",
                settings.confirmAlbumWrites(),
                accent);
        CheckBox exportDeleted = UiStyle.settingsCheckBox(
                this,
                "Export deleted photos",
                "Includes selected recoverable deleted slots when saving or sharing images.",
                settings.exportDeleted(),
                accent);
        CheckBox autoRgbMerge = UiStyle.settingsCheckBox(
                this,
                "Auto-detect RGB sets",
                "Merges consecutive 3-shot RGB and 4-shot CRGB captures.",
                settings.autoRgbMerge(),
                accent);

        final String[] rgb4Value = { settings.rgb4Order() };
        final String[] rgb3Value = { settings.rgb3Order() };
        final String[] defaultAlgoValue = { settings.mergeAlgorithm() };

        Runnable saveAll = () -> {
            boolean rgbChanged = autoRgbMerge.isChecked() != settings.autoRgbMerge()
                    || !rgb4Value[0].equals(settings.rgb4Order())
                    || !rgb3Value[0].equals(settings.rgb3Order())
                    || !defaultAlgoValue[0].equals(settings.mergeAlgorithm());
            settings.saveSettings(
                    autoLoad.isChecked(), loadCache.isChecked(), showLogs.isChecked(),
                    confirmWrites.isChecked(), exportDeleted.isChecked(), autoRgbMerge.isChecked(),
                    rgb4Value[0], rgb3Value[0], defaultAlgoValue[0]);
            settings.saveShowStartupPopup(showStartupPopupCb.isChecked());
            screen.setLogsVisibleFromSettings(showLogs.isChecked());
            if (rgbChanged && screen.gallery() != null) {
                recolorCachedGallery(paletteIndex, false);
            }
        };

        View rgb4Row = settingsPickerRow(
                "4-shot order",
                "Position of C (clear), R, G, B in consecutive shots.",
                AppSettings.RGB4_ORDERS, rgb4Value, saveAll);
        View rgb3Row = settingsPickerRow(
                "3-shot order",
                "Position of R, G, B in consecutive shots.",
                AppSettings.RGB3_ORDERS, rgb3Value, saveAll);
        View algoRow = settingsIdPickerRow(
                "Default merge algorithm",
                "Algorithm used when auto-detecting RGB sets.",
                RgbMergeDetector.ALGORITHM_IDS,
                RgbMergeDetector.ALGORITHM_LABELS,
                new String[]{ "Basic", "Clear Lum", "Norm RGB", "Norm+Clear", "Sat Boost", "Adaptive ★" },
                defaultAlgoValue, saveAll);

        boolean rgbMergeOn = autoRgbMerge.isChecked();
        rgb4Row.setVisibility(rgbMergeOn ? View.VISIBLE : View.GONE);
        rgb3Row.setVisibility(rgbMergeOn ? View.VISIBLE : View.GONE);
        algoRow.setVisibility(rgbMergeOn ? View.VISIBLE : View.GONE);

        autoLoad.setOnCheckedChangeListener((btn, checked) -> saveAll.run());
        loadCache.setOnCheckedChangeListener((btn, checked) -> saveAll.run());
        showLogs.setOnCheckedChangeListener((btn, checked) -> saveAll.run());
        showPhotoMetaCb.setOnCheckedChangeListener((btn, checked) -> {
            settings.saveShowPhotoMeta(checked);
            screen.setShowMeta(checked);
        });
        showStartupPopupCb.setOnCheckedChangeListener((btn, checked) -> saveAll.run());
        confirmWrites.setOnCheckedChangeListener((btn, checked) -> saveAll.run());
        exportDeleted.setOnCheckedChangeListener((btn, checked) -> saveAll.run());
        autoRgbMerge.setOnCheckedChangeListener((btn, checked) -> {
            rgb4Row.setVisibility(checked ? View.VISIBLE : View.GONE);
            rgb3Row.setVisibility(checked ? View.VISIBLE : View.GONE);
            algoRow.setVisibility(checked ? View.VISIBLE : View.GONE);
            saveAll.run();
        });

        options.addView(UiStyle.settingsRow(this, autoLoad));
        options.addView(UiStyle.settingsRow(this, loadCache));
        options.addView(UiStyle.settingsRow(this, showLogs));
        options.addView(UiStyle.settingsRow(this, showPhotoMetaCb));
        options.addView(UiStyle.settingsRow(this, showStartupPopupCb));
        options.addView(UiStyle.settingsRow(this, confirmWrites));
        options.addView(UiStyle.settingsRow(this, exportDeleted));
        options.addView(UiStyle.settingsRow(this, autoRgbMerge));
        options.addView(rgb4Row);
        options.addView(rgb3Row);
        options.addView(algoRow);
        options.addView(settingsActionRow(
                "Backups",
                "Browse and restore save file backups.",
                accent,
                () -> {
                    dialog.dismiss();
                    onBackupsRequested();
                }));
        options.addView(settingsActionRow(
                "Import save",
                "Load a .sav file from your device.",
                accent,
                () -> {
                    dialog.dismiss();
                    onImportSaveRequested();
                }));
        options.addView(settingsActionRow(
                "Export save",
                "Save the current camera backup to your device.",
                accent,
                () -> {
                    dialog.dismiss();
                    onExportSaveRequested();
                }));
        options.addView(settingsActionRow(
                "Apply current palette as app icon",
                "Updates the launcher icon to match the selected palette. The app will briefly restart.",
                accent,
                () -> {
                    dialog.dismiss();
                    runInBackground(() -> PaletteIcons.applyIfChanged(MainActivity.this, paletteIndex));
                }));
        options.addView(settingsActionRow(
                "Share logs",
                "Share the current session log text.",
                accent,
                () -> {
                    dialog.dismiss();
                    shareCurrentLogs();
                }));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(options);
        content.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dp(360), dp(72) * 11 + dp(12))));

        dialog.setContentView(content);
        UiStyle.sizeDialog(dialog, this, 32, 560);
        dialog.show();
    }

    private void showAboutDialog() {
        Dialog dialog = UiStyle.baseDialog(this);
        UiStyle.Palette colors = UiStyle.palette(this);

        LinearLayout content = UiStyle.dialog(this, dialog, "GBxCAM Viewer", "v" + packageVersionName());

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(8), 0, 0);

        GalleryState connectedGallery = screen.gallery();
        if (connectedGallery != null && usb.device() != null) {
            body.addView(aboutSection("Connected Device", colors));
            TextView deviceInfo = new TextView(this);
            deviceInfo.setText(connectedGallery.connected);
            deviceInfo.setTextColor(colors.textPrimary);
            deviceInfo.setTextSize(13);
            deviceInfo.setPadding(0, dp(6), 0, dp(6));
            body.addView(deviceInfo);
        }

        body.addView(aboutSection("Feedback", colors));
        body.addView(aboutRow("Report a bug", "518@returnnull.de",
                "mailto:518@returnnull.de", colors));

        body.addView(aboutSection("Author", colors));
        body.addView(aboutRow("tolik518", null,
                "https://github.com/tolik518", colors));

        body.addView(aboutSection("Acknowledgments", colors));
        body.addView(aboutRow("FlashGBX",
                "USB protocol · lesserkuma · GPL-3.0",
                "https://github.com/lesserkuma/FlashGBX", colors));
        body.addView(aboutRow("gbcam-rev-engineer",
                "GB Camera docs · Antonio Niño Díaz · CC BY 4.0",
                "https://github.com/AntonioND/gbcam-rev-engineer", colors));
        body.addView(aboutRow("Inject-pictures-in-your-Game-Boy-Camera-saves",
                "Save file research · Raphaël Boichot",
                "https://github.com/Raphael-Boichot/Inject-pictures-in-your-Game-Boy-Camera-saves", colors));

        body.addView(aboutSection("License", colors));
        body.addView(aboutRow("GPL-3.0-or-later", null,
                "https://www.gnu.org/licenses/gpl-3.0.html", colors));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        content.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(content);
        UiStyle.sizeDialog(dialog, this, 32, 560);
        dialog.show();
    }

    private View aboutSection(String label, UiStyle.Palette colors) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, dp(2));
        row.setLayoutParams(params);
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(colors.textSecondary);
        title.setTextSize(11);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setIncludeFontPadding(false);
        row.addView(title);
        View rule = new View(this);
        rule.setBackgroundColor(colors.border);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(0, dp(1), 1);
        ruleParams.setMargins(dp(8), 0, 0, 0);
        row.addView(rule, ruleParams);
        return row;
    }

    private View aboutRow(String label, String description, String url, UiStyle.Palette colors) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setOnClickListener(v -> openUrl(url));
        row.setPadding(0, dp(6), 0, dp(6));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(colors.primary);
        labelView.setTextSize(13);
        text.addView(labelView);
        if (description != null && !description.isEmpty()) {
            TextView descView = new TextView(this);
            descView.setText(description);
            descView.setTextColor(colors.textSecondary);
            descView.setTextSize(11);
            descView.setIncludeFontPadding(false);
            text.addView(descView);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = new TextView(this);
        arrow.setText("↗");
        arrow.setTextColor(colors.textMuted);
        arrow.setTextSize(14);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.MATCH_PARENT));
        return row;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            onLog("Could not open link.");
        }
    }

    @SuppressWarnings("deprecation")
    private String packageVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "?";
        }
    }

    private View settingsActionRow(String title, String description, int accentColor, Runnable action) {
        UiStyle.Palette colors = UiStyle.palette(this);
        android.widget.FrameLayout row = new android.widget.FrameLayout(this);
        row.setBackground(UiStyle.rounded(this, colors.surfaceRaised, colors.border, 10, 1));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(android.view.Gravity.CENTER_VERTICAL);
        inner.setPadding(dp(14), dp(10), dp(10), dp(10));

        android.widget.TextView text = new android.widget.TextView(this);
        text.setText(UiStyle.twoLineText(title, description, colors.textPrimary, colors.textSecondary));
        text.setTextSize(12);
        inner.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        android.widget.TextView arrow = new android.widget.TextView(this);
        arrow.setText("›");
        arrow.setTextColor(accentColor);
        arrow.setTextSize(22);
        arrow.setGravity(android.view.Gravity.CENTER);
        arrow.setPadding(dp(8), 0, dp(4), 0);
        inner.addView(arrow);

        row.addView(inner, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(66));
        params.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(params);
        return row;
    }

    private View settingsPickerRow(
            String title,
            String description,
            String[] options,
            String[] valueHolder,
            Runnable onChange) {
        return UiStyle.settingsChoiceRow(
                this,
                title,
                description,
                options,
                indexOf(options, valueHolder[0]),
                valueHolder[0],
                64,
                13,
                true,
                screen.accentColor(),
                which -> {
                    valueHolder[0] = options[which];
                    if (onChange != null) onChange.run();
                    return options[which];
                });
    }

    private View settingsIdPickerRow(
            String title,
            String description,
            String[] ids,
            String[] labels,
            String[] shortLabels,
            String[] valueHolder,
            Runnable onChange) {
        return UiStyle.settingsChoiceRow(
                this,
                title,
                description,
                labels,
                indexOf(ids, valueHolder[0]),
                shortLabelForId(ids, shortLabels, valueHolder[0]),
                80,
                11,
                false,
                screen.accentColor(),
                which -> {
                    valueHolder[0] = ids[which];
                    if (onChange != null) onChange.run();
                    return shortLabelForId(ids, shortLabels, ids[which]);
                });
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    private static String shortLabelForId(String[] ids, String[] shortLabels, String id) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(id)) return shortLabels[i];
        }
        return id;
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
            gallery = applyLocallyDeletedSlots(gallery);
            gallery = applyAutoRgbMerge(gallery);
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

    private void showBackupPicker(File[] saves) {
        Dialog dialog = UiStyle.baseDialog(this);
        UiStyle.Palette colors = UiStyle.palette(this);
        int panelRaised = colors.surfaceRaised;
        int border = colors.borderStrong;
        int textPrimary = colors.textPrimary;
        int textSecondary = colors.textSecondary;
        int textMuted = colors.textMuted;
        int accent = screen.accentColor();

        LinearLayout content = UiStyle.dialog(
                this,
                dialog,
                "Save Backups",
                saves.length + " file" + (saves.length == 1 ? "" : "s") + " available");

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(12), 0, 0);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (File save : saves) {
            list.addView(backupRow(save, format, dialog, panelRaised, border, textPrimary, textSecondary, textMuted, accent, colors.logBackground));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dp(420), dp(86) * saves.length + dp(12)));
        content.addView(scroll, scrollParams);

        dialog.setContentView(content);
        UiStyle.sizeDialog(dialog, this, 32, 560);
        dialog.show();
    }

    private View backupRow(
            File save,
            SimpleDateFormat format,
            Dialog dialog,
            int panelRaised,
            int border,
            int textPrimary,
            int textSecondary,
            int textMuted,
            int accent,
            int previewBackground) {
        boolean current = save.getName().equals("GAMEBOYCAMERA.sav");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(rounded(panelRaised, current ? accent : border, 10, current ? 2 : 1));
        row.setOnClickListener(v -> {
            dialog.dismiss();
            loadBackupSave(save);
        });

        ImageView[] tiles = new ImageView[4];
        View[] badge = new View[1];
        row.addView(backupMosaic(tiles, badge, current, border, accent, textSecondary, previewBackground),
                new LinearLayout.LayoutParams(dp(58), dp(58)));
        previewExecutor.submit(() -> {
            GalleryPhoto[] photos = backups.previewPhotos(save);
            postToUi(() -> {
                boolean anyPhoto = false;
                for (int i = 0; i < tiles.length; i++) {
                    if (photos[i] != null && tiles[i] != null) {
                        tiles[i].setImageURI(Uri.fromFile(new File(photos[i].path)));
                        anyPhoto = true;
                    }
                }
                if (anyPhoto && badge[0] != null) {
                    badge[0].setVisibility(View.GONE);
                }
            });
        });

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);

        TextView name = new TextView(this);
        name.setText(save.getName());
        name.setTextColor(textPrimary);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        text.addView(name);

        TextView meta = new TextView(this);
        meta.setText(format.format(new Date(save.lastModified()))
                + " · "
                + Math.max(1, save.length() / 1024)
                + " KB");
        meta.setTextColor(current ? textSecondary : textMuted);
        meta.setTextSize(12);
        meta.setSingleLine(true);
        text.addView(meta);
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = new TextView(this);
        arrow.setText("Load");
        arrow.setTextColor(current ? accent : textSecondary);
        arrow.setTextSize(12);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(54), dp(42)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(params);
        return row;
    }

    private View backupMosaic(ImageView[] tilesOut, View[] badgeOut, boolean current,
            int border, int accent, int textSecondary, int background) {
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(2), dp(2), dp(2), dp(2));
        frame.setBackground(rounded(background, current ? accent : border, 8, current ? 2 : 1));

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipToOutline(true);
        for (int y = 0; y < 2; y++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int x = 0; x < 2; x++) {
                int index = y * 2 + x;
                ImageView tile = new ImageView(this);
                tile.setScaleType(ImageView.ScaleType.CENTER_CROP);
                tile.setBackgroundColor(background);
                tilesOut[index] = tile;
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
                tileParams.setMargins(x == 0 ? 0 : dp(1), y == 0 ? 0 : dp(1), 0, 0);
                line.addView(tile, tileParams);
            }
            rows.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        }
        frame.addView(rows, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        TextView badge = new TextView(this);
        badge.setText("SAV");
        badge.setGravity(Gravity.CENTER);
        badge.setTextSize(9);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(textSecondary);
        frame.addView(badge, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        badgeOut[0] = badge;

        return frame;
    }

    private void showPhotoDetail(GalleryPhoto photo) {
        Dialog dialog = UiStyle.baseDialog(this);

        // Mutable state shared across in-place navigations
        GalleryPhoto[] photoRef       = { photo };
        String[]       algoRef        = { photo.mergedRgb && photo.mergedAlgorithm != null ? photo.mergedAlgorithm : "" };
        boolean[]      algoChangedRef  = { false };
        String[]       orderRef       = { photo.mergedKind != null && !photo.mergedKind.isEmpty() ? photo.mergedKind
                                          : photo.mergedSourceCount == 4 ? AppSettings.DEFAULT_RGB4_ORDER : AppSettings.DEFAULT_RGB3_ORDER };
        boolean[]      orderChangedRef = { false };
        int[]          genRef         = { 0 };

        // The scrollable photo content slot is rebuilt on every navigation;
        // the nav row lives outside the scroll so its position never changes.
        ScrollView photoScroll = new ScrollView(this);
        photoScroll.setFillViewport(true);

        UiStyle.Palette colors0 = UiStyle.palette(this);
        Button shareBtn = UiStyle.button(this, "Share", screen.accentColor(), colors0.surfaceRaised, screen.accentColor());

        // Header title refs — updated on every navigation
        TextView[] titleViewRef    = { null };
        TextView[] subtitleViewRef = { null };

        // Rebuild trigger: resets algo state, repopulates scroll, updates header + nav.
        Runnable[] rebuildRef = { null };
        rebuildRef[0] = () -> {
            GalleryPhoto p = photoRef[0];
            algoRef[0]         = p.mergedRgb && p.mergedAlgorithm != null ? p.mergedAlgorithm : "";
            algoChangedRef[0]  = false;
            orderRef[0]        = p.mergedKind != null && !p.mergedKind.isEmpty() ? p.mergedKind
                                 : p.mergedSourceCount == 4 ? AppSettings.DEFAULT_RGB4_ORDER : AppSettings.DEFAULT_RGB3_ORDER;
            orderChangedRef[0] = false;
            genRef[0]++;

            // Update header title / subtitle
            if (titleViewRef[0] != null)    titleViewRef[0].setText(photoDetailTitle(p));
            if (subtitleViewRef[0] != null) subtitleViewRef[0].setText(photoDetailSubtitle(p));

            // Rebuild scroll content for the new photo
            photoScroll.removeAllViews();
            photoScroll.addView(buildDetailScrollContent(
                    p, algoRef, algoChangedRef, orderRef, orderChangedRef, genRef,
                    titleViewRef, subtitleViewRef));
            photoScroll.scrollTo(0, 0);

            shareBtn.setOnClickListener(v -> shareSinglePhoto(p));
        };

        // Swipe left/right anywhere in the photo area to navigate.
        android.view.GestureDetector swipeDetector = new android.view.GestureDetector(
                this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                   float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                // Require a clearly horizontal gesture (1.2× wider than tall) and minimum travel.
                if (Math.abs(dx) <= Math.abs(dy) * 1.2f || Math.abs(dx) < dp(50)) return false;
                GalleryState g = screen.gallery();
                if (g == null) return false;
                int idx = g.photos.indexOf(photoRef[0]);
                if (dx < 0 && idx >= 0 && idx < g.photos.size() - 1) {
                    if ((algoChangedRef[0] || orderChangedRef[0]) && photoRef[0].mergedRgb)
                        applyOrSaveDetailChanges(photoRef[0], orderRef[0], algoRef[0]);
                    photoRef[0] = g.photos.get(idx + 1);
                    rebuildRef[0].run();
                    return true;
                } else if (dx > 0 && idx > 0) {
                    if ((algoChangedRef[0] || orderChangedRef[0]) && photoRef[0].mergedRgb)
                        applyOrSaveDetailChanges(photoRef[0], orderRef[0], algoRef[0]);
                    photoRef[0] = g.photos.get(idx - 1);
                    rebuildRef[0].run();
                    return true;
                }
                return false;
            }
        });
        photoScroll.setOnTouchListener((v, event) -> {
            swipeDetector.onTouchEvent(event);
            return false;  // let ScrollView handle vertical scrolling normally
        });

        // Outer dialog shell: inline header (holds live title refs) + scroll + share button
        LinearLayout outer = UiStyle.dialogContent(this, colors0);
        {
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout titleBlock = new LinearLayout(this);
            titleBlock.setOrientation(LinearLayout.VERTICAL);
            TextView titleView = new TextView(this);
            titleView.setTextColor(colors0.textPrimary);
            titleView.setTextSize(19);
            titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleViewRef[0] = titleView;
            titleBlock.addView(titleView);
            TextView subtitleView = new TextView(this);
            subtitleView.setTextColor(colors0.textSecondary);
            subtitleView.setTextSize(12);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            subtitleViewRef[0] = subtitleView;
            titleBlock.addView(subtitleView);
            header.addView(titleBlock, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button closeBtn = UiStyle.button(this, "Close",
                    colors0.textSecondary, colors0.surfaceRaised, colors0.border);
            closeBtn.setTextSize(13);
            closeBtn.setOnClickListener(v -> dialog.dismiss());
            header.addView(closeBtn, new LinearLayout.LayoutParams(dp(82), dp(44)));
            outer.addView(header);
        }
        rebuildRef[0].run();   // sets title, subtitle, scroll content, nav buttons

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.setMargins(0, dp(4), 0, 0);
        outer.addView(photoScroll, scrollParams);

        LinearLayout.LayoutParams shareBtnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        shareBtnParams.setMargins(0, dp(8), 0, 0);
        outer.addView(shareBtn, shareBtnParams);

        dialog.setContentView(outer);
        dialog.setOnDismissListener(d -> {
            if ((algoChangedRef[0] || orderChangedRef[0]) && photoRef[0].mergedRgb)
                applyOrSaveDetailChanges(photoRef[0], orderRef[0], algoRef[0]);
        });
        UiStyle.sizeDialog(dialog, this, 32, 560);
        dialog.show();

        // Lock the dialog height after first layout so navigating between photos
        // (which differ in chip/dropdown count) never shifts the nav row.
        outer.post(() -> {
            if (dialog.isShowing() && dialog.getWindow() != null) {
                android.view.WindowManager.LayoutParams wp = dialog.getWindow().getAttributes();
                if (wp.height == android.view.WindowManager.LayoutParams.WRAP_CONTENT) {
                    wp.height = outer.getHeight();
                    dialog.getWindow().setAttributes(wp);
                }
            }
        });
    }

    private LinearLayout buildDetailScrollContent(
            GalleryPhoto photo,
            String[]  algoRef,
            boolean[] algoChangedRef,
            String[]  orderRef,
            boolean[] orderChangedRef,
            int[]     genRef,
            TextView[] titleViewRef,
            TextView[] subtitleViewRef) {
        UiStyle.Palette colors = UiStyle.palette(this);
        int panel      = colors.surface;
        int panelRaised = colors.surfaceRaised;
        int panelSoft  = UiStyle.blend(colors.surfaceRaised, colors.surface, 0.45f);
        int border     = colors.borderStrong;
        int borderSoft = colors.border;
        int textPrimary   = colors.textPrimary;
        int textSecondary = colors.textSecondary;
        int textMuted     = colors.textMuted;
        int danger = colors.danger;
        int accent = screen.accentColor();

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);

        // Status chips
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(8), 0, 0);
        statusRow.addView(detailChip(
                photo.deleted ? "Deleted" : photo.mergedRgb ? mergedPhotoTitle(photo) : "Original",
                photo.deleted ? danger : accent, panelSoft, photo.deleted ? danger : accent));
        String mergeKindLabel = photo.mergedRgb
                ? (photo.isManualMerge() ? "Manually merged" : "Auto-merged")
                : (photo.copy ? "Copy" : "Camera photo");
        statusRow.addView(detailChip(mergeKindLabel, textSecondary, panelSoft, borderSoft));
        if (photo.mergedRgb && photo.mergedAlgorithm != null && !photo.mergedAlgorithm.isEmpty()) {
            statusRow.addView(detailChip(RgbMergeDetector.algorithmShortLabel(photo.mergedAlgorithm), textSecondary, panelSoft, borderSoft));
        }
        if (!photo.mergedRgb) {
            statusRow.addView(detailChip(
                    photo.metadataValid ? "Metadata OK" : "Check metadata",
                    photo.metadataValid ? textSecondary : danger,
                    panelSoft,
                    photo.metadataValid ? borderSoft : danger));
        }
        inner.addView(statusRow, statusParams);

        // Technical metadata (toggleable)
        if (settings.showPhotoMeta()) {
            LinearLayout infoRow = new LinearLayout(this);
            infoRow.setOrientation(LinearLayout.HORIZONTAL);
            infoRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            infoParams.setMargins(0, dp(8), 0, 0);
            infoRow.addView(detailChip(
                    photo.mergedRgb ? mergedSourceLabel(photo) : "Album " + String.format(Locale.US, "%02d", photo.displayIndex + 1),
                    textMuted, panel, borderSoft));
            if (!photo.mergedRgb) infoRow.addView(detailChip("Slot " + (photo.physicalSlot + 1), textMuted, panel, borderSoft));
            infoRow.addView(detailChip(photo.width + "×" + photo.height, textMuted, panel, borderSoft));
            infoRow.addView(detailChip("Border " + photo.border, textMuted, panel, borderSoft));
            inner.addView(infoRow, infoParams);

            if (!photo.ownerUserId.isEmpty()) {
                LinearLayout ownerRow = new LinearLayout(this);
                ownerRow.setOrientation(LinearLayout.HORIZONTAL);
                ownerRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams ownerParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                ownerParams.setMargins(0, dp(8), 0, 0);
                ownerRow.addView(detailChip("Owner " + photo.ownerUserId, textMuted, panel, borderSoft));
                inner.addView(ownerRow, ownerParams);
            }
        }

        // Photo image
        FrameLayout imageMat = new FrameLayout(this);
        imageMat.setPadding(dp(8), dp(8), dp(8), dp(8));
        imageMat.setBackground(rounded(colors.logBackground, border, 12, 1));
        LinearLayout.LayoutParams matParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        matParams.setMargins(0, dp(12), 0, dp(12));

        ImageView image = new ImageView(this);
        Bitmap bmp = PhotoRenderer.renderBitmap(photo, palettes.colorsFor(paletteIndex));
        if (bmp != null) image.setImageBitmap(bmp);
        else             image.setImageURI(Uri.fromFile(new File(photo.path)));
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAlpha(photo.deleted ? 0.86f : 1.0f);
        imageMat.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        final ProgressBar[] progressRef = { null };
        if (photo.mergedRgb) {
            ProgressBar progress = new ProgressBar(this);
            progress.setIndeterminate(true);
            progress.setVisibility(View.GONE);
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(48), dp(48));
            pp.gravity = Gravity.CENTER;
            imageMat.addView(progress, pp);
            progressRef[0] = progress;
        }
        inner.addView(imageMat, matParams);

        // Order + algorithm selectors (merged photos only)
        if (photo.mergedRgb) {
            boolean isManual = photo.isManualMerge();

            // Channel-order dropdown — only for manual merges (auto-merged order is fixed by detection)
            if (isManual) {
                String[] orders = photo.mergedSourceCount == 4 ? AppSettings.RGB4_ORDERS : AppSettings.RGB3_ORDERS;
                FrameLayout orderField = new FrameLayout(this);
                orderField.setBackground(rounded(panelRaised, borderSoft, 8, 1));
                orderField.setClickable(true);
                orderField.setFocusable(true);
                TextView orderDropText = new TextView(this);
                orderDropText.setSingleLine(true);
                orderDropText.setGravity(Gravity.CENTER_VERTICAL);
                orderDropText.setIncludeFontPadding(false);
                orderDropText.setTextColor(textPrimary);
                orderDropText.setTextSize(13);
                orderDropText.setPadding(dp(14), 0, dp(36), 0);
                orderDropText.setText("Order: " + orderRef[0]);
                orderField.addView(orderDropText, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                TextView orderArrow = new TextView(this);
                orderArrow.setText("▼");
                orderArrow.setTextSize(10);
                orderArrow.setTextColor(textSecondary);
                orderArrow.setGravity(Gravity.CENTER);
                orderArrow.setEnabled(false);
                FrameLayout.LayoutParams oArrowP = new FrameLayout.LayoutParams(
                        dp(28), FrameLayout.LayoutParams.MATCH_PARENT);
                oArrowP.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                orderField.addView(orderArrow, oArrowP);
                orderField.setOnClickListener(v -> {
                    int cur = indexOf(orders, orderRef[0]);
                    UiStyle.dropdown(this, orderField, orders, cur, accent, which -> {
                        if (orders[which].equals(orderRef[0])) return;
                        orderRef[0]        = orders[which];
                        orderChangedRef[0] = true;
                        orderDropText.setText("Order: " + orders[which]);
                        // Update header title ("Merged RGB" → "Merged BRG") immediately
                        if (titleViewRef[0] != null)
                            titleViewRef[0].setText("Merged " + orders[which]);
                        if (subtitleViewRef[0] != null)
                            subtitleViewRef[0].setText(mergedVariantSubtitle(photo, orderRef[0], algoRef[0]));
                        runPreviewMerge(photo, orderRef[0], algoRef[0], image, progressRef[0], genRef);
                    });
                });
                LinearLayout.LayoutParams orderP = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
                orderP.setMargins(0, 0, 0, dp(6));
                inner.addView(orderField, orderP);
            }

            // Algorithm dropdown
            FrameLayout algoField = new FrameLayout(this);
            algoField.setBackground(rounded(panelRaised, borderSoft, 8, 1));
            algoField.setClickable(true);
            algoField.setFocusable(true);
            TextView algoDropText = new TextView(this);
            algoDropText.setSingleLine(true);
            algoDropText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            algoDropText.setGravity(Gravity.CENTER_VERTICAL);
            algoDropText.setIncludeFontPadding(false);
            algoDropText.setTextColor(textPrimary);
            algoDropText.setTextSize(13);
            algoDropText.setPadding(dp(14), 0, dp(36), 0);
            algoDropText.setText(RgbMergeDetector.algorithmLabel(algoRef[0]));
            algoField.addView(algoDropText, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            TextView algoArrow = new TextView(this);
            algoArrow.setText("▼");
            algoArrow.setTextSize(10);
            algoArrow.setTextColor(textSecondary);
            algoArrow.setGravity(Gravity.CENTER);
            algoArrow.setEnabled(false);
            FrameLayout.LayoutParams arrowP = new FrameLayout.LayoutParams(
                    dp(28), FrameLayout.LayoutParams.MATCH_PARENT);
            arrowP.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            algoField.addView(algoArrow, arrowP);
            algoField.setOnClickListener(v -> {
                boolean hasClear = photo.mergedSourceCount == 4;
                String[] ids    = RgbMergeDetector.compatibleAlgorithmIds(hasClear);
                String[] labels = RgbMergeDetector.compatibleAlgorithmLabels(hasClear);
                UiStyle.dropdown(this, algoField, labels, indexOf(ids, algoRef[0]), accent, which -> {
                    if (ids[which].equals(algoRef[0])) return;
                    algoRef[0]        = ids[which];
                    algoChangedRef[0] = true;
                    algoDropText.setText(labels[which]);
                    if (subtitleViewRef[0] != null)
                        subtitleViewRef[0].setText(mergedVariantSubtitle(photo, orderRef[0], algoRef[0]));
                    runPreviewMerge(photo, orderRef[0], algoRef[0], image, progressRef[0], genRef);
                });
            });
            LinearLayout.LayoutParams algoP = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
            algoP.setMargins(0, 0, 0, dp(12));
            inner.addView(algoField, algoP);
        }

        return inner;
    }

    private void applyOrSaveDetailChanges(GalleryPhoto photo, String order, String algorithm) {
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

    private void shareSinglePhoto(GalleryPhoto photo) {
        GalleryState gallery = screen.gallery();
        if (gallery == null) return;
        showShareSizeDialog(scale -> {
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

    private void runPreviewMerge(
            GalleryPhoto mergedPhoto,
            String order,
            String algorithm,
            ImageView imageView,
            ProgressBar progressBar,
            int[] generation) {
        generation[0]++;
        int gen = generation[0];
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);

        GalleryState gallery = screen.gallery();
        previewExecutor.submit(() -> {
            Bitmap preview = null;
            try {
                List<GalleryPhoto> monoPhotos = monoSourcePhotos(gallery);
                int startIdx = mergedPhoto.mergedSourceStartDisplayIndex;
                int count = mergedPhoto.mergedSourceCount;

                // Build a display-index → mono-photo map so we're immune to merged photos
                // being interspersed in gallery.photos and to empty-deleted filtering offsets.
                Map<Integer, GalleryPhoto> monoByIndex = new HashMap<>();
                for (GalleryPhoto mp : monoPhotos) {
                    if (!mp.mergedRgb) monoByIndex.put(mp.displayIndex, mp);
                }

                GalleryPhoto[] sourceForPreview = new GalleryPhoto[count];
                for (int pos = 0; pos < count; pos++) {
                    sourceForPreview[pos] = monoByIndex.get(startIdx + pos);
                }
                preview = RgbMergeDetector.previewMerge(
                        sourceForPreview, order, count, algorithm);
            } catch (Exception ignored) {
            }
            final Bitmap result = preview;
            postToUi(() -> {
                if (gen != generation[0]) {
                    if (result != null) result.recycle();
                    return;
                }
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (result != null) {
                    int w = imageView.getWidth();
                    int h = imageView.getHeight();
                    Bitmap display = (w > 0 && h > 0)
                            ? Bitmap.createScaledBitmap(result, w, h, true)
                            : result;
                    if (display != result) result.recycle();
                    imageView.setAdjustViewBounds(false);
                    imageView.setScaleType(ImageView.ScaleType.FIT_XY);
                    imageView.setImageBitmap(display);
                }
            });
        });
    }

    private String photoDetailTitle(GalleryPhoto photo) {
        if (photo.mergedRgb) {
            return mergedPhotoTitle(photo);
        }
        return (photo.deleted ? "Deleted " : "Photo ") + String.format(Locale.US, "%02d", photo.displayIndex + 1);
    }

    private String photoDetailSubtitle(GalleryPhoto photo) {
        if (photo.mergedRgb) {
            return mergedVariantSubtitle(photo,
                    photo.mergedKind != null && !photo.mergedKind.isEmpty() ? photo.mergedKind
                            : photo.mergedSourceCount == 4 ? AppSettings.DEFAULT_RGB4_ORDER : AppSettings.DEFAULT_RGB3_ORDER,
                    photo.mergedAlgorithm);
        }
        String label = photo.deleted ? "Recoverable" : "Album";
        if (photo.physicalSlot >= 0) {
            return label + " " + String.format(Locale.US, "%02d", photo.displayIndex + 1)
                    + " · Slot " + (photo.physicalSlot + 1);
        }
        return label + " " + String.format(Locale.US, "%02d", photo.displayIndex + 1);
    }

    private String mergedPhotoTitle(GalleryPhoto photo) {
        return "Merged " + (photo.mergedKind == null || photo.mergedKind.isEmpty() ? "RGB" : photo.mergedKind);
    }

    private String mergedSourceLabel(GalleryPhoto photo) {
        return photo.mergedSourceCount > 0
                ? "Sources " + mergedSourceRange(photo)
                : "Sources";
    }

    private String mergedVariantSubtitle(GalleryPhoto photo, String order, String algorithm) {
        StringBuilder subtitle = new StringBuilder(photo.isManualMerge() ? "Manual merge" : "Auto merge");
        if (photo.mergedSourceCount > 0) {
            subtitle.append(" · ").append(mergedSourceRange(photo));
        }
        if (algorithm != null && !algorithm.isEmpty()) {
            subtitle.append(" · ").append(RgbMergeDetector.algorithmShortLabel(algorithm));
        }
        return subtitle.toString();
    }

    private String mergedSourceRange(GalleryPhoto photo) {
        int start = photo.mergedSourceStartDisplayIndex + 1;
        int end = start + Math.max(0, photo.mergedSourceCount - 1);
        return String.format(Locale.US, "%02d-%02d", start, end);
    }

    private TextView detailChip(String text, int textColor, int fillColor, int strokeColor) {
        return UiStyle.chip(this, text, textColor, fillColor, strokeColor);
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp, int strokeDp) {
        return UiStyle.rounded(this, fill, stroke, radiusDp, strokeDp);
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
                gallery = applyLocallyDeletedSlots(gallery);
                gallery = applyAutoRgbMerge(gallery);
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

    private GalleryState applyAutoRgbMerge(GalleryState gallery) {
        gallery = filterEmptyDeletedPhotos(gallery);
        if (!settings.autoRgbMerge()) {
            return gallery;
        }
        List<GalleryPhoto> sourcePhotos = monoSourcePhotos(gallery);
        GalleryState merged = RgbMergeDetector.addAutoMergedPhotos(gallery, sourcePhotos, new File(gallery.outputDir),
                settings.rgb4Order(), settings.rgb3Order(), settings.mergeAlgorithm(), settings.mergeAlgorithmOverrides());
        int added = merged.photos.size() - gallery.photos.size();
        if (added > 0) {
            onLog("Auto-merged " + added + " RGB " + plural(added, "set") + ".");
        }
        return merged;
    }

    private GalleryState filterEmptyDeletedPhotos(GalleryState gallery) {
        List<GalleryPhoto> filtered = null;
        for (int i = 0; i < gallery.photos.size(); i++) {
            GalleryPhoto photo = gallery.photos.get(i);
            if (photo.deleted && emptyImages.isEmpty(photo.path)) {
                if (filtered == null) {
                    filtered = new ArrayList<>(gallery.photos.subList(0, i));
                }
            } else if (filtered != null) {
                filtered.add(photo);
            }
        }
        if (filtered == null) return gallery;
        return gallery.withPhotos(filtered);
    }

    private List<GalleryPhoto> monoSourcePhotos(GalleryState gallery) {
        int monoPaletteIndex = NativeGbcam.defaultPaletteIndex();
        if (gallery.paletteIndex == monoPaletteIndex) {
            return gallery.photos;
        }
        try {
            File monoDir = new File(gallery.outputDir, "rgb-merge-mono");
            GalleryState mono = GalleryState.fromJson(NativeGbcam.loadGalleryFromSave(
                    gallery.savePath,
                    monoDir.getAbsolutePath(),
                    monoPaletteIndex));
            // Apply the same empty-deleted filter so indices align with the main gallery.
            return filterEmptyDeletedPhotos(mono).photos;
        } catch (Exception e) {
            return gallery.photos;
        }
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
