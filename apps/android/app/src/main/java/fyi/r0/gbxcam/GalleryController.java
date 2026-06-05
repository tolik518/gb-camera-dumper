package fyi.r0.gbxcam;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The gallery controller: implements {@link MainScreen.Listener} and
 * {@link GbcamOperationRunner.Callback} (plus the {@link SettingsDialog.Host} and
 * {@link PhotoDetailDialog.Host} dialog callbacks). It owns the gallery flow —
 * load/delete/recover/reorder/merge orchestration, the selected palette index, the
 * worker threading, and import/export coordination — delegating bytes/pixels to the
 * domain/data collaborators and view work to {@link MainScreen}.
 *
 * {@code MainActivity} keeps only lifecycle and USB receiver reactions.
 */
final class GalleryController implements MainScreen.Listener, GbcamOperationRunner.Callback,
        SettingsDialog.Host, PhotoDetailDialog.Host {
    private static final String TAG = "GbcamApp";
    static final int REQUEST_IMPORT_SAVE = 1;
    static final int REQUEST_EXPORT_SAVE = 2;

    private final Activity activity;
    private final MainScreen screen;
    private final UsbDeviceController usb;
    private final GbcamOperationRunner operationRunner;
    private final AppSettings settings;
    private final ManualMergeStore mergeStore;
    private final BackupRepository backups;
    private final GalleryPipeline pipeline;
    private final PaletteCatalog palettes;
    private final ExecutorService previewExecutor;
    private final ExecutorService backgroundExecutor;
    private final AtomicInteger recolorGeneration = new AtomicInteger(0);
    private final Runnable onCameraLoaded;

    private int paletteIndex;
    private boolean destroyed;
    private boolean autoLoadAttempted;
    private File pendingSaveExport;

    GalleryController(Activity activity, MainScreen screen, UsbDeviceController usb,
            GbcamOperationRunner operationRunner, AppSettings settings, ManualMergeStore mergeStore,
            BackupRepository backups, GalleryPipeline pipeline, PaletteCatalog palettes,
            ExecutorService previewExecutor, ExecutorService backgroundExecutor,
            int paletteIndex, Runnable onCameraLoaded) {
        this.activity = activity;
        this.screen = screen;
        this.usb = usb;
        this.operationRunner = operationRunner;
        this.settings = settings;
        this.mergeStore = mergeStore;
        this.backups = backups;
        this.pipeline = pipeline;
        this.palettes = palettes;
        this.previewExecutor = previewExecutor;
        this.backgroundExecutor = backgroundExecutor;
        this.paletteIndex = paletteIndex;
        this.onCameraLoaded = onCameraLoaded;
    }

    /** Marks the controller destroyed so in-flight async results are dropped. */
    void dispose() {
        destroyed = true;
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
            Toast.makeText(activity,
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
                    activity, gallery, palettes.colorsFor(gallery.paletteIndex), settings.exportDeleted());
            onLog("Saved " + result.imageCount + " " + plural(result.imageCount, "photo") + ":\n" + result.summary());
        } catch (Exception e) {
            onLog("Save failed: " + e.toString());
        }
    }

    @Override
    public void onShareSelectedRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null || gallery.selectedCount() == 0) return;
        ShareSizeDialog.show(activity, settings, scale -> doShareSelected(gallery, scale));
    }

    private void doShareSelected(GalleryState gallery, int scale) {
        int selected = gallery.selectedCount();
        if (selected == 0) return;
        try {
            PhotoExporter.ExportResult result = PhotoExporter.exportSelectedScaled(
                    activity, gallery, palettes.colorsFor(gallery.paletteIndex),
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
            activity.startActivity(Intent.createChooser(share, "Share photos"));
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
        new BackupPickerDialog(activity, screen, backups, previewExecutor, this::postToUi, this::loadBackupSave)
                .show(saves);
    }

    @Override
    public void onImportSaveRequested() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        activity.startActivityForResult(intent, REQUEST_IMPORT_SAVE);
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
        activity.startActivityForResult(intent, REQUEST_EXPORT_SAVE);
    }

    @Override
    public void onSettingsRequested() {
        new SettingsDialog(activity, settings, screen, this).show();
    }

    @Override
    public void shareLogs() {
        shareCurrentLogs();
    }

    @Override
    public void applyPaletteIcon() {
        runInBackground(() -> PaletteIcons.applyIfChanged(activity, paletteIndex));
    }

    @Override
    public void recolorGallery() {
        recolorCachedGallery(paletteIndex, false);
    }

    @Override
    public void onAboutRequested() {
        new AboutDialog(activity, screen, this::onLog).show(screen.gallery(), usb.device() != null);
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
                            operationRunner.deletePhotos(usb.manager(), usb.device(), current, dumpsDir(), paletteIndex, this);
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
            UiStyle.confirmDialog(activity, "Delete selected?", detail, "Delete", doDelete);
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
                        operationRunner.recoverPhotos(usb.manager(), usb.device(), current, dumpsDir(), paletteIndex, this);
                });
            } else {
                recoverSelectedFromCachedSave(g);
            }
        });
    }

    private void recoverSelectedFromCachedSave(GalleryState gallery) {
        String slots = gallery.selectedPhysicalSlotsCsv(true);
        if (slots.isEmpty()) return;
        Set<String> restoredSlots = selectedDeletedSlotKeys(gallery);
        screen.setBusy(true, "Recovering selected deleted photos...");
        runInBackground(() -> {
            try {
                String json = NativeGbcam.recoverPhotosFromSave(
                        gallery.savePath,
                        dumpsDir().getAbsolutePath(),
                        slots,
                        paletteIndex,
                        message -> postToUi(() -> {
                            onLog(message);
                            screen.updateBusyProgress(message);
                        }));
                GalleryState recovered = GalleryState.fromJson(json);
                postToUi(() -> {
                    settings.removeLocallyDeletedSlots(restoredSlots);
                    GalleryState shown = pipeline.process(recovered, true);
                    mergeStore.load();
                    shown = mergeStore.inject(shown);
                    screen.showGallery(shown);
                    screen.setBusy(false, null);
                    onLog("Recovered selected deleted photos from cached save.");
                });
            } catch (Exception e) {
                postToUi(() -> {
                    onError("Error: " + e.toString());
                    screen.setBusy(false, null);
                });
            }
        });
    }

    private static Set<String> selectedDeletedSlotKeys(GalleryState gallery) {
        Set<String> slots = new HashSet<>();
        for (GalleryPhoto photo : gallery.photos) {
            if (photo.selected && photo.isDeletedAlbumPhoto() && !photo.mergedRgb) {
                slots.add(String.valueOf(photo.physicalSlot));
            }
        }
        return slots;
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
        new PhotoDetailDialog(activity, screen, settings, pipeline, previewExecutor, this::postToUi,
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
        onCameraLoaded.run();
        int loaded = gallery.photos.size();
        onLog("Loaded " + loaded + " camera " + (loaded == 1 ? "photo." : "photos."));
        if (gallery.validationErrors > 0 || gallery.validationWarnings > 0) {
            onLog("Save validation: " + gallery.validationErrors + " error(s), "
                    + gallery.validationWarnings + " warning(s).");
        }
    }

    private void confirmOrRun(String title, String message, String action, Runnable runnable) {
        if (!settings.confirmAlbumWrites()) {
            runnable.run();
            return;
        }
        UiStyle.confirmDialog(activity, title, message, action, runnable);
    }

    private void runInBackground(Runnable action) {
        backgroundExecutor.execute(action);
    }

    private void postToUi(Runnable action) {
        activity.runOnUiThread(() -> {
            if (!destroyed) {
                action.run();
            }
        });
    }

    /** Loads the camera automatically once per session if no gallery is shown yet. */
    void autoLoadCamera() {
        if (autoLoadAttempted || screen.gallery() != null) {
            return;
        }
        autoLoadAttempted = true;
        onLog("Loading camera automatically...");
        usb.withPermission(() -> operationRunner.loadGallery(usb.manager(), usb.device(), dumpsDir(), paletteIndex, this));
    }

    private File dumpsDir() {
        return AppFiles.dumpsDir(activity);
    }

    private File appFilesDir(String type) {
        return AppFiles.appFilesDir(activity, type);
    }

    void loadCachedGallery() {
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
        if (photo.isManualMerge()) {
            applyManualMergeChanges(photo, order, algorithm, null);
        } else {
            // Auto-merged: order is fixed by detection; only algorithm can change.
            settings.saveMergeAlgorithmOverride(photo, algorithm);
            recolorCachedGallery(paletteIndex, false);
        }
    }

    @Override
    public void applyDetailChangesThenShare(GalleryPhoto photo, String order, String algorithm) {
        if (photo.isManualMerge()) {
            // The committed manual merge is a brand-new photo object; share it directly.
            applyManualMergeChanges(photo, order, algorithm, this::shareSinglePhoto);
        } else {
            int start = photo.mergedSourceStartDisplayIndex;
            int count = photo.mergedSourceCount;
            settings.saveMergeAlgorithmOverride(photo, algorithm);
            // Recolor regenerates the auto-merge in place; share whatever auto-merge now
            // covers this source range (there is exactly one auto-merge per range).
            recolorCachedGallery(paletteIndex, false, () -> {
                GalleryPhoto updated = findAutoMerge(start, count);
                if (updated != null) shareSinglePhoto(updated);
            });
        }
    }

    private GalleryPhoto findAutoMerge(int sourceStart, int sourceCount) {
        GalleryState g = screen.gallery();
        if (g == null) return null;
        for (GalleryPhoto p : g.photos) {
            if (p.mergedRgb && !p.isManualMerge()
                    && p.mergedSourceStartDisplayIndex == sourceStart
                    && p.mergedSourceCount == sourceCount) {
                return p;
            }
        }
        return null;
    }

    private void shareCurrentLogs() {
        String logText = screen.getLogs();
        if (logText == null || logText.trim().isEmpty()) {
            Toast.makeText(activity, "No logs to share.", Toast.LENGTH_SHORT).show();
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
            activity.startActivity(Intent.createChooser(share, "Share logs"));
        } catch (Exception e) {
            onLog("Share logs failed: " + e);
        }
    }

    @Override
    public void shareSinglePhoto(GalleryPhoto photo) {
        if (screen.gallery() == null) return;
        ShareSizeDialog.show(activity, settings, scale -> {
            GalleryState gallery = screen.gallery();
            GalleryPhoto target = findCurrentPhoto(photo, gallery);
            if (target == null) {
                onLog("Share failed: photo is no longer in the current gallery.");
                return;
            }
            // Save and temporarily replace selection so exportSelectedScaled picks up just this photo.
            boolean[] prev = new boolean[gallery.photos.size()];
            for (int i = 0; i < gallery.photos.size(); i++) {
                prev[i] = gallery.photos.get(i).selected;
                gallery.photos.get(i).selected = (gallery.photos.get(i) == target);
            }
            doShareSelected(gallery, scale);
            for (int i = 0; i < gallery.photos.size(); i++) gallery.photos.get(i).selected = prev[i];
            screen.showGallery(gallery);
        });
    }

    private static GalleryPhoto findCurrentPhoto(GalleryPhoto photo, GalleryState gallery) {
        if (photo == null || gallery == null) return null;
        for (GalleryPhoto current : gallery.photos) {
            if (current == photo) {
                return current;
            }
        }
        for (GalleryPhoto current : gallery.photos) {
            if (samePhotoIdentity(current, photo)) {
                return current;
            }
        }
        return null;
    }

    private static boolean samePhotoIdentity(GalleryPhoto left, GalleryPhoto right) {
        if (left.mergedRgb || right.mergedRgb) {
            return left.mergedRgb == right.mergedRgb
                    && left.path != null
                    && left.path.equals(right.path);
        }
        if (left.isAlbumBacked() && right.isAlbumBacked()) {
            return left.physicalSlot == right.physicalSlot;
        }
        return left.displayIndex == right.displayIndex
                && left.name != null
                && left.name.equals(right.name);
    }

    private void recolorCachedGallery(int paletteIndex, boolean logChange) {
        recolorCachedGallery(paletteIndex, logChange, null);
    }

    private void recolorCachedGallery(int paletteIndex, boolean logChange, Runnable onApplied) {
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
                    if (onApplied != null) onApplied.run();
                });
            } catch (Exception e) {
                postToUi(() -> {
                    if (recolorGeneration.get() != generation) return;
                    onLog("Palette change failed: " + e);
                });
            }
        });
    }

    private void applyManualMergeChanges(GalleryPhoto photo, String order, String algorithm,
            java.util.function.Consumer<GalleryPhoto> onApplied) {
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
                if (onApplied != null) onApplied.accept(updated);
            });
        });
    }

    /** Handles the import/export-save document picker results routed from the Activity. */
    void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
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

    private static void sortByDisplayIndex(List<GalleryPhoto> photos) {
        Collections.sort(photos, (left, right) -> compareInts(left.displayIndex, right.displayIndex));
    }

    private static int compareInts(int left, int right) {
        if (left < right) return -1;
        if (left > right) return 1;
        return 0;
    }
}
