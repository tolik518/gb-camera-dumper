package fyi.r0.gbxcam;

import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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

public class MainActivity extends Activity implements MainScreen.Listener, GbcamOperationRunner.Callback {
    private static final String TAG = "GbcamApp";
    private static final String ACTION_USB_PERMISSION = "fyi.r0.gbxcam.USB_PERMISSION";
    private static final int REQUEST_IMPORT_SAVE = 1;
    private static final int REQUEST_EXPORT_SAVE = 2;

    private UsbManager usbManager;
    private UsbDevice selectedDevice;
    private AppSettings settings;
    private Runnable pendingAction;
    private MainScreen screen;
    private GbcamOperationRunner operationRunner;
    private boolean autoLoadAttempted;
    private int paletteIndex;
    private String[] paletteLabels;
    private int[][] paletteColors;
    private File pendingSaveExport;
    private final ExecutorService previewExecutor = Executors.newFixedThreadPool(3);
    private final AtomicInteger recolorGeneration = new AtomicInteger(0);
    private final Map<String, Boolean> emptyImageCache = new HashMap<>();
    private final List<GalleryPhoto> manualMerges = new ArrayList<>();

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
                pendingAction = null;
                return;
            }

            selectedDevice = device;
            onLog("USB permission granted.");
            Runnable action = pendingAction;
            pendingAction = null;
            if (action != null) action.run();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        operationRunner = new GbcamOperationRunner();
        settings = new AppSettings(this);
        int defaultPaletteIndex = NativeGbcam.defaultPaletteIndex();
        paletteLabels = paletteLabels();
        paletteColors = paletteColors(paletteLabels.length);
        paletteIndex = settings.paletteIndex(defaultPaletteIndex);
        paletteIndex = Math.max(0, Math.min(paletteIndex, paletteLabels.length - 1));
        screen = new MainScreen(
                this,
                this,
                paletteLabels,
                paletteColors,
                settings.paletteFavorites(paletteLabels),
                settings.recentPalettes(paletteLabels),
                defaultPaletteIndex);
        screen.setBitmapExecutor(previewExecutor);
        screen.setPaletteIndex(paletteIndex);
        screen.setLogsVisibleFromSettings(settings.showLogs());
        setContentView(screen.view());

        registerUsbReceiver();
        onLog("Rust core loaded: " + NativeGbcam.version());
        if (refreshDevice() && settings.autoLoad()) {
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
        unregisterReceiver(usbReceiver);
        operationRunner.shutdown();
        previewExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (refreshDevice() && settings.autoLoad() && !autoLoadAttempted) {
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
        sources.sort(Comparator.comparingInt(p -> p.displayIndex));
        String order = count == 3 ? settings.rgb3Order() : settings.rgb4Order();
        screen.setBusy(true, "Merging " + count + " photos...");
        new Thread(() -> {
            GalleryPhoto merged = RgbMergeDetector.manualMerge(
                    sources.toArray(new GalleryPhoto[0]),
                    count, order,
                    new File(gallery.outputDir),
                    settings.mergeAlgorithm());
            runOnUiThread(() -> {
                screen.setBusy(false, null);
                if (merged == null) {
                    onLog("Manual merge failed.");
                    return;
                }
                List<GalleryPhoto> photos = new ArrayList<>(gallery.photos);
                int insertAt = -1;
                for (int i = 0; i < photos.size(); i++) {
                    GalleryPhoto p = photos.get(i);
                    if (p.selected && !p.deleted && !p.mergedRgb && p.physicalSlot >= 0) {
                        insertAt = i;
                    }
                }
                photos.add(insertAt + 1, merged);
                addToManualMerges(merged);
                screen.showGallery(new GalleryState(
                        gallery.connected, gallery.savePath, gallery.outputDir,
                        gallery.paletteIndex, gallery.paletteName,
                        gallery.validationErrors, gallery.validationWarnings,
                        photos));
                onLog("Merged " + count + " photos → " + merged.name);
            });
        }).start();
    }

    @Override
    public void onLoadRequested() {
        runWithPermission(() -> operationRunner.loadGallery(usbManager, selectedDevice, dumpsDir(), paletteIndex, this));
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
        int selected = gallery.selectedCount();
        try {
            PhotoExporter.ExportResult result = PhotoExporter.exportSelected(
                    this, gallery, paletteColorsForIndex(gallery.paletteIndex), settings.exportDeleted());
            onLog("Saved " + selected + " photo(s):\n" + result.summary());
        } catch (Exception e) {
            onLog("Save failed: " + e.toString());
        }
    }

    @Override
    public void onShareSelectedRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null || gallery.selectedCount() == 0) {
            return;
        }
        int selected = gallery.selectedCount();
        try {
            PhotoExporter.ExportResult result = PhotoExporter.exportSelected(
                    this, gallery, paletteColorsForIndex(gallery.paletteIndex), settings.exportDeleted());
            if (result.imageUris.isEmpty()) {
                onLog("Share unavailable for this Android version. Saved instead:\n" + result.summary());
                return;
            }

            Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
            share.setType("image/png");
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, result.imageUris);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share selected photos"));
            onLog("Prepared " + selected + " photo(s) for sharing.");
        } catch (Exception e) {
            onLog("Share failed: " + e.toString());
        }
    }

    @Override
    public void onBackupsRequested() {
        File[] saves = dumpsDir().listFiles((dir, name) -> name.toLowerCase().endsWith(".sav"));
        if (saves == null || saves.length == 0) {
            onLog("No save backups found.");
            return;
        }
        Arrays.sort(saves, Comparator.comparingLong(File::lastModified).reversed());
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

        boolean cartridgeConnected = GbxCartDevices.find(usbManager) != null;

        String detail;
        if (activeCount > 0 && manualCount > 0) {
            detail = "Remove " + activeCount + " photo(s) and " + manualCount + " merged image(s) from the local gallery?"
                    + (cartridgeConnected ? " You'll be asked about the cartridge next." : "");
        } else if (activeCount > 0) {
            detail = "Remove " + activeCount + " photo(s) from the local gallery?"
                    + (cartridgeConnected ? " You'll be asked about the cartridge next." : "");
        } else {
            detail = "Remove " + manualCount + " merged image(s) from this device?";
        }

        UiStyle.confirmDialog(this, "Delete selected?", detail, "Delete", () -> {
            GalleryState g = screen.gallery();
            if (g == null) return;
            final GalleryState forCartridge = g;

            GalleryState after = g.selectedManualMergeCount() > 0
                    ? removeSelectedManualMerges(g) : g;
            if (activeCount > 0) {
                after = removeSelectedActiveLocally(after);
            }
            screen.showGallery(after);

            if (activeCount == 0 || !cartridgeConnected) return;

            UiStyle.confirmDialog(this, "Delete from cartridge?",
                    "Also delete " + activeCount + " photo(s) from the camera cartridge?"
                            + " A save backup is kept in the app dumps folder.",
                    "Delete from cartridge",
                    () -> runWithPermission(() ->
                            operationRunner.deletePhotos(usbManager, selectedDevice, forCartridge, dumpsDir(), paletteIndex, MainActivity.this)));
        });
    }

    private GalleryState removeSelectedActiveLocally(GalleryState gallery) {
        List<GalleryPhoto> photos = new ArrayList<>();
        Set<String> deletedSlots = new HashSet<>();
        for (GalleryPhoto p : gallery.photos) {
            if (!p.selected || p.deleted || p.mergedRgb || p.physicalSlot < 0) {
                photos.add(p);
            } else {
                deletedSlots.add(String.valueOf(p.physicalSlot));
            }
        }
        if (!deletedSlots.isEmpty()) {
            settings.addLocallyDeletedSlots(deletedSlots);
        }
        return new GalleryState(gallery.connected, gallery.savePath, gallery.outputDir,
                gallery.paletteIndex, gallery.paletteName,
                gallery.validationErrors, gallery.validationWarnings, photos);
    }

    private GalleryState filterLocallyDeletedPhotos(GalleryState gallery) {
        Set<String> deleted = settings.locallyDeletedSlots();
        if (deleted.isEmpty()) return gallery;
        List<GalleryPhoto> photos = new ArrayList<>();
        for (GalleryPhoto p : gallery.photos) {
            if (p.physicalSlot >= 0 && deleted.contains(String.valueOf(p.physicalSlot))) continue;
            photos.add(p);
        }
        if (photos.size() == gallery.photos.size()) return gallery;
        return new GalleryState(gallery.connected, gallery.savePath, gallery.outputDir,
                gallery.paletteIndex, gallery.paletteName,
                gallery.validationErrors, gallery.validationWarnings, photos);
    }

    private GalleryState removeSelectedManualMerges(GalleryState gallery) {
        List<GalleryPhoto> photos = new ArrayList<>();
        for (GalleryPhoto p : gallery.photos) {
            if (p.selected && p.mergedRgb && p.path.contains("rgb-merged-manual")) {
                for (int i = manualMerges.size() - 1; i >= 0; i--) {
                    if (manualMerges.get(i).path.equals(p.path)) { manualMerges.remove(i); break; }
                }
                new File(p.path).delete();
            } else {
                photos.add(p);
            }
        }
        saveManualMerges();
        return new GalleryState(gallery.connected, gallery.savePath, gallery.outputDir,
                gallery.paletteIndex, gallery.paletteName,
                gallery.validationErrors, gallery.validationWarnings, photos);
    }

    @Override
    public void onRecoverSelectedRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null || gallery.selectedDeletedCount() == 0) {
            return;
        }

        confirmOrRun(
                "Recover deleted photos?",
                "This restores " + gallery.selectedDeletedCount() + " deleted slot(s) into the camera album. A save backup is kept in the app dumps folder.",
                "Recover",
                () -> runWithPermission(() -> {
                    GalleryState g = screen.gallery();
                    if (g != null) operationRunner.recoverPhotos(usbManager, selectedDevice, g, dumpsDir(), paletteIndex, this);
                }));
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
                () -> runWithPermission(() -> {
                    GalleryState g = screen.gallery();
                    if (g != null) operationRunner.reorderPhotos(usbManager, selectedDevice, g, dumpsDir(), paletteIndex, csv, "Moving selected photos to front...", this);
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
                () -> runWithPermission(() -> {
                    GalleryState g = screen.gallery();
                    if (g != null) operationRunner.reorderPhotos(usbManager, selectedDevice, g, dumpsDir(), paletteIndex, csv, "Compacting album order...", this);
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
                () -> runWithPermission(() -> {
                    GalleryState g = screen.gallery();
                    if (g != null) operationRunner.reorderPhotos(usbManager, selectedDevice, g, dumpsDir(), paletteIndex, csv, "Clearing album order...", this);
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
        this.paletteIndex = Math.max(0, Math.min(paletteIndex, paletteLabels.length - 1));
        settings.savePaletteIndex(this.paletteIndex);
        GalleryState current = screen.gallery();
        if (current != null) {
            settings.rememberBackupPalette(new File(current.savePath), this.paletteIndex);
        }
        settings.rememberRecentPalette(paletteLabels, this.paletteIndex);
        screen.setRecentPalettes(settings.recentPalettes(paletteLabels));
        onLog("Palette changed: " + paletteLabel(this.paletteIndex));
    }

    @Override
    public void onPaletteFavoriteToggled(int paletteIndex) {
        if (paletteIndex < 0 || paletteIndex >= paletteLabels.length) {
            return;
        }
        boolean favorite = settings.togglePaletteFavorite(paletteLabels, paletteIndex);
        screen.setPaletteFavorite(paletteIndex, favorite);
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
        settings.clearLocallyDeletedSlots();
        gallery = applyAutoRgbMerge(gallery);
        loadManualMerges();
        gallery = injectManualMerges(gallery);
        rememberBackupPalette(new File(gallery.savePath), gallery.paletteIndex);
        screen.showGallery(gallery);
        onLog("Loaded " + gallery.photos.size() + " camera photo(s).");
        if (gallery.validationErrors > 0 || gallery.validationWarnings > 0) {
            onLog("Save validation: " + gallery.validationErrors + " error(s), "
                    + gallery.validationWarnings + " warning(s).");
        }
    }

    private void showStartupPopup() {
        Dialog dialog = UiStyle.baseDialog(this);
        UiStyle.Palette colors = UiStyle.palette(this);
        int accent = screen.accentColor();

        LinearLayout content = UiStyle.dialog(this, dialog, "How to connect", "Game Boy Camera + GBxCart RW + phone");

        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        steps.setPadding(0, dp(12), 0, 0);
        steps.addView(connectionStep("1", "Insert the Game Boy Camera cartridge into GBxCart RW.", colors, accent));
        steps.addView(connectionStep("2", "Connect GBxCart RW to your phone via USB (USB-C to USB-C cable).", colors, accent));
        steps.addView(connectionStep("3", "Tap \"Load Camera\".", colors, accent));
        content.addView(steps, matchWidthWrapContent());

        CheckBox dontShow = UiStyle.settingsCheckBox(
                this, "Don't show on startup", "", false, accent);
        View cbRow = UiStyle.settingsRow(this, dontShow);
        LinearLayout.LayoutParams cbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        cbParams.setMargins(0, dp(12), 0, 0);
        content.addView(cbRow, cbParams);

        Button loadCamera = UiStyle.button(this, "Load Camera", accent, colors.surfaceRaised, accent);
        loadCamera.setOnClickListener(v -> {
            if (dontShow.isChecked()) {
                settings.saveShowStartupPopup(false);
            }
            dialog.dismiss();
            onLoadRequested();
        });
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        okParams.setMargins(0, dp(10), 0, 0);
        content.addView(loadCamera, okParams);

        dialog.setContentView(content);
        UiStyle.sizeDialog(dialog, this, 32, 480);
        dialog.show();
    }

    private View connectionStep(String number, String text, UiStyle.Palette colors, int accent) {
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

        TextView label = new TextView(this);
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

        LinearLayout content = UiStyle.dialog(this, dialog, "Settings", "");
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
                    new Thread(() -> PaletteIcons.applyIfChanged(
                            MainActivity.this, paletteIndex)).start();
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
        runWithPermission(() -> operationRunner.loadGallery(usbManager, selectedDevice, dumpsDir(), paletteIndex, this));
    }

    private void runWithPermission(Runnable action) {
        if (!refreshDevice()) {
            return;
        }

        if (!usbManager.hasPermission(selectedDevice)) {
            pendingAction = action;
            onLog("Requesting USB permission...");
            usbManager.requestPermission(selectedDevice, permissionIntent());
            return;
        }

        action.run();
    }

    private File dumpsDir() {
        return new File(appFilesDir(null), "dumps");
    }

    private File appFilesDir(String type) {
        File dir = getExternalFilesDir(type);
        if (dir != null) {
            return dir;
        }
        if (type == null) {
            return getFilesDir();
        }
        return new File(getFilesDir(), type);
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
            gallery = filterLocallyDeletedPhotos(gallery);
            gallery = applyAutoRgbMerge(gallery);
            loadManualMerges();
            gallery = injectManualMerges(gallery);
            rememberBackupPalette(save, paletteIndex);
            screen.showGallery(gallery);
            onLog("Loaded cached gallery from:\n" + save.getAbsolutePath());
        } catch (Exception e) {
            onLog("Cached gallery load failed: " + e.toString());
        }
    }

    private void loadBackupSave(File save) {
        try {
            File current = new File(dumpsDir(), "GAMEBOYCAMERA.sav");
            int backupPalette = backupPaletteIndex(save);
            paletteIndex = backupPalette;
            settings.savePaletteIndex(paletteIndex);
            if (!save.getCanonicalPath().equals(current.getCanonicalPath())) {
                PhotoExporter.copyFile(save, current);
            }
            rememberBackupPalette(current, paletteIndex);
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
            GalleryPhoto[] photos = backupPreviewPhotos(save);
            runOnUiThread(() -> {
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

    private GalleryPhoto[] backupPreviewPhotos(File save) {
        GalleryPhoto[] preview = new GalleryPhoto[4];
        try {
            int backupPalette = backupPaletteIndex(save);
            File output = new File(
                    appFilesDir(null),
                    "backup-previews/" + safeFilePart(save.getName()) + "-" + save.lastModified() + "-p" + backupPalette);

            // Fast path: if preview PNGs already exist on disk, use them without re-decoding.
            if (output.isDirectory()) {
                GalleryPhoto[] cached = loadCachedPreviews(output);
                if (cached != null) return cached;
            }

            if (!output.mkdirs() && !output.isDirectory()) {
                return preview;
            }
            GalleryState gallery = GalleryState.fromJson(NativeGbcam.loadGalleryFromSave(
                    save.getAbsolutePath(),
                    output.getAbsolutePath(),
                    backupPalette));
            List<GalleryPhoto> active = new ArrayList<>();
            for (GalleryPhoto p : gallery.photos) {
                if (!p.deleted) active.add(p);
            }
            if (active.isEmpty()) {
                return preview;
            }

            int[] indices = backupPreviewIndices(active.size());
            for (int i = 0; i < indices.length; i++) {
                preview[i] = active.get(indices[i]);
            }
        } catch (Exception e) {
            Log.w(TAG, "Backup preview failed for " + save.getName(), e);
        }
        return preview;
    }

    private GalleryPhoto[] loadCachedPreviews(File dir) {
        List<File> found = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            File f = new File(dir, String.format(Locale.US, "IMG_PC%02d.png", i));
            if (f.exists() && !decodeAndCheckEmpty(f.getAbsolutePath())) {
                found.add(f);
            }
        }
        if (found.isEmpty()) return null;

        int[] indices = backupPreviewIndices(found.size());
        GalleryPhoto[] preview = new GalleryPhoto[4];
        for (int i = 0; i < Math.min(indices.length, preview.length); i++) {
            File f = found.get(indices[i]);
            preview[i] = new GalleryPhoto(f.getName(), f.getAbsolutePath(),
                    indices[i], indices[i], 128, 112, null, false, 0, false, true, "");
        }
        return preview;
    }

    private static int[] backupPreviewIndices(int count) {
        if (count <= 4) {
            int[] indices = new int[count];
            for (int i = 0; i < count; i++) {
                indices[i] = i;
            }
            return indices;
        }
        return new int[] {
                0,
                count / 3,
                (count * 2) / 3,
                count - 1
        };
    }

    private static String safeFilePart(String label) {
        String safe = label.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "save" : safe;
    }

    private int backupPaletteIndex(File save) {
        return settings.backupPaletteIndex(save, NativeGbcam.defaultPaletteIndex());
    }

    private void rememberBackupPalette(File save, int paletteIndex) {
        settings.rememberBackupPalette(save, paletteIndex);
    }

    private void showPhotoDetail(GalleryPhoto photo) {
        Dialog dialog = UiStyle.baseDialog(this);
        UiStyle.Palette colors = UiStyle.palette(this);
        int panel = colors.surface;
        int panelRaised = colors.surfaceRaised;
        int panelSoft = UiStyle.blend(colors.surfaceRaised, colors.surface, 0.45f);
        int border = colors.borderStrong;
        int borderSoft = colors.border;
        int textPrimary = colors.textPrimary;
        int textSecondary = colors.textSecondary;
        int textMuted = colors.textMuted;
        int danger = colors.danger;
        int accent = screen.accentColor();

        LinearLayout content = UiStyle.dialog(this, dialog, photoDetailTitle(photo), photo.name);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(12), 0, 0);
        statusRow.addView(detailChip(photo.deleted ? "Deleted" : photo.mergedRgb ? mergedPhotoTitle(photo) : "Original", photo.deleted ? danger : accent, panelSoft, photo.deleted ? danger : accent));
        String mergeKindChip = photo.mergedRgb
                ? (photo.path.contains("rgb-merged-manual") ? "Manually merged" : "Auto-merged")
                : (photo.copy ? "Copy" : "Camera photo");
        statusRow.addView(detailChip(mergeKindChip, textSecondary, panelSoft, borderSoft));
        if (photo.mergedRgb && photo.mergedAlgorithm != null && !photo.mergedAlgorithm.isEmpty()) {
            statusRow.addView(detailChip(RgbMergeDetector.algorithmShortLabel(photo.mergedAlgorithm), textSecondary, panelSoft, borderSoft));
        }
        if (!photo.mergedRgb) {
            statusRow.addView(detailChip(photo.metadataValid ? "Metadata OK" : "Check metadata", photo.metadataValid ? textSecondary : danger, panelSoft, photo.metadataValid ? borderSoft : danger));
        }
        content.addView(statusRow, statusParams);

        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        infoParams.setMargins(0, dp(8), 0, 0);
        infoRow.addView(detailChip(photo.mergedRgb ? mergedSourceLabel(photo) : "Album " + String.format("%02d", photo.displayIndex + 1), textMuted, panel, borderSoft));
        if (!photo.mergedRgb) {
            infoRow.addView(detailChip("Slot " + (photo.physicalSlot + 1), textMuted, panel, borderSoft));
        }
        infoRow.addView(detailChip(photo.width + "×" + photo.height, textMuted, panel, borderSoft));
        infoRow.addView(detailChip("Border " + photo.border, textMuted, panel, borderSoft));
        content.addView(infoRow, infoParams);

        if (!photo.ownerUserId.isEmpty()) {
            LinearLayout ownerRow = new LinearLayout(this);
            ownerRow.setOrientation(LinearLayout.HORIZONTAL);
            ownerRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams ownerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            ownerParams.setMargins(0, dp(8), 0, 0);
            ownerRow.addView(detailChip("Owner " + photo.ownerUserId, textMuted, panel, borderSoft));
            content.addView(ownerRow, ownerParams);
        }

        final String[] previewAlgo = { photo.mergedAlgorithm != null ? photo.mergedAlgorithm : "" };
        final boolean[] algoChanged = { false };
        final int[] previewGeneration = { 0 };

        FrameLayout imageMat = new FrameLayout(this);
        imageMat.setPadding(dp(8), dp(8), dp(8), dp(8));
        imageMat.setBackground(rounded(colors.logBackground, border, 12, 1));
        LinearLayout.LayoutParams matParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        matParams.setMargins(0, dp(12), 0, dp(12));

        ImageView image = new ImageView(this);
        Bitmap detailBitmap = PhotoRenderer.renderBitmap(photo, paletteColorsForIndex(paletteIndex));
        if (detailBitmap != null) {
            image.setImageBitmap(detailBitmap);
        } else {
            image.setImageURI(Uri.fromFile(new File(photo.path)));
        }
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAlpha(photo.deleted ? 0.86f : 1.0f);
        imageMat.addView(image, new android.widget.FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final ProgressBar[] progressRef = { null };

        if (photo.mergedRgb) {
            ProgressBar previewProgress = new ProgressBar(this);
            previewProgress.setIndeterminate(true);
            previewProgress.setVisibility(View.GONE);
            FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(48), dp(48));
            progressParams.gravity = Gravity.CENTER;
            imageMat.addView(previewProgress, progressParams);
            progressRef[0] = previewProgress;
        }

        content.addView(imageMat, matParams);

        if (photo.mergedRgb) {
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
            algoDropText.setText(RgbMergeDetector.algorithmLabel(previewAlgo[0]));
            algoField.addView(algoDropText, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            TextView algoArrow = new TextView(this);
            algoArrow.setText("▼");
            algoArrow.setTextSize(10);
            algoArrow.setTextColor(textSecondary);
            algoArrow.setGravity(Gravity.CENTER);
            algoArrow.setEnabled(false);
            FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(dp(28),
                    FrameLayout.LayoutParams.MATCH_PARENT);
            arrowParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            algoField.addView(algoArrow, arrowParams);

            algoField.setOnClickListener(v -> {
                boolean hasClear = photo.mergedSourceCount == 4;
                String[] ids    = RgbMergeDetector.compatibleAlgorithmIds(hasClear);
                String[] labels = RgbMergeDetector.compatibleAlgorithmLabels(hasClear);

                UiStyle.dropdown(this, algoField, labels, indexOf(ids, previewAlgo[0]), accent, which -> {
                    if (ids[which].equals(previewAlgo[0])) return;
                    previewAlgo[0] = ids[which];
                    algoChanged[0] = true;
                    algoDropText.setText(labels[which]);
                    runPreviewMerge(photo, previewAlgo[0], image, progressRef[0], previewGeneration);
                });
            });

            LinearLayout.LayoutParams algoFieldParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
            algoFieldParams.setMargins(0, 0, 0, dp(12));
            content.addView(algoField, algoFieldParams);
        }

        Button share = previewButton("Share", accent, panelRaised, accent);
        share.setOnClickListener(v -> shareSinglePhoto(photo));
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        content.addView(share, shareParams);

        dialog.setContentView(content);
        if (photo.mergedRgb) {
            boolean isManual = isManualMerge(photo);
            dialog.setOnDismissListener(d -> {
                if (algoChanged[0]) {
                    if (isManual) {
                        applyManualMergeAlgoChange(photo, previewAlgo[0]);
                    } else {
                        settings.saveMergeAlgorithmOverride(photo, previewAlgo[0]);
                        recolorCachedGallery(paletteIndex, false);
                    }
                }
            });
        }
        UiStyle.sizeDialog(dialog, this, 32, 560);
        dialog.show();
    }

    private void shareSinglePhoto(GalleryPhoto photo) {
        GalleryState gallery = screen.gallery();
        if (gallery == null) {
            return;
        }
        boolean[] previousSelection = new boolean[gallery.photos.size()];
        for (int i = 0; i < gallery.photos.size(); i++) {
            GalleryPhoto item = gallery.photos.get(i);
            previousSelection[i] = item.selected;
            item.selected = item == photo;
        }
        onShareSelectedRequested();
        for (int i = 0; i < gallery.photos.size(); i++) {
            gallery.photos.get(i).selected = previousSelection[i];
        }
        screen.showGallery(gallery);
    }

    private void runPreviewMerge(
            GalleryPhoto mergedPhoto,
            String algorithm,
            ImageView imageView,
            ProgressBar progressBar,
            int[] generation) {
        generation[0]++;
        int gen = generation[0];
        progressBar.setVisibility(View.VISIBLE);

        GalleryState gallery = screen.gallery();
        new Thread(() -> {
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
                        sourceForPreview, mergedPhoto.mergedKind, count, algorithm);
            } catch (Exception ignored) {
            }
            final Bitmap result = preview;
            runOnUiThread(() -> {
                if (gen != generation[0]) return;
                progressBar.setVisibility(View.GONE);
                if (result != null) {
                    int w = imageView.getWidth();
                    int h = imageView.getHeight();
                    Bitmap display = (w > 0 && h > 0)
                            ? Bitmap.createScaledBitmap(result, w, h, true)
                            : result;
                    imageView.setAdjustViewBounds(false);
                    imageView.setScaleType(ImageView.ScaleType.FIT_XY);
                    imageView.setImageBitmap(display);
                }
            });
        }).start();
    }

    private String photoDetailTitle(GalleryPhoto photo) {
        if (photo.mergedRgb) {
            return mergedPhotoTitle(photo);
        }
        return (photo.deleted ? "Deleted " : "Photo ") + String.format("%02d", photo.displayIndex + 1);
    }

    private String mergedPhotoTitle(GalleryPhoto photo) {
        return "Merged " + (photo.mergedKind == null || photo.mergedKind.isEmpty() ? "RGB" : photo.mergedKind);
    }

    private String mergedSourceLabel(GalleryPhoto photo) {
        int start = photo.mergedSourceStartDisplayIndex + 1;
        int end = start + Math.max(0, photo.mergedSourceCount - 1);
        return photo.mergedSourceCount > 0
                ? "Sources " + String.format("%02d-%02d", start, end)
                : "Sources";
    }

    private TextView detailChip(String text, int textColor, int fillColor, int strokeColor) {
        return UiStyle.chip(this, text, textColor, fillColor, strokeColor);
    }

    private Button previewButton(String text, int textColor, int fillColor, int strokeColor) {
        return UiStyle.button(this, text, textColor, fillColor, strokeColor);
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp, int strokeDp) {
        return UiStyle.rounded(this, fill, stroke, radiusDp, strokeDp);
    }

    private static LinearLayout.LayoutParams matchWidthWrapContent() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void recolorCachedGallery() {
        recolorCachedGallery(paletteIndex, true);
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
                gallery = filterLocallyDeletedPhotos(gallery);
                gallery = applyAutoRgbMerge(gallery);
                gallery = injectManualMerges(gallery);
                gallery.copySelectionFrom(previous);
                GalleryState finalGallery = gallery;
                runOnUiThread(() -> {
                    if (recolorGeneration.get() != generation) return;
                    screen.showGallery(finalGallery);
                    if (logChange) onLog("Palette changed: " + finalGallery.paletteName);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (recolorGeneration.get() != generation) return;
                    onLog("Palette change failed: " + e);
                });
            }
        });
    }

    private void addToManualMerges(GalleryPhoto m) {
        for (int i = 0; i < manualMerges.size(); i++) {
            GalleryPhoto e = manualMerges.get(i);
            if (e.mergedSourceStartDisplayIndex == m.mergedSourceStartDisplayIndex
                    && e.mergedSourceCount == m.mergedSourceCount) {
                manualMerges.set(i, m);
                saveManualMerges();
                return;
            }
        }
        manualMerges.add(m);
        saveManualMerges();
    }

    private void saveManualMerges() {
        try {
            JSONArray arr = new JSONArray();
            for (GalleryPhoto m : manualMerges) {
                JSONObject obj = new JSONObject();
                obj.put("name", m.name);
                obj.put("path", m.path);
                obj.put("displayIndex", m.displayIndex);
                obj.put("mergedKind", m.mergedKind != null ? m.mergedKind : "");
                obj.put("mergedSourceCount", m.mergedSourceCount);
                obj.put("mergedSourceStartDisplayIndex", m.mergedSourceStartDisplayIndex);
                obj.put("mergedAlgorithm", m.mergedAlgorithm != null ? m.mergedAlgorithm : "");
                arr.put(obj);
            }
            File dir = dumpsDir();
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(new File(dir, "manual-merges.json"))) {
                out.write(arr.toString().getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }

    private void loadManualMerges() {
        manualMerges.clear();
        File f = new File(dumpsDir(), "manual-merges.json");
        if (!f.isFile()) return;
        try {
            byte[] bytes = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                //noinspection ResultOfMethodCallIgnored
                in.read(bytes);
            }
            JSONArray arr = new JSONArray(new String(bytes, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String path = obj.getString("path");
                if (!new File(path).exists()) continue;
                manualMerges.add(new GalleryPhoto(
                        obj.getString("name"),
                        path,
                        obj.getInt("displayIndex"),
                        -1,
                        128, 112,
                        null,
                        false, 0, false, true, "",
                        true,
                        obj.optString("mergedKind", ""),
                        obj.optInt("mergedSourceCount", 0),
                        obj.optInt("mergedSourceStartDisplayIndex", -1),
                        obj.optString("mergedAlgorithm", "")));
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isManualMerge(GalleryPhoto photo) {
        for (GalleryPhoto m : manualMerges) {
            if (m.path.equals(photo.path)) return true;
        }
        return false;
    }

    private GalleryState injectManualMerges(GalleryState gallery) {
        if (manualMerges.isEmpty()) return gallery;
        List<GalleryPhoto> photos = null;
        for (GalleryPhoto m : manualMerges) {
            if (!new File(m.path).exists()) continue;
            int endIdx = m.mergedSourceStartDisplayIndex + m.mergedSourceCount - 1;
            int insertAt = -1;
            boolean alreadyPresent = false;
            List<GalleryPhoto> list = photos != null ? photos : gallery.photos;
            for (int i = 0; i < list.size(); i++) {
                GalleryPhoto p = list.get(i);
                if (p.mergedRgb && p.path.equals(m.path)) { alreadyPresent = true; break; }
                if (!p.mergedRgb && !p.deleted && p.displayIndex == endIdx) insertAt = i;
            }
            if (alreadyPresent || insertAt < 0) continue;
            if (photos == null) photos = new ArrayList<>(gallery.photos);
            photos.add(insertAt + 1, m);
        }
        if (photos == null) return gallery;
        return new GalleryState(gallery.connected, gallery.savePath, gallery.outputDir,
                gallery.paletteIndex, gallery.paletteName,
                gallery.validationErrors, gallery.validationWarnings, photos);
    }

    private void applyManualMergeAlgoChange(GalleryPhoto photo, String algorithm) {
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
        sources.sort(Comparator.comparingInt(p -> p.displayIndex));
        String order = count == 3 ? settings.rgb3Order() : settings.rgb4Order();
        new Thread(() -> {
            GalleryPhoto updated = RgbMergeDetector.manualMerge(
                    sources.toArray(new GalleryPhoto[0]),
                    count, order, new File(gallery.outputDir), algorithm);
            runOnUiThread(() -> {
                if (updated == null) { onLog("Merge algo update failed."); return; }
                addToManualMerges(updated);
                List<GalleryPhoto> photos = new ArrayList<>(gallery.photos);
                for (int i = 0; i < photos.size(); i++) {
                    if (photos.get(i).path.equals(photo.path)) { photos.set(i, updated); break; }
                }
                screen.showGallery(new GalleryState(gallery.connected, gallery.savePath,
                        gallery.outputDir, gallery.paletteIndex, gallery.paletteName,
                        gallery.validationErrors, gallery.validationWarnings, photos));
                onLog("Merge algorithm updated: " + RgbMergeDetector.algorithmShortLabel(algorithm));
            });
        }).start();
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
            onLog("Auto-merged " + added + " RGB set(s).");
        }
        return merged;
    }

    private GalleryState filterEmptyDeletedPhotos(GalleryState gallery) {
        List<GalleryPhoto> filtered = null;
        for (int i = 0; i < gallery.photos.size(); i++) {
            GalleryPhoto photo = gallery.photos.get(i);
            if (photo.deleted && isEmptyImage(photo)) {
                if (filtered == null) {
                    filtered = new ArrayList<>(gallery.photos.subList(0, i));
                }
            } else if (filtered != null) {
                filtered.add(photo);
            }
        }
        if (filtered == null) return gallery;
        return new GalleryState(
                gallery.connected, gallery.savePath, gallery.outputDir,
                gallery.paletteIndex, gallery.paletteName,
                gallery.validationErrors, gallery.validationWarnings,
                filtered);
    }

    private boolean isEmptyImage(GalleryPhoto photo) {
        Boolean cached = emptyImageCache.get(photo.path);
        if (cached != null) return cached;
        boolean empty = decodeAndCheckEmpty(photo.path);
        emptyImageCache.put(photo.path, empty);
        return empty;
    }

    private static boolean decodeAndCheckEmpty(String path) {
        Bitmap bmp = BitmapFactory.decodeFile(path);
        if (bmp == null) return true;
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        bmp.recycle();
        if (pixels.length == 0) return true;
        int first = pixels[0];
        for (int p : pixels) {
            if (p != first) return false;
        }
        return true;
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
            return;
        }

        Uri uri = data.getData();
        try {
            if (requestCode == REQUEST_IMPORT_SAVE) {
                File target = new File(dumpsDir(), "GAMEBOYCAMERA.sav");
                File importCheck = new File(appFilesDir(null), "import-check.sav");
                if (!dumpsDir().mkdirs() && !dumpsDir().isDirectory()) {
                    throw new Exception("Could not create dumps directory.");
                }
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(importCheck)) {
                    if (in == null) {
                        throw new Exception("Could not open imported save.");
                    }
                    PhotoExporter.copyStream(in, out);
                }
                NativeGbcam.loadGalleryFromSave(
                        importCheck.getAbsolutePath(),
                        dumpsDir().getAbsolutePath(),
                        paletteIndex);
                PhotoExporter.copyFile(importCheck, target);
                if (!importCheck.delete()) {
                    Log.w(TAG, "Could not delete import check file: " + importCheck);
                }
                loadCachedGallery();
                onLog("Imported save:\n" + target.getAbsolutePath());
            } else if (requestCode == REQUEST_EXPORT_SAVE && pendingSaveExport != null) {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        throw new Exception("Could not open export target.");
                    }
                    PhotoExporter.copyToStream(pendingSaveExport, out);
                }
                onLog("Exported save file.");
                pendingSaveExport = null;
            }
        } catch (Exception e) {
            onLog("File operation failed: " + e.toString());
        }
    }

    private static String[] paletteLabels() {
        String labels = NativeGbcam.paletteLabels();
        if (labels == null || labels.isEmpty()) {
            return new String[] { "Monochrome - Grayscale" };
        }
        return labels.split("\\n");
    }

    private static int[][] paletteColors(int expectedCount) {
        String colors = NativeGbcam.paletteColors();
        if (colors == null || colors.isEmpty()) {
            return fallbackPaletteColors(expectedCount);
        }

        String[] rows = colors.split("\\n");
        int[][] parsed = new int[expectedCount][];
        for (int i = 0; i < expectedCount; i++) {
            parsed[i] = i < rows.length ? parsePaletteRow(rows[i]) : fallbackPaletteColors(1)[0];
        }
        return parsed;
    }

    private static int[] parsePaletteRow(String row) {
        String[] parts = row.split(",");
        int[] colors = new int[] {
                0xFFFFFFFF,
                0xFFB0B0B0,
                0xFF686868,
                0xFF000000
        };
        for (int i = 0; i < Math.min(parts.length, colors.length); i++) {
            try {
                colors[i] = (int) (0xFF000000L | Long.parseLong(parts[i], 16));
            } catch (NumberFormatException ignored) {
            }
        }
        return colors;
    }

    private String paletteLabel(int index) {
        if (paletteLabels == null || paletteLabels.length == 0) {
            return "Monochrome - Grayscale";
        }
        return paletteLabels[Math.max(0, Math.min(index, paletteLabels.length - 1))];
    }

    private int[] paletteColorsForIndex(int index) {
        if (paletteColors != null && index >= 0 && index < paletteColors.length
                && paletteColors[index] != null && paletteColors[index].length > 0) {
            return paletteColors[index];
        }
        return fallbackPaletteColors(1)[0];
    }

    private static int[][] fallbackPaletteColors(int count) {
        int[][] colors = new int[Math.max(1, count)][];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = new int[] {
                    0xFFFFFFFF,
                    0xFFB0B0B0,
                    0xFF686868,
                    0xFF000000
            };
        }
        return colors;
    }

    private int dp(int value) {
        return UiStyle.dp(this, value);
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
