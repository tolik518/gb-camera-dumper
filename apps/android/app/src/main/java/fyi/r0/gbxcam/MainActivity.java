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
import android.graphics.Color;
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
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity implements MainScreen.Listener, GbcamOperationRunner.Callback {
    private static final String TAG = "GbcamApp";
    private static final String ACTION_USB_PERMISSION = "fyi.r0.gbxcam.USB_PERMISSION";
    private static final String PREFS = "gbxcam-viewer";
    private static final String KEY_PALETTE_INDEX = "palette-index";
    private static final String KEY_PALETTE_FAVORITES = "palette-favorites";
    private static final String KEY_PALETTE_RECENT = "palette-recent";
    private static final String KEY_BACKUP_PALETTE_PREFIX = "backup-palette:";
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
        int selected = gallery.selectedCount();
        try {
            PhotoExporter.ExportResult result = PhotoExporter.exportSelected(this, gallery);
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
            PhotoExporter.ExportResult result = PhotoExporter.exportSelected(this, gallery);
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
    public void onDeleteSelectedRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null || gallery.selectedActiveCount() == 0) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Delete selected photos?")
                .setMessage("This removes " + gallery.selectedActiveCount() + " active photo(s) from the camera album. A save backup is kept in the app dumps folder.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> startOperation(PendingOperation.DELETE))
                .show();
    }

    @Override
    public void onRecoverSelectedRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null || gallery.selectedDeletedCount() == 0) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Recover deleted photos?")
                .setMessage("This restores " + gallery.selectedDeletedCount() + " deleted slot(s) into the camera album. A save backup is kept in the app dumps folder.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Recover", (dialog, which) -> startOperation(PendingOperation.RECOVER))
                .show();
    }

    @Override
    public void onMoveSelectedFirstRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null || gallery.selectedActiveCount() == 0) {
            return;
        }

        pendingReorderCsv = gallery.selectedActiveFirstPhysicalSlotsCsv();
        pendingReorderMessage = "Moving selected photos to front...";
        new AlertDialog.Builder(this)
                .setTitle("Move selected photos first?")
                .setMessage("This rewrites the album order so selected active photos appear first. A save backup is kept in the app dumps folder.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Move", (dialog, which) -> startOperation(PendingOperation.REORDER))
                .show();
    }

    @Override
    public void onCompactAlbumRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null) {
            return;
        }

        pendingReorderCsv = gallery.activePhysicalSlotsCsv();
        pendingReorderMessage = "Compacting album order...";
        new AlertDialog.Builder(this)
                .setTitle("Compact album order?")
                .setMessage("This rewrites active photos into contiguous album positions and leaves deleted slots hidden. A save backup is kept in the app dumps folder.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Compact", (dialog, which) -> startOperation(PendingOperation.REORDER))
                .show();
    }

    @Override
    public void onClearAlbumRequested() {
        GalleryState gallery = screen.gallery();
        if (gallery == null) {
            return;
        }

        pendingReorderCsv = "";
        pendingReorderMessage = "Clearing album order...";
        new AlertDialog.Builder(this)
                .setTitle("Clear camera album?")
                .setMessage("This hides every album slot by writing an empty state vector. Image data remains in SRAM for recovery/export until overwritten. A save backup is kept in the app dumps folder.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> startOperation(PendingOperation.REORDER))
                .show();
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
        rememberBackupPalette(new File(gallery.savePath), gallery.paletteIndex);
        screen.showGallery(gallery);
        onLog("Loaded " + gallery.photos.size() + " camera photo(s).");
        if (gallery.validationErrors > 0 || gallery.validationWarnings > 0) {
            onLog("Save validation: " + gallery.validationErrors + " error(s), "
                    + gallery.validationWarnings + " warning(s).");
        }
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
        title.setText((photo.deleted ? "Deleted " : "Photo ") + String.format("%02d", photo.displayIndex + 1));
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
        statusRow.addView(detailChip(photo.deleted ? "Deleted" : "Original", photo.deleted ? danger : accent, panelSoft, photo.deleted ? danger : accent));
        statusRow.addView(detailChip(photo.copy ? "Copy" : "Camera photo", textSecondary, panelSoft, borderSoft));
        statusRow.addView(detailChip(photo.metadataValid ? "Metadata OK" : "Check metadata", photo.metadataValid ? textSecondary : danger, panelSoft, photo.metadataValid ? borderSoft : danger));
        content.addView(statusRow, statusParams);

        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        infoParams.setMargins(0, dp(8), 0, 0);
        infoRow.addView(detailChip("Album " + String.format("%02d", photo.displayIndex + 1), textMuted, panel, borderSoft));
        infoRow.addView(detailChip("Slot " + (photo.physicalSlot + 1), textMuted, panel, borderSoft));
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

        FrameLayout imageMat = new FrameLayout(this);
        imageMat.setPadding(dp(8), dp(8), dp(8), dp(8));
        imageMat.setBackground(rounded(Color.rgb(2, 6, 23), border, 12, 1));
        LinearLayout.LayoutParams matParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(456));
        matParams.setMargins(0, dp(12), 0, dp(14));

        ImageView image = new ImageView(this);
        image.setImageURI(Uri.fromFile(new File(photo.path)));
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAlpha(photo.deleted ? 0.86f : 1.0f);
        image.setBackgroundColor(Color.rgb(10, 10, 14));
        imageMat.addView(image, new android.widget.FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        content.addView(imageMat, matParams);

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
