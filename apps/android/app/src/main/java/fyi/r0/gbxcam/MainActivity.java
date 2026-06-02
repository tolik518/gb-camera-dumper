package fyi.r0.gbxcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity implements MainScreen.Listener, GbcamOperationRunner.Callback {
    private static final String TAG = "GbcamApp";
    private static final String ACTION_USB_PERMISSION = "fyi.r0.gbxcam.USB_PERMISSION";
    private static final String PREFS = "gbxcam-viewer";
    private static final String KEY_PALETTE_INDEX = "palette-index";
    private static final String KEY_PALETTE_FAVORITES = "palette-favorites";
    private static final String KEY_PALETTE_RECENT = "palette-recent";
    private static final String KEY_BACKUP_PALETTE_PREFIX = "backup-palette:";
    private static final String KEY_AUTO_LOAD_CAMERA = "auto-load-camera";
    private static final String KEY_LOAD_CACHED_GALLERY = "load-cached-gallery";
    private static final String KEY_SHOW_LOGS = "show-logs";
    private static final String KEY_CONFIRM_ALBUM_WRITES = "confirm-album-writes";
    private static final String KEY_EXPORT_DELETED_PHOTOS = "export-deleted-photos";
    private static final String KEY_AUTO_RGB_MERGE = "auto-rgb-merge";
    private static final String KEY_RGB4_ORDER = "rgb4-order";
    private static final String KEY_RGB3_ORDER = "rgb3-order";
    private static final String KEY_DEFAULT_MERGE_ALGO = "default-merge-algo";
    private static final String KEY_MERGE_ALGO_OVERRIDE_PREFIX = "merge-algo-override:";
    private static final String DEFAULT_RGB4_ORDER = "CRGB";
    private static final String DEFAULT_RGB3_ORDER = "RGB";
    private static final String[] RGB4_ORDERS = {
            "CRGB", "CRBG", "CGBR", "CGRB", "CBRG", "CBGR",
            "RCGB", "RCBG", "RGBC", "RGCB", "RBGC", "RBCG",
            "GCRB", "GCBR", "GRCB", "GRBC", "GBCR", "GBRC",
            "BCRG", "BCGR", "BRCG", "BRGC", "BGCR", "BGRC"
    };
    private static final String[] RGB3_ORDERS = {
            "RGB", "RBG", "GRB", "GBR", "BRG", "BGR"
    };
    private static final int REQUEST_IMPORT_SAVE = 1;
    private static final int REQUEST_EXPORT_SAVE = 2;

    private UsbManager usbManager;
    private UsbDevice selectedDevice;
    private PendingOperation pendingOperation = PendingOperation.NONE;
    private MainScreen screen;
    private GbcamOperationRunner operationRunner;
    private boolean autoLoadAttempted;
    private int paletteIndex;
    private File pendingSaveExport;
    private String pendingReorderCsv = "";
    private String pendingReorderMessage = "Reordering album...";

    private enum PendingOperation {
        NONE,
        LOAD,
        DELETE,
        RECOVER,
        REORDER
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
        int defaultPaletteIndex = NativeGbcam.defaultPaletteIndex();
        String[] paletteLabels = paletteLabels();
        int[][] paletteColors = paletteColors(paletteLabels.length);
        paletteIndex = prefs().getInt(KEY_PALETTE_INDEX, defaultPaletteIndex);
        paletteIndex = Math.max(0, Math.min(paletteIndex, paletteLabels.length - 1));
        screen = new MainScreen(
                this,
                this,
                paletteLabels,
                paletteColors,
                paletteFavorites(paletteLabels),
                recentPalettes(paletteLabels),
                defaultPaletteIndex);
        screen.setPaletteIndex(paletteIndex);
        screen.setLogsVisibleFromSettings(showLogsByDefault());
        setContentView(screen.view());

        registerUsbReceiver();
        onLog("Rust core loaded: " + NativeGbcam.version());
        if (refreshDevice() && autoLoadCameraEnabled()) {
            autoLoadCamera();
        } else if (loadCachedGalleryEnabled()) {
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
        int selected = gallery.selectedCount();
        try {
            PhotoExporter.ExportResult result = PhotoExporter.exportSelected(this, gallery, exportDeletedPhotosEnabled());
            onLog("Saved " + selected + " photo(s):\n" + result.summary());
        } catch (Exception e) {
            onLog("Save failed: " + e.getMessage());
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
            PhotoExporter.ExportResult result = PhotoExporter.exportSelected(this, gallery, exportDeletedPhotosEnabled());
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
            onLog("Share failed: " + e.getMessage());
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
    public void onDeleteSelectedRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null || gallery.selectedActiveCount() == 0) {
            return;
        }

        confirmOrRun(
                "Delete selected photos?",
                "This removes " + gallery.selectedActiveCount() + " active photo(s) from the camera album. A save backup is kept in the app dumps folder.",
                "Delete",
                () -> startOperation(PendingOperation.DELETE));
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
                () -> startOperation(PendingOperation.RECOVER));
    }

    @Override
    public void onMoveSelectedFirstRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null || gallery.selectedActiveCount() == 0) {
            return;
        }

        pendingReorderCsv = gallery.selectedActiveFirstPhysicalSlotsCsv();
        pendingReorderMessage = "Moving selected photos to front...";
        confirmOrRun(
                "Move selected photos first?",
                "This rewrites the album order so selected active photos appear first. A save backup is kept in the app dumps folder.",
                "Move",
                () -> startOperation(PendingOperation.REORDER));
    }

    @Override
    public void onCompactAlbumRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null) {
            return;
        }

        pendingReorderCsv = gallery.activePhysicalSlotsCsv();
        pendingReorderMessage = "Compacting album order...";
        confirmOrRun(
                "Compact album order?",
                "This rewrites active photos into contiguous album positions and leaves deleted slots hidden. A save backup is kept in the app dumps folder.",
                "Compact",
                () -> startOperation(PendingOperation.REORDER));
    }

    @Override
    public void onClearAlbumRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null) {
            return;
        }

        pendingReorderCsv = "";
        pendingReorderMessage = "Clearing album order...";
        confirmOrRun(
                "Clear camera album?",
                "This hides every album slot by writing an empty state vector. Image data remains in SRAM for recovery/export until overwritten. A save backup is kept in the app dumps folder.",
                "Clear",
                () -> startOperation(PendingOperation.REORDER));
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
    public void onPalettePreview(int paletteIndex) {
        if (screen.gallery() == null) {
            screen.previewPaletteIndex(paletteIndex);
            return;
        }
        recolorCachedGallery(paletteIndex, false);
    }

    @Override
    public void onPalettePreviewCanceled(int paletteIndex) {
        if (screen.gallery() == null) {
            screen.setPaletteIndex(this.paletteIndex);
            return;
        }
        recolorCachedGallery(this.paletteIndex, false);
    }

    @Override
    public void onPaletteChanged(int paletteIndex) {
        this.paletteIndex = paletteIndex;
        prefs().edit().putInt(KEY_PALETTE_INDEX, paletteIndex).apply();
        GalleryState current = screen.gallery();
        if (current != null) {
            rememberBackupPalette(new File(current.savePath), paletteIndex);
        }
        rememberRecentPalette(paletteIndex);
        screen.setRecentPalettes(recentPalettes(paletteLabels()));
        if (screen != null) {
            recolorCachedGallery();
        }
    }

    @Override
    public void onPaletteFavoriteToggled(int paletteIndex) {
        String[] labels = paletteLabels();
        if (paletteIndex < 0 || paletteIndex >= labels.length) {
            return;
        }
        Set<String> favorites = new HashSet<>(prefs().getStringSet(KEY_PALETTE_FAVORITES, new HashSet<>()));
        boolean favorite;
        if (favorites.contains(labels[paletteIndex])) {
            favorites.remove(labels[paletteIndex]);
            favorite = false;
        } else {
            favorites.add(labels[paletteIndex]);
            favorite = true;
        }
        prefs().edit().putStringSet(KEY_PALETTE_FAVORITES, new HashSet<>(favorites)).commit();
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
        gallery = applyAutoRgbMerge(gallery);
        rememberBackupPalette(new File(gallery.savePath), gallery.paletteIndex);
        screen.showGallery(gallery);
        onLog("Loaded " + gallery.photos.size() + " camera photo(s).");
        if (gallery.validationErrors > 0 || gallery.validationWarnings > 0) {
            onLog("Save validation: " + gallery.validationErrors + " error(s), "
                    + gallery.validationWarnings + " warning(s).");
        }
    }

    private void showSettingsDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int panel = Color.rgb(15, 23, 42);
        int panelRaised = Color.rgb(30, 41, 59);
        int border = Color.rgb(71, 85, 105);
        int borderSoft = Color.rgb(51, 65, 85);
        int textPrimary = Color.rgb(248, 250, 252);
        int textSecondary = Color.rgb(203, 213, 225);
        int textMuted = Color.rgb(148, 163, 184);
        int accent = screen.accentColor();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(14));
        content.setBackground(rounded(panel, border, 14, 1));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(textPrimary);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBlock.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Startup, logs, exports, and album safety");
        subtitle.setTextColor(textSecondary);
        subtitle.setTextSize(12);
        titleBlock.addView(subtitle);
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button close = previewButton("Close", textSecondary, panelRaised, border);
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(82), dp(42)));
        content.addView(header, matchWidthWrapContent());

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(0, dp(12), 0, 0);

        CheckBox autoLoad = settingsCheckBox(
                "Auto-load camera on launch",
                "Starts reading the camera automatically when a GBxCart RW is connected.",
                autoLoadCameraEnabled(),
                textPrimary,
                textSecondary,
                accent,
                border);
        CheckBox loadCache = settingsCheckBox(
                "Load last gallery when offline",
                "Shows the most recent save backup when no camera is connected or auto-load is off.",
                loadCachedGalleryEnabled(),
                textPrimary,
                textSecondary,
                accent,
                border);
        CheckBox showLogs = settingsCheckBox(
                "Show logs by default",
                "Keeps the operation log panel open after app startup.",
                showLogsByDefault(),
                textPrimary,
                textSecondary,
                accent,
                border);
        CheckBox confirmWrites = settingsCheckBox(
                "Confirm album writes",
                "Asks before delete, recover, reorder, compact, or clear operations.",
                confirmAlbumWritesEnabled(),
                textPrimary,
                textSecondary,
                accent,
                border);
        CheckBox exportDeleted = settingsCheckBox(
                "Export deleted photos",
                "Includes selected recoverable deleted slots when saving or sharing images.",
                exportDeletedPhotosEnabled(),
                textPrimary,
                textSecondary,
                accent,
                border);
        CheckBox autoRgbMerge = settingsCheckBox(
                "Auto-detect RGB sets",
                "Merges consecutive 3-shot RGB and 4-shot CRGB captures.",
                autoRgbMergeEnabled(),
                textPrimary,
                textSecondary,
                accent,
                border);

        final String[] rgb4Value = { rgb4Order() };
        final String[] rgb3Value = { rgb3Order() };
        View rgb4Row = settingsPickerRow(
                "4-shot order",
                "Position of C (clear), R, G, B in consecutive shots.",
                RGB4_ORDERS, rgb4Value,
                textPrimary, textSecondary, accent, border, panelRaised);
        View rgb3Row = settingsPickerRow(
                "3-shot order",
                "Position of R, G, B in consecutive shots.",
                RGB3_ORDERS, rgb3Value,
                textPrimary, textSecondary, accent, border, panelRaised);

        final String[] defaultAlgoValue = { defaultMergeAlgorithm() };
        View algoRow = settingsIdPickerRow(
                "Default merge algorithm",
                "Algorithm used when auto-detecting RGB sets.",
                RgbMergeDetector.ALGORITHM_IDS,
                RgbMergeDetector.ALGORITHM_LABELS,
                new String[]{ "Basic", "Clear Lum", "Norm RGB", "Norm+Clear", "Sat Boost", "Adaptive ★" },
                defaultAlgoValue,
                textPrimary, textSecondary, accent, border, panelRaised);

        boolean rgbMergeOn = autoRgbMerge.isChecked();
        rgb4Row.setVisibility(rgbMergeOn ? View.VISIBLE : View.GONE);
        rgb3Row.setVisibility(rgbMergeOn ? View.VISIBLE : View.GONE);
        algoRow.setVisibility(rgbMergeOn ? View.VISIBLE : View.GONE);
        autoRgbMerge.setOnCheckedChangeListener((btn, checked) -> {
            rgb4Row.setVisibility(checked ? View.VISIBLE : View.GONE);
            rgb3Row.setVisibility(checked ? View.VISIBLE : View.GONE);
            algoRow.setVisibility(checked ? View.VISIBLE : View.GONE);
        });

        options.addView(settingsRow(autoLoad, panelRaised, borderSoft));
        options.addView(settingsRow(loadCache, panelRaised, borderSoft));
        options.addView(settingsRow(showLogs, panelRaised, borderSoft));
        options.addView(settingsRow(confirmWrites, panelRaised, borderSoft));
        options.addView(settingsRow(exportDeleted, panelRaised, borderSoft));
        options.addView(settingsRow(autoRgbMerge, panelRaised, borderSoft));
        options.addView(rgb4Row);
        options.addView(rgb3Row);
        options.addView(algoRow);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(options);
        content.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dp(360), dp(72) * 9 + dp(12))));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionParams.setMargins(0, dp(12), 0, 0);

        Button cancel = previewButton("Cancel", textMuted, panelRaised, border);
        cancel.setOnClickListener(v -> dialog.dismiss());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(44), 1));

        View gap = new View(this);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));

        Button apply = previewButton("Apply", accent, panelRaised, accent);
        apply.setOnClickListener(v -> {
            boolean rgbSettingsChanged = autoRgbMerge.isChecked() != autoRgbMergeEnabled()
                    || !rgb4Value[0].equals(rgb4Order())
                    || !rgb3Value[0].equals(rgb3Order())
                    || !defaultAlgoValue[0].equals(defaultMergeAlgorithm());
            prefs().edit()
                    .putBoolean(KEY_AUTO_LOAD_CAMERA, autoLoad.isChecked())
                    .putBoolean(KEY_LOAD_CACHED_GALLERY, loadCache.isChecked())
                    .putBoolean(KEY_SHOW_LOGS, showLogs.isChecked())
                    .putBoolean(KEY_CONFIRM_ALBUM_WRITES, confirmWrites.isChecked())
                    .putBoolean(KEY_EXPORT_DELETED_PHOTOS, exportDeleted.isChecked())
                    .putBoolean(KEY_AUTO_RGB_MERGE, autoRgbMerge.isChecked())
                    .putString(KEY_RGB4_ORDER, rgb4Value[0])
                    .putString(KEY_RGB3_ORDER, rgb3Value[0])
                    .putString(KEY_DEFAULT_MERGE_ALGO, defaultAlgoValue[0])
                    .apply();
            screen.setLogsVisibleFromSettings(showLogs.isChecked());
            onLog("Settings updated.");
            dialog.dismiss();
            if (rgbSettingsChanged && screen.gallery() != null) {
                recolorCachedGallery(paletteIndex, false);
            }
        });
        actions.addView(apply, new LinearLayout.LayoutParams(0, dp(44), 1));
        content.addView(actions, actionParams);

        dialog.setContentView(content);
        dialog.setOnShowListener(d -> {
            Window shownWindow = dialog.getWindow();
            if (shownWindow != null) {
                WindowManager.LayoutParams params = new WindowManager.LayoutParams();
                params.copyFrom(shownWindow.getAttributes());
                params.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(560));
                params.height = WindowManager.LayoutParams.WRAP_CONTENT;
                shownWindow.setAttributes(params);
                shownWindow.setBackgroundDrawableResource(android.R.color.transparent);
            }
        });
        dialog.show();
    }

    private CheckBox settingsCheckBox(
            String title,
            String description,
            boolean checked,
            int textPrimary,
            int textSecondary,
            int accent,
            int border) {
        CheckBox box = new CheckBox(this);
        SpannableString text = new SpannableString(title + "\n" + description);
        text.setSpan(new ForegroundColorSpan(textPrimary), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(textSecondary), title.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(0.88f), title.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        box.setText(text);
        box.setTextColor(textPrimary);
        box.setTextSize(12);
        box.setChecked(checked);
        box.setMinHeight(0);
        box.setMinimumHeight(0);
        box.setPadding(dp(10), 0, dp(10), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            box.setButtonTintList(new ColorStateList(
                    new int[][] {
                            new int[] { android.R.attr.state_checked },
                            new int[] {}
                    },
                    new int[] {
                            accent,
                            border
                    }));
        }
        return box;
    }

    private View settingsRow(CheckBox box, int fill, int stroke) {
        FrameLayout row = new FrameLayout(this);
        row.setBackground(rounded(fill, stroke, 10, 1));
        row.setClickable(true);
        row.setOnClickListener(v -> box.toggle());
        row.addView(box, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(66));
        params.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(params);
        return row;
    }

    private View settingsPickerRow(
            String title,
            String description,
            String[] options,
            String[] valueHolder,
            int textPrimary,
            int textSecondary,
            int accent,
            int border,
            int fill) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(fill, border, 10, 1));
        row.setPadding(dp(10), dp(10), dp(10), dp(10));

        SpannableString text = new SpannableString(title + "\n" + description);
        text.setSpan(new ForegroundColorSpan(textPrimary), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(textSecondary), title.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(0.88f), title.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12);
        label.setPadding(0, 0, dp(8), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView badge = new TextView(this);
        badge.setText(valueHolder[0]);
        badge.setTextColor(accent);
        badge.setTextSize(13);
        badge.setTypeface(android.graphics.Typeface.MONOSPACE);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(12), 0, dp(12), 0);
        badge.setBackground(rounded(fill, accent, 6, 1));
        row.addView(badge, new LinearLayout.LayoutParams(dp(64), dp(36)));

        row.setClickable(true);
        row.setOnClickListener(v -> {
            int current = 0;
            for (int i = 0; i < options.length; i++) {
                if (options[i].equals(valueHolder[0])) { current = i; break; }
            }
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setSingleChoiceItems(options, current, (d, which) -> {
                        valueHolder[0] = options[which];
                        badge.setText(options[which]);
                        d.dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(66));
        params.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(params);
        return row;
    }

    /** Like settingsPickerRow but stores IDs in valueHolder while showing labels in the dialog and shortLabels in the badge. */
    private View settingsIdPickerRow(
            String title,
            String description,
            String[] ids,
            String[] labels,
            String[] shortLabels,
            String[] valueHolder,
            int textPrimary,
            int textSecondary,
            int accent,
            int border,
            int fill) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(fill, border, 10, 1));
        row.setPadding(dp(10), dp(10), dp(10), dp(10));

        SpannableString text = new SpannableString(title + "\n" + description);
        text.setSpan(new ForegroundColorSpan(textPrimary), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(textSecondary), title.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(0.88f), title.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12);
        label.setPadding(0, 0, dp(8), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView badge = new TextView(this);
        badge.setText(shortLabelForId(ids, shortLabels, valueHolder[0]));
        badge.setTextColor(accent);
        badge.setTextSize(11);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(8), 0, dp(8), 0);
        badge.setBackground(rounded(fill, accent, 6, 1));
        row.addView(badge, new LinearLayout.LayoutParams(dp(80), dp(36)));

        row.setClickable(true);
        row.setOnClickListener(v -> {
            int current = 0;
            for (int i = 0; i < ids.length; i++) {
                if (ids[i].equals(valueHolder[0])) { current = i; break; }
            }
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, current, (d, which) -> {
                        valueHolder[0] = ids[which];
                        badge.setText(shortLabelForId(ids, shortLabels, ids[which]));
                        d.dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(66));
        params.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(params);
        return row;
    }

    private static String shortLabelForId(String[] ids, String[] shortLabels, String id) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(id)) return shortLabels[i];
        }
        return id;
    }

    private void confirmOrRun(String title, String message, String action, Runnable runnable) {
        if (!confirmAlbumWritesEnabled()) {
            runnable.run();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(action, (dialog, which) -> runnable.run())
                .show();
    }

    private boolean autoLoadCameraEnabled() {
        return prefs().getBoolean(KEY_AUTO_LOAD_CAMERA, true);
    }

    private boolean loadCachedGalleryEnabled() {
        return prefs().getBoolean(KEY_LOAD_CACHED_GALLERY, true);
    }

    private boolean showLogsByDefault() {
        return prefs().getBoolean(KEY_SHOW_LOGS, false);
    }

    private boolean confirmAlbumWritesEnabled() {
        return prefs().getBoolean(KEY_CONFIRM_ALBUM_WRITES, true);
    }

    private boolean exportDeletedPhotosEnabled() {
        return prefs().getBoolean(KEY_EXPORT_DELETED_PHOTOS, true);
    }

    private boolean autoRgbMergeEnabled() {
        return prefs().getBoolean(KEY_AUTO_RGB_MERGE, true);
    }

    private String rgb4Order() {
        return prefs().getString(KEY_RGB4_ORDER, DEFAULT_RGB4_ORDER);
    }

    private String rgb3Order() {
        return prefs().getString(KEY_RGB3_ORDER, DEFAULT_RGB3_ORDER);
    }

    private String defaultMergeAlgorithm() {
        return prefs().getString(KEY_DEFAULT_MERGE_ALGO, RgbMergeDetector.ALGO_NORM_CLEAR_LUM);
    }

    private Map<String, String> mergeAlgorithmOverrides() {
        Map<String, String> overrides = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            if (entry.getKey().startsWith(KEY_MERGE_ALGO_OVERRIDE_PREFIX) && entry.getValue() instanceof String) {
                overrides.put(entry.getKey().substring(KEY_MERGE_ALGO_OVERRIDE_PREFIX.length()),
                        (String) entry.getValue());
            }
        }
        return overrides;
    }

    private void saveMergeAlgorithmOverride(GalleryPhoto photo, String algorithm) {
        String identity = photo.mergedKind + ":" + photo.mergedSourceStartDisplayIndex + ":" + photo.mergedSourceCount;
        prefs().edit().putString(KEY_MERGE_ALGO_OVERRIDE_PREFIX + identity, algorithm).apply();
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
        if (!refreshDevice()) {
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
                operationRunner.loadGallery(usbManager, selectedDevice, dumpsDir(), paletteIndex, this);
                break;
            case DELETE:
                GalleryState gallery = screen.gallery();
                if (gallery != null) {
                    operationRunner.deletePhotos(usbManager, selectedDevice, gallery, dumpsDir(), paletteIndex, this);
                }
                break;
            case RECOVER:
                GalleryState recoverGallery = screen.gallery();
                if (recoverGallery != null) {
                    operationRunner.recoverPhotos(usbManager, selectedDevice, recoverGallery, dumpsDir(), paletteIndex, this);
                }
                break;
            case REORDER:
                GalleryState reorderGallery = screen.gallery();
                if (reorderGallery != null) {
                    operationRunner.reorderPhotos(usbManager, selectedDevice, reorderGallery, dumpsDir(), paletteIndex, pendingReorderCsv, pendingReorderMessage, this);
                }
                break;
            case NONE:
                break;
        }
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
            gallery = applyAutoRgbMerge(gallery);
            rememberBackupPalette(save, paletteIndex);
            screen.showGallery(gallery);
            onLog("Loaded cached gallery from:\n" + save.getAbsolutePath());
        } catch (Exception e) {
            onLog("Cached gallery load failed: " + e.getMessage());
        }
    }

    private void loadBackupSave(File save) {
        try {
            File current = new File(dumpsDir(), "GAMEBOYCAMERA.sav");
            int backupPalette = backupPaletteIndex(save);
            paletteIndex = backupPalette;
            prefs().edit().putInt(KEY_PALETTE_INDEX, paletteIndex).apply();
            if (!save.getCanonicalPath().equals(current.getCanonicalPath())) {
                copy(save, current);
            }
            rememberBackupPalette(current, paletteIndex);
            loadCachedGallery();
            onLog("Loaded save backup:\n" + save.getAbsolutePath());
        } catch (Exception e) {
            onLog("Backup load failed: " + e.getMessage());
        }
    }

    private void showBackupPicker(File[] saves) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int panel = Color.rgb(15, 23, 42);
        int panelRaised = Color.rgb(30, 41, 59);
        int border = Color.rgb(71, 85, 105);
        int textPrimary = Color.rgb(248, 250, 252);
        int textSecondary = Color.rgb(203, 213, 225);
        int textMuted = Color.rgb(148, 163, 184);
        int accent = screen.accentColor();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(14));
        content.setBackground(rounded(panel, border, 14, 1));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Save Backups");
        title.setTextColor(textPrimary);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBlock.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(saves.length + " file" + (saves.length == 1 ? "" : "s") + " available");
        subtitle.setTextColor(textSecondary);
        subtitle.setTextSize(12);
        titleBlock.addView(subtitle);
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button close = previewButton("Close", textSecondary, panelRaised, border);
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(82), dp(42)));
        content.addView(header, matchWidthWrapContent());

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(12), 0, 0);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (File save : saves) {
            list.addView(backupRow(save, format, dialog, panelRaised, border, textPrimary, textSecondary, textMuted, accent));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dp(420), dp(86) * saves.length + dp(12)));
        content.addView(scroll, scrollParams);

        dialog.setContentView(content);
        dialog.setOnShowListener(d -> {
            Window shownWindow = dialog.getWindow();
            if (shownWindow != null) {
                WindowManager.LayoutParams params = new WindowManager.LayoutParams();
                params.copyFrom(shownWindow.getAttributes());
                params.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(560));
                params.height = WindowManager.LayoutParams.WRAP_CONTENT;
                shownWindow.setAttributes(params);
                shownWindow.setBackgroundDrawableResource(android.R.color.transparent);
            }
        });
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
            int accent) {
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

        row.addView(backupMosaic(save, current, border, accent, textSecondary), new LinearLayout.LayoutParams(dp(58), dp(58)));

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

    private View backupMosaic(File save, boolean current, int border, int accent, int textSecondary) {
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(2), dp(2), dp(2), dp(2));
        frame.setBackground(rounded(Color.rgb(15, 23, 42), current ? accent : border, 8, current ? 2 : 1));

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipToOutline(true);
        GalleryPhoto[] photos = backupPreviewPhotos(save);
        for (int y = 0; y < 2; y++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int x = 0; x < 2; x++) {
                int index = y * 2 + x;
                View tile;
                if (photos[index] != null) {
                    ImageView image = new ImageView(this);
                    image.setImageURI(Uri.fromFile(new File(photos[index].path)));
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    image.setBackgroundColor(Color.rgb(24, 24, 27));
                    tile = image;
                } else {
                    tile = new View(this);
                    tile.setBackgroundColor(Color.rgb(24, 24, 27));
                }
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
                tileParams.setMargins(x == 0 ? 0 : dp(1), y == 0 ? 0 : dp(1), 0, 0);
                line.addView(tile, tileParams);
            }
            rows.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        }
        frame.addView(rows, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        if (photos[0] == null) {
            TextView badge = new TextView(this);
            badge.setText("SAV");
            badge.setGravity(Gravity.CENTER);
            badge.setTextSize(9);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setTextColor(textSecondary);
            frame.addView(badge, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }

        return frame;
    }

    private GalleryPhoto[] backupPreviewPhotos(File save) {
        GalleryPhoto[] preview = new GalleryPhoto[4];
        try {
            int backupPalette = backupPaletteIndex(save);
            File output = new File(
                    appFilesDir(null),
                    "backup-previews/" + safeFilePart(save.getName()) + "-" + save.lastModified() + "-p" + backupPalette);
            if (!output.mkdirs() && !output.isDirectory()) {
                return preview;
            }
            GalleryState gallery = GalleryState.fromJson(NativeGbcam.loadGalleryFromSave(
                    save.getAbsolutePath(),
                    output.getAbsolutePath(),
                    backupPalette));
            int count = gallery.photos.size();
            if (count == 0) {
                return preview;
            }

            int[] indices = backupPreviewIndices(count);
            for (int i = 0; i < indices.length; i++) {
                preview[i] = gallery.photos.get(indices[i]);
            }
        } catch (Exception e) {
            Log.w(TAG, "Backup preview failed for " + save.getName(), e);
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
        int fallback = NativeGbcam.defaultPaletteIndex();
        return prefs().getInt(backupPaletteKey(save), fallback);
    }

    private void rememberBackupPalette(File save, int paletteIndex) {
        prefs().edit().putInt(backupPaletteKey(save), paletteIndex).apply();
    }

    private String backupPaletteKey(File save) {
        String path;
        try {
            path = save.getCanonicalPath();
        } catch (Exception e) {
            path = save.getAbsolutePath();
        }
        return KEY_BACKUP_PALETTE_PREFIX
                + path
                + ":"
                + save.lastModified()
                + ":"
                + save.length();
    }

    private void showPhotoDetail(GalleryPhoto photo) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int panel = Color.rgb(15, 23, 42);
        int panelRaised = Color.rgb(30, 41, 59);
        int panelSoft = Color.rgb(22, 33, 52);
        int border = Color.rgb(71, 85, 105);
        int borderSoft = Color.rgb(51, 65, 85);
        int textPrimary = Color.rgb(248, 250, 252);
        int textSecondary = Color.rgb(203, 213, 225);
        int textMuted = Color.rgb(148, 163, 184);
        int danger = Color.rgb(220, 38, 38);
        int accent = screen.accentColor();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(16));
        content.setBackground(rounded(panel, border, 14, 1));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(photoDetailTitle(photo));
        title.setTextColor(textPrimary);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        titleBlock.addView(title);

        TextView fileName = new TextView(this);
        fileName.setText(photo.name);
        fileName.setTextColor(textSecondary);
        fileName.setTextSize(12);
        fileName.setSingleLine(true);
        titleBlock.addView(fileName);

        header.addView(titleBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button close = previewButton("×", textSecondary, panelRaised, border);
        close.setTextSize(22);
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(42)));
        content.addView(header, matchWidthWrapContent());

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(12), 0, 0);
        statusRow.addView(detailChip(photo.deleted ? "Deleted" : photo.mergedRgb ? mergedPhotoTitle(photo) : "Original", photo.deleted ? danger : accent, panelSoft, photo.deleted ? danger : accent));
        statusRow.addView(detailChip(photo.mergedRgb ? "Auto-merged" : photo.copy ? "Copy" : "Camera photo", textSecondary, panelSoft, borderSoft));
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
        imageMat.setBackground(rounded(Color.rgb(2, 6, 23), border, 12, 1));
        LinearLayout.LayoutParams matParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        matParams.setMargins(0, dp(12), 0, dp(14));

        ImageView image = new ImageView(this);
        image.setImageURI(Uri.fromFile(new File(photo.path)));
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
            algoField.setBackground(rounded(panelRaised, border, 8, 1));
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

                int surfaceRaised = Color.rgb(37, 52, 76);
                int borderStrong  = Color.rgb(71, 85, 105);
                int textPrim      = Color.rgb(241, 245, 249);
                int accentSurface = Color.rgb(
                        Math.round(Color.red(accent)   * 0.28f + 15 * 0.72f),
                        Math.round(Color.green(accent) * 0.28f + 23 * 0.72f),
                        Math.round(Color.blue(accent)  * 0.28f + 42 * 0.72f));

                LinearLayout menu = new LinearLayout(this);
                menu.setOrientation(LinearLayout.VERTICAL);
                ScrollView menuScroll = new ScrollView(this);
                menuScroll.setBackground(rounded(surfaceRaised, borderStrong, 8, 1));
                menuScroll.addView(menu);

                int itemH       = dp(48);
                int popupHeight = Math.min(itemH * ids.length, dp(288));
                int popupWidth  = algoField.getWidth();

                PopupWindow popup = new PopupWindow(menuScroll, popupWidth, popupHeight, true);
                popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                popup.setOutsideTouchable(true);
                popup.setElevation(dp(8));

                for (int i = 0; i < ids.length; i++) {
                    final int idx = i;
                    boolean sel = ids[i].equals(previewAlgo[0]);
                    LinearLayout item = new LinearLayout(this);
                    item.setOrientation(LinearLayout.HORIZONTAL);
                    item.setGravity(Gravity.CENTER_VERTICAL);
                    item.setPadding(dp(16), 0, dp(16), 0);
                    item.setBackgroundColor(sel ? accentSurface : surfaceRaised);
                    item.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, itemH));
                    TextView itemLabel = new TextView(this);
                    itemLabel.setText(labels[i]);
                    itemLabel.setTextSize(13);
                    itemLabel.setTextColor(sel ? accent : textPrim);
                    itemLabel.setSingleLine(true);
                    itemLabel.setIncludeFontPadding(false);
                    item.addView(itemLabel, new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                    item.setOnClickListener(iv -> {
                        popup.dismiss();
                        if (ids[idx].equals(previewAlgo[0])) return;
                        previewAlgo[0] = ids[idx];
                        algoChanged[0] = true;
                        algoDropText.setText(labels[idx]);
                        runPreviewMerge(photo, previewAlgo[0], image,
                                progressRef[0], previewGeneration);
                    });
                    menu.addView(item);
                }

                // Popup opens above the field
                popup.showAsDropDown(algoField, 0, -(popupHeight + algoField.getHeight() + dp(4)));
            });

            LinearLayout.LayoutParams algoFieldParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
            algoFieldParams.setMargins(0, dp(8), 0, 0);
            content.addView(algoField, algoFieldParams);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        Button select = previewButton(photo.selected ? "Deselect" : "Select", textPrimary, panelRaised, border);
        select.setOnClickListener(v -> {
            photo.selected = !photo.selected;
            select.setText(photo.selected ? "Deselect" : "Select");
            screen.showGallery(screen.gallery());
        });
        actions.addView(select, new LinearLayout.LayoutParams(0, dp(44), 1));

        View gap = new View(this);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));

        Button share = previewButton("Share", accent, panelRaised, accent);
        share.setOnClickListener(v -> shareSinglePhoto(photo));
        actions.addView(share, new LinearLayout.LayoutParams(0, dp(44), 1));

        content.addView(actions, matchWidthWrapContent());

        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        if (photo.mergedRgb) {
            dialog.setOnDismissListener(d -> {
                if (algoChanged[0]) {
                    saveMergeAlgorithmOverride(photo, previewAlgo[0]);
                    recolorCachedGallery(paletteIndex, false);
                }
            });
        }
        dialog.setOnShowListener(d -> {
            Window shownWindow = dialog.getWindow();
            if (shownWindow != null) {
                WindowManager.LayoutParams params = new WindowManager.LayoutParams();
                params.copyFrom(shownWindow.getAttributes());
                params.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(560));
                params.height = WindowManager.LayoutParams.WRAP_CONTENT;
                shownWindow.setAttributes(params);
                shownWindow.setBackgroundDrawableResource(android.R.color.transparent);
            }
        });
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

    private void showRemergePicker(GalleryPhoto photo, Dialog parentDialog) {
        boolean hasClear = photo.mergedSourceCount == 4;
        String[] ids    = RgbMergeDetector.compatibleAlgorithmIds(hasClear);
        String[] labels = RgbMergeDetector.compatibleAlgorithmLabels(hasClear);
        int current = 0;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(photo.mergedAlgorithm)) { current = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("Merge algorithm")
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    d.dismiss();
                    parentDialog.dismiss();
                    saveMergeAlgorithmOverride(photo, ids[which]);
                    recolorCachedGallery(paletteIndex, false);
                })
                .setNegativeButton("Cancel", null)
                .show();
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
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextColor(textColor);
        chip.setTextSize(11);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setIncludeFontPadding(false);
        chip.setSingleLine(true);
        chip.setPadding(dp(10), 0, dp(10), 0);
        chip.setBackground(rounded(fillColor, strokeColor, 999, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(28));
        params.setMargins(0, 0, dp(8), 0);
        chip.setLayoutParams(params);
        return chip;
    }

    private Button previewButton(String text, int textColor, int fillColor, int strokeColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(textColor);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setMinimumHeight(0);
        button.setMinimumWidth(0);
        button.setIncludeFontPadding(false);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(rounded(fillColor, strokeColor, 8, 1));
        return button;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setStroke(dp(strokeDp), stroke);
        bg.setCornerRadius(dp(radiusDp));
        return bg;
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

        try {
            GalleryState gallery = GalleryState.fromJson(NativeGbcam.loadGalleryFromSave(
                    previous.savePath,
                    previous.outputDir,
                    paletteIndex));
            gallery = applyAutoRgbMerge(gallery);
            gallery.copySelectionFrom(previous);
            screen.showGallery(gallery);
            if (logChange) {
                onLog("Palette changed: " + gallery.paletteName);
            }
        } catch (Exception e) {
            onLog("Palette change failed: " + e.getMessage());
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private GalleryState applyAutoRgbMerge(GalleryState gallery) {
        gallery = filterEmptyDeletedPhotos(gallery);
        if (!autoRgbMergeEnabled()) {
            return gallery;
        }
        List<GalleryPhoto> sourcePhotos = monoSourcePhotos(gallery);
        GalleryState merged = RgbMergeDetector.addAutoMergedPhotos(gallery, sourcePhotos, new File(gallery.outputDir),
                rgb4Order(), rgb3Order(), defaultMergeAlgorithm(), mergeAlgorithmOverrides());
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

    private static boolean isEmptyImage(GalleryPhoto photo) {
        Bitmap bmp = BitmapFactory.decodeFile(photo.path);
        if (bmp == null) return true;
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        bmp.recycle();
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
                    copy(in, out);
                }
                NativeGbcam.loadGalleryFromSave(
                        importCheck.getAbsolutePath(),
                        dumpsDir().getAbsolutePath(),
                        paletteIndex);
                copy(importCheck, target);
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
            onLog("File operation failed: " + e.getMessage());
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

    private boolean[] paletteFavorites(String[] labels) {
        Set<String> favorites = prefs().getStringSet(KEY_PALETTE_FAVORITES, new HashSet<>());
        boolean[] result = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            result[i] = favorites.contains(labels[i]);
        }
        return result;
    }

    private int[] recentPalettes(String[] labels) {
        String stored = prefs().getString(KEY_PALETTE_RECENT, "");
        if (stored.isEmpty()) {
            return new int[0];
        }
        String[] names = stored.split("\\n");
        int[] indices = new int[names.length];
        int count = 0;
        for (String name : names) {
            int index = paletteIndexForLabel(labels, name);
            if (index >= 0) {
                indices[count++] = index;
            }
        }
        return Arrays.copyOf(indices, count);
    }

    private void rememberRecentPalette(int paletteIndex) {
        String[] labels = paletteLabels();
        if (paletteIndex < 0 || paletteIndex >= labels.length) {
            return;
        }
        LinkedHashSet<String> recent = new LinkedHashSet<>();
        recent.add(labels[paletteIndex]);
        String stored = prefs().getString(KEY_PALETTE_RECENT, "");
        if (!stored.isEmpty()) {
            for (String label : stored.split("\\n")) {
                if (!label.isEmpty()) {
                    recent.add(label);
                }
                if (recent.size() >= 5) {
                    break;
                }
            }
        }
        prefs().edit().putString(KEY_PALETTE_RECENT, String.join("\n", recent)).apply();
    }

    private static int paletteIndexForLabel(String[] labels, String label) {
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(label)) {
                return i;
            }
        }
        return -1;
    }

    private void copy(File source, File target) throws Exception {
        if (target.getParentFile() != null && !target.getParentFile().mkdirs() && !target.getParentFile().isDirectory()) {
            throw new Exception("Could not create directory: " + target.getParentFile());
        }
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
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
