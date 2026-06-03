package fyi.r0.gbxcam;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

final class MainScreen {
    interface Listener {
        void onLoadRequested();

        void onSelectAllRequested();

        void onDeselectAllRequested();

        void onSaveSelectedRequested();

        void onShareSelectedRequested();

        void onBackupsRequested();

        void onImportSaveRequested();

        void onExportSaveRequested();

        void onSettingsRequested();

        void onAboutRequested();

        void onDeleteSelectedRequested();

        void onRecoverSelectedRequested();

        void onMoveSelectedFirstRequested();

        void onCompactAlbumRequested();

        void onClearAlbumRequested();

        void onPhotoSelectionChanged(GalleryPhoto photo, boolean selected);

        void onPhotoOpenRequested(GalleryPhoto photo);

        void onPalettePreview(int paletteIndex);

        void onPalettePreviewCanceled(int paletteIndex);

        void onPaletteChanged(int paletteIndex);

        void onPaletteFavoriteToggled(int paletteIndex);
    }

    private final Context context;
    private final Listener listener;
    private final String[] paletteLabels;
    private final int[][] paletteColors;
    private final boolean[] paletteFavorites;
    private int[] recentPalettes;
    private final UiStyle.Palette colors;
    private final LinearLayout root;
    private final TextView subtitle;
    private final TextView selection;
    private final TextView empty;
    private final TextView logs;
    private final TextView logTitle;
    private final ProgressBar progress;
    private final GridLayout grid;
    private final LinearLayout loadingRow;
    private final ScrollView logScroll;
    private final FrameLayout paletteField;
    private final LinearLayout paletteSwatch;
    private final TextView paletteValue;
    private final Button loadButton;
    private final Button selectAllButton;
    private final Button deselectAllButton;
    private final Button saveButton;
    private final Button shareButton;
    private final Button backupsButton;
    private final Button importSaveButton;
    private final Button exportSaveButton;
    private final Button aboutButton;
    private final Button settingsButton;
    private final Button deleteButton;
    private final Button recoverButton;
    private final Button moveFirstButton;
    private final Button compactButton;
    private final Button clearAlbumButton;

    private GalleryState gallery;
    private boolean busy;
    private boolean logsVisible;
    private int paletteIndex;
    private int previewPaletteIndex = -1;
    private int accent;
    private int accentPressed;
    private int accentSurface;
    private int accentText;

    private Executor bitmapExecutor;
    private TileHolder[] tileHolders;
    private final AtomicInteger displayGeneration = new AtomicInteger(0);

    private static final class TileHolder {
        final LinearLayout tile;
        final ImageView image;
        final TextView selectionMarker;
        TileHolder(LinearLayout tile, ImageView image, TextView selectionMarker) {
            this.tile = tile;
            this.image = image;
            this.selectionMarker = selectionMarker;
        }
    }

    void setBitmapExecutor(Executor executor) {
        this.bitmapExecutor = executor;
    }

    MainScreen(Context context, Listener listener, String[] paletteLabels, int[][] paletteColors, boolean[] paletteFavorites, int[] recentPalettes, int defaultPaletteIndex) {
        this.context = context;
        this.listener = listener;
        this.paletteLabels = paletteLabels.length == 0 ? new String[] { "Monochrome - Grayscale" } : paletteLabels;
        this.paletteColors = paletteColors.length == 0 ? new int[][] { fallbackPaletteColors() } : paletteColors;
        this.paletteFavorites = paletteFavorites.length == this.paletteLabels.length ? paletteFavorites : new boolean[this.paletteLabels.length];
        this.recentPalettes = recentPalettes;
        this.colors = UiStyle.palette(context);

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), 0);
        root.setBackgroundColor(colors.background);
        setAccentForPalette(defaultPaletteIndex);

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBlock = new LinearLayout(context);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText("GBxCAM Viewer");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(colors.textPrimary);
        title.setContentDescription("Toggle logs");
        title.setOnClickListener(v -> toggleLogs());
        titleBlock.setOnClickListener(v -> toggleLogs());
        titleBlock.setClickable(true);
        subtitle = new TextView(context);
        subtitle.setText("Load the camera to view photos.");
        subtitle.setTextSize(14);
        subtitle.setTextColor(colors.textSecondary);
        titleBlock.addView(title);
        titleBlock.addView(subtitle);
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        loadButton = UiStyle.button(context, "Load Camera", accent, colors.surfaceRaised, accent);
        loadButton.setTextSize(12);
        loadButton.setPadding(dp(14), dp(9), dp(14), dp(9));
        loadButton.setBackground(buttonBackground(colors.surfaceRaised, accentSurface, colors.disabledBackground, accent));
        loadButton.setOnClickListener(v -> listener.onLoadRequested());
        LinearLayout.LayoutParams loadParams = new LinearLayout.LayoutParams(
                dp(136),
                dp(44));
        loadParams.setMargins(dp(14), 0, 0, 0);
        header.addView(loadButton, loadParams);
        root.addView(header, matchWidthWrapContent());

        loadingRow = row();
        loadingRow.setGravity(Gravity.CENTER_VERTICAL);
        progress = new ProgressBar(context);
        progress.setIndeterminate(true);
        loadingRow.addView(progress);
        TextView loadingText = new TextView(context);
        loadingText.setText("Working with GBxCart RW...");
        loadingText.setTextColor(colors.textSecondary);
        loadingText.setPadding(dp(16), 0, 0, 0);
        loadingRow.addView(loadingText);
        root.addView(loadingRow, matchWidthWrapContent());

        LinearLayout paletteRow = row();
        paletteRow.setGravity(Gravity.CENTER_VERTICAL);
        paletteRow.setPadding(0, dp(4), 0, dp(4));
        TextView paletteLabel = new TextView(context);
        paletteLabel.setText("Palette");
        paletteLabel.setTextColor(colors.textSecondary);
        paletteLabel.setTextSize(13);
        paletteLabel.setPadding(0, 0, dp(10), 0);
        paletteRow.addView(paletteLabel);
        paletteField = new FrameLayout(context);
        paletteField.setBackground(chipBackground());
        paletteField.setClickable(true);
        paletteField.setOnClickListener(v -> showPaletteMenu());
        paletteSwatch = paletteSwatch(dp(54), dp(18));
        FrameLayout.LayoutParams swatchParams = new FrameLayout.LayoutParams(dp(54), dp(18));
        swatchParams.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        swatchParams.setMargins(dp(12), 0, 0, 0);
        paletteField.addView(paletteSwatch, swatchParams);
        paletteValue = new TextView(context);
        paletteValue.setSingleLine(true);
        paletteValue.setEllipsize(TextUtils.TruncateAt.END);
        paletteValue.setGravity(Gravity.CENTER_VERTICAL);
        paletteValue.setIncludeFontPadding(false);
        paletteValue.setTextColor(colors.textPrimary);
        paletteValue.setTextSize(15);
        paletteValue.setPadding(dp(76), 0, dp(38), 0);
        paletteField.addView(paletteValue, new FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        TextView paletteArrow = new TextView(context);
        paletteArrow.setText("▼");
        paletteArrow.setTextSize(10);
        paletteArrow.setTextColor(colors.textSecondary);
        paletteArrow.setGravity(Gravity.CENTER);
        paletteArrow.setEnabled(false);
        FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.MATCH_PARENT);
        arrowParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        paletteField.addView(paletteArrow, arrowParams);
        paletteRow.addView(paletteField, new LinearLayout.LayoutParams(
                0,
                dp(38),
                1));
        root.addView(paletteRow, matchWidthWrapContent());
        setPaletteIndex(defaultPaletteIndex);

        LinearLayout actions = toolbarRow("Selection");
        selection = new TextView(context);
        selection.setGravity(Gravity.CENTER);
        selection.setTextSize(12);
        selection.setTextColor(colors.textSecondary);
        selection.setSingleLine(true);
        selection.setIncludeFontPadding(false);
        selection.setPadding(dp(10), 0, dp(10), 0);
        selection.setBackground(rounded(colors.surface, colors.border, 6, 1));
        LinearLayout.LayoutParams selectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36));
        selectionParams.setMargins(0, 0, dp(8), 0);
        actions.addView(selection, selectionParams);
        selectAllButton = smallButton("Select all", v -> listener.onSelectAllRequested());
        deselectAllButton = smallButton("Deselect", v -> listener.onDeselectAllRequested());
        saveButton = smallButton("Save", v -> listener.onSaveSelectedRequested());
        shareButton = smallButton("Share", v -> listener.onShareSelectedRequested());
        addActionButton(actions, selectAllButton);
        addActionButton(actions, deselectAllButton);
        addActionButton(actions, saveButton);
        addActionButton(actions, shareButton);
        root.addView(wrapHorizontal(actions), matchWidthWrapContent());

        LinearLayout albumActions = toolbarRow("Album tools");
        moveFirstButton = smallButton("Move first", v -> listener.onMoveSelectedFirstRequested());
        recoverButton = smallButton("Recover deleted", v -> listener.onRecoverSelectedRequested());
        recoverButton.setTextColor(accent);
        recoverButton.setBackground(buttonBackground(colors.surfaceRaised, accentSurface, colors.disabledBackground, accent));
        deleteButton = smallButton("Delete", v -> listener.onDeleteSelectedRequested());
        deleteButton.setTextColor(Color.WHITE);
        deleteButton.setBackground(dangerButtonBackground());
        compactButton = smallButton("Compact gaps", v -> listener.onCompactAlbumRequested());
        clearAlbumButton = smallButton("Clear album", v -> listener.onClearAlbumRequested());
        clearAlbumButton.setTextColor(colors.danger);
        clearAlbumButton.setBackground(buttonBackground(colors.surfaceRaised, blend(colors.danger, colors.background, 0.82f), colors.disabledBackground, colors.danger));
        addActionButton(albumActions, moveFirstButton);
        addActionButton(albumActions, recoverButton);
        addActionButton(albumActions, deleteButton);
        addActionButton(albumActions, compactButton);
        addActionButton(albumActions, clearAlbumButton);
        root.addView(wrapHorizontal(albumActions), matchWidthWrapContent());

        LinearLayout fileActions = toolbarRow("Files");
        backupsButton = smallButton("Backups", v -> listener.onBackupsRequested());
        importSaveButton = smallButton("Import save", v -> listener.onImportSaveRequested());
        exportSaveButton = smallButton("Export save", v -> listener.onExportSaveRequested());
        aboutButton = smallButton("About", v -> listener.onAboutRequested());
        settingsButton = smallButton("⚙", v -> listener.onSettingsRequested());
        settingsButton.setTextSize(18);
        settingsButton.setContentDescription("Settings");
        addActionButton(fileActions, backupsButton);
        addActionButton(fileActions, importSaveButton);
        addActionButton(fileActions, exportSaveButton);
        addActionButton(fileActions, aboutButton);
        addActionButton(fileActions, settingsButton);
        root.addView(wrapHorizontal(fileActions), matchWidthWrapContent());

        LinearLayout libraryHeader = row();
        libraryHeader.setGravity(Gravity.CENTER_VERTICAL);
        libraryHeader.setPadding(0, dp(10), 0, dp(4));
        TextView libraryTitle = new TextView(context);
        libraryTitle.setText("Library");
        libraryTitle.setTextColor(colors.textSecondary);
        libraryTitle.setTextSize(12);
        libraryTitle.setTypeface(Typeface.DEFAULT_BOLD);
        libraryTitle.setIncludeFontPadding(false);
        libraryHeader.addView(libraryTitle);
        View libraryRule = new View(context);
        libraryRule.setBackgroundColor(colors.border);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(
                0,
                dp(1),
                1);
        ruleParams.setMargins(dp(10), 0, 0, 0);
        libraryHeader.addView(libraryRule, ruleParams);
        root.addView(libraryHeader, matchWidthWrapContent());

        grid = new GridLayout(context);
        grid.setColumnCount(3);
        grid.setUseDefaultMargins(false);
        grid.setPadding(0, dp(2), 0, 0);

        empty = new TextView(context);
        empty.setText("No camera photos loaded yet.");
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(colors.textMuted);
        empty.setPadding(0, dp(80), 0, dp(80));

        LinearLayout galleryContainer = new LinearLayout(context);
        galleryContainer.setOrientation(LinearLayout.VERTICAL);
        galleryContainer.setPadding(0, 0, 0, dp(28));
        galleryContainer.addView(empty, matchWidthWrapContent());
        galleryContainer.addView(grid, matchWidthWrapContent());

        ScrollView galleryScroll = new ScrollView(context);
        galleryScroll.addView(galleryContainer);
        root.addView(galleryScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1));

        logTitle = new TextView(context);
        logTitle.setText("Logs");
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logTitle.setTextColor(colors.textPrimary);
        logTitle.setPadding(0, dp(8), 0, dp(4));
        root.addView(logTitle, matchWidthWrapContent());

        logs = new TextView(context);
        logs.setTextSize(12);
        logs.setTextColor(colors.logText);
        logs.setTextIsSelectable(true);
        logs.setPadding(dp(12), dp(12), dp(12), dp(12));
        logs.setBackgroundColor(colors.logBackground);
        logScroll = new ScrollView(context);
        logScroll.addView(logs);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(120)));
        setLogsVisible(false);

        setBusy(false, null);
        updateActions();
    }

    View view() {
        return root;
    }

    void showGallery(GalleryState gallery) {
        this.gallery = gallery;
        int gen = displayGeneration.incrementAndGet();
        setPaletteIndex(gallery.paletteIndex);
        grid.removeAllViews();
        tileHolders = new TileHolder[gallery.photos.size()];
        for (int i = 0; i < gallery.photos.size(); i++) {
            GalleryPhoto photo = gallery.photos.get(i);
            TileHolder holder = photoTile(photo, gen);
            tileHolders[i] = holder;
            grid.addView(holder.tile, tileParams());
        }
        empty.setVisibility(gallery.photos.isEmpty() ? View.VISIBLE : View.GONE);
        grid.setVisibility(gallery.photos.isEmpty() ? View.GONE : View.VISIBLE);
        subtitle.setText(gallery.connected + " · " + gallery.photos.size() + " photo(s)");
        updateActions();
    }

    void setBusy(boolean busy, String message) {
        this.busy = busy;
        loadingRow.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (message != null) {
            subtitle.setText(message);
        }
        updateActions();
    }

    void appendLog(String message) {
        String current = logs.getText().toString();
        if (current.isEmpty()) {
            logs.setText(message);
        } else {
            logs.setText(current + "\n" + message);
        }
    }

    void setPaletteIndex(int paletteIndex) {
        this.paletteIndex = safePaletteIndex(paletteIndex);
        this.previewPaletteIndex = -1;
        applyPaletteIndex(this.paletteIndex);
    }

    void previewPaletteIndex(int paletteIndex) {
        int index = safePaletteIndex(paletteIndex);
        if (index == previewPaletteIndex) {
            return;
        }
        previewPaletteIndex = index;
        applyPaletteIndex(index);
    }

    private void applyPaletteIndex(int paletteIndex) {
        setAccentForPalette(paletteIndex);
        paletteValue.setText(paletteLabels[paletteIndex]);
        setSwatchColors(paletteSwatch, paletteColorsForIndex(paletteIndex));
        updatePaletteAccent();
    }

    void setRecentPalettes(int[] recentPalettes) {
        this.recentPalettes = recentPalettes;
    }

    void setPaletteFavorite(int paletteIndex, boolean favorite) {
        int index = safePaletteIndex(paletteIndex);
        paletteFavorites[index] = favorite;
    }

    private int safePaletteIndex(int paletteIndex) {
        return Math.max(0, Math.min(paletteIndex, paletteLabels.length - 1));
    }

    private void toggleLogs() {
        setLogsVisible(!logsVisible);
    }

    private void setLogsVisible(boolean visible) {
        logsVisible = visible;
        int visibility = visible ? View.VISIBLE : View.GONE;
        logTitle.setVisibility(visibility);
        logScroll.setVisibility(visibility);
    }

    void setLogsVisibleFromSettings(boolean visible) {
        setLogsVisible(visible);
    }

    GalleryState gallery() {
        return gallery;
    }

    int accentColor() {
        return accent;
    }

    void selectAll(boolean selected) {
        if (gallery == null) return;
        for (int i = 0; i < gallery.photos.size(); i++) {
            GalleryPhoto photo = gallery.photos.get(i);
            photo.selected = selected;
            if (tileHolders != null && i < tileHolders.length) {
                applySelectionToTile(tileHolders[i], photo);
            }
        }
        updateActions();
    }

    void updateActions() {
        int selected = gallery == null ? 0 : gallery.selectedCount();
        int selectedActive = gallery == null ? 0 : gallery.selectedActiveCount();
        int selectedDeleted = gallery == null ? 0 : gallery.selectedDeletedCount();
        int total = gallery == null ? 0 : gallery.photos.size();
        selection.setText(total == 0 ? "No photos" : selected == 0 ? "0 selected" : selected + " of " + total + " selected");

        loadButton.setEnabled(!busy);
        selectAllButton.setEnabled(!busy && total > 0 && selected < total);
        selectAllButton.setVisibility(total > 0 && selected < total ? View.VISIBLE : View.GONE);
        deselectAllButton.setEnabled(!busy && selected > 0);
        deselectAllButton.setVisibility(selected > 0 ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!busy && selected > 0);
        saveButton.setVisibility(selected > 0 ? View.VISIBLE : View.GONE);
        shareButton.setEnabled(!busy && selected > 0);
        shareButton.setVisibility(selected > 0 ? View.VISIBLE : View.GONE);
        backupsButton.setEnabled(!busy);
        importSaveButton.setEnabled(!busy);
        exportSaveButton.setEnabled(!busy && gallery != null);
        settingsButton.setEnabled(!busy);
        deleteButton.setEnabled(!busy && selectedActive > 0);
        deleteButton.setVisibility(selectedActive > 0 ? View.VISIBLE : View.GONE);
        recoverButton.setEnabled(!busy && selectedDeleted > 0);
        recoverButton.setVisibility(selectedDeleted > 0 ? View.VISIBLE : View.GONE);
        moveFirstButton.setEnabled(!busy && selectedActive > 0);
        moveFirstButton.setVisibility(selectedActive > 0 ? View.VISIBLE : View.GONE);
        compactButton.setEnabled(!busy && gallery != null);
        compactButton.setVisibility(gallery != null ? View.VISIBLE : View.GONE);
        clearAlbumButton.setEnabled(!busy && total > 0);
        clearAlbumButton.setVisibility(total > 0 ? View.VISIBLE : View.GONE);
        paletteField.setEnabled(!busy);
        paletteField.setAlpha(busy ? 0.42f : 1.0f);
        setButtonAvailability(loadButton, !busy);
        setButtonAvailability(selectAllButton, !busy && total > 0 && selected < total);
        setButtonAvailability(deselectAllButton, !busy && selected > 0);
        setButtonAvailability(saveButton, !busy && selected > 0);
        setButtonAvailability(shareButton, !busy && selected > 0);
        setButtonAvailability(backupsButton, !busy);
        setButtonAvailability(importSaveButton, !busy);
        setButtonAvailability(exportSaveButton, !busy && gallery != null);
        setButtonAvailability(settingsButton, !busy);
        setButtonAvailability(recoverButton, !busy && selectedDeleted > 0);
        setButtonAvailability(moveFirstButton, !busy && selectedActive > 0);
        setButtonAvailability(compactButton, !busy && gallery != null);
        setButtonAvailability(clearAlbumButton, !busy && total > 0);
        deleteButton.setTextColor(!busy && selectedActive > 0 ? Color.WHITE : colors.disabledText);
    }

    private TileHolder photoTile(GalleryPhoto photo, int gen) {
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(6), dp(6), dp(6), dp(6));
        tile.setBackground(tileBackground(photo.selected, photo.deleted));
        tile.setContentDescription(photoTitle(photo)
                + (photo.selected ? ", selected" : ", not selected"));
        tile.setOnClickListener(v -> listener.onPhotoOpenRequested(photo));
        tile.setOnLongClickListener(v -> {
            togglePhotoSelection(photo);
            return true;
        });

        View.OnClickListener toggleSelection = v -> togglePhotoSelection(photo);

        FrameLayout imageFrame = new CameraImageFrame(context);
        imageFrame.setOnClickListener(v -> listener.onPhotoOpenRequested(photo));
        imageFrame.setOnLongClickListener(v -> {
            togglePhotoSelection(photo);
            return true;
        });
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(false);
        image.setAlpha(photo.deleted ? 0.72f : 1.0f);
        image.setBackgroundColor(colors.photoBackground);
        if (bitmapExecutor != null) {
            bitmapExecutor.execute(() -> {
                Bitmap bmp = BitmapFactory.decodeFile(photo.path);
                if (bmp == null || displayGeneration.get() != gen) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                ((Activity) context).runOnUiThread(() -> {
                    if (displayGeneration.get() == gen) image.setImageBitmap(bmp);
                    else bmp.recycle();
                });
            });
        } else {
            image.setImageBitmap(BitmapFactory.decodeFile(photo.path));
        }
        imageFrame.addView(image, new FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        TextView selectionMarker = new TextView(context);
        selectionMarker.setText(photo.selected ? "✓" : "");
        selectionMarker.setTextColor(accentText);
        selectionMarker.setTextSize(14);
        selectionMarker.setTypeface(Typeface.DEFAULT_BOLD);
        selectionMarker.setGravity(Gravity.CENTER);
        selectionMarker.setBackground(checkBackground(photo.selected));
        selectionMarker.setContentDescription(photo.selected ? "Selected" : "Not selected");
        selectionMarker.setAlpha(photo.selected ? 1.0f : 0.74f);
        selectionMarker.setOnClickListener(toggleSelection);
        FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(dp(26), dp(26));
        checkParams.gravity = Gravity.TOP | Gravity.START;
        checkParams.setMargins(dp(6), dp(6), 0, 0);
        imageFrame.addView(selectionMarker, checkParams);
        if (photo.deleted) {
            TextView deletedBadge = new TextView(context);
            deletedBadge.setText("Deleted");
            deletedBadge.setTextColor(Color.WHITE);
            deletedBadge.setTextSize(10);
            deletedBadge.setTypeface(Typeface.DEFAULT_BOLD);
            deletedBadge.setIncludeFontPadding(false);
            deletedBadge.setGravity(Gravity.CENTER);
            deletedBadge.setPadding(dp(8), 0, dp(8), 0);
            deletedBadge.setBackground(rounded(blend(colors.danger, Color.BLACK, 0.18f), colors.danger, 8, 1));
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(24));
            badgeParams.gravity = Gravity.TOP | Gravity.END;
            badgeParams.setMargins(0, dp(6), dp(6), 0);
            imageFrame.addView(deletedBadge, badgeParams);
        }
        tile.addView(imageFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout labelBlock = new LinearLayout(context);
        labelBlock.setOrientation(LinearLayout.VERTICAL);
        labelBlock.setGravity(Gravity.CENTER_VERTICAL);
        labelBlock.setPadding(dp(4), dp(7), dp(4), 0);
        labelBlock.setOnClickListener(toggleSelection);
        TextView label = new TextView(context);
        label.setText(photoTitle(photo));
        label.setTextColor(colors.textPrimary);
        label.setTextSize(12);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        TextView meta = new TextView(context);
        meta.setText(photoMeta(photo));
        meta.setTextColor(photo.deleted ? blend(colors.danger, colors.textSecondary, 0.42f) : colors.textMuted);
        meta.setTextSize(10);
        meta.setIncludeFontPadding(false);
        labelBlock.addView(label);
        labelBlock.addView(meta);
        tile.addView(labelBlock, matchWidthWrapContent());

        return new TileHolder(tile, image, selectionMarker);
    }

    private String photoTitle(GalleryPhoto photo) {
        if (photo.mergedRgb) {
            return "Merged " + mergedKindLabel(photo);
        }
        return (photo.deleted ? "Deleted " : "Photo ") + String.format("%02d", photo.displayIndex + 1);
    }

    private String mergedKindLabel(GalleryPhoto photo) {
        return photo.mergedKind == null || photo.mergedKind.isEmpty() ? "RGB" : photo.mergedKind;
    }

    private String photoMeta(GalleryPhoto photo) {
        if (photo.mergedRgb) {
            int start = photo.mergedSourceStartDisplayIndex + 1;
            int end = start + Math.max(0, photo.mergedSourceCount - 1);
            return photo.mergedSourceCount > 0
                    ? "Auto-merged " + String.format("%02d-%02d", start, end)
                    : "Auto-merged";
        }
        return photo.deleted ? "Recoverable slot " + (photo.physicalSlot + 1) : "Slot " + (photo.physicalSlot + 1);
    }

    private void togglePhotoSelection(GalleryPhoto photo) {
        if (gallery == null) return;
        photo.selected = !photo.selected;
        listener.onPhotoSelectionChanged(photo, photo.selected);
        int idx = gallery.photos.indexOf(photo);
        if (tileHolders != null && idx >= 0 && idx < tileHolders.length) {
            applySelectionToTile(tileHolders[idx], photo);
        }
        updateActions();
    }

    private void applySelectionToTile(TileHolder holder, GalleryPhoto photo) {
        holder.tile.setBackground(tileBackground(photo.selected, photo.deleted));
        holder.tile.setContentDescription(photoTitle(photo)
                + (photo.selected ? ", selected" : ", not selected"));
        holder.selectionMarker.setText(photo.selected ? "✓" : "");
        holder.selectionMarker.setBackground(checkBackground(photo.selected));
        holder.selectionMarker.setAlpha(photo.selected ? 1.0f : 0.74f);
        holder.selectionMarker.setContentDescription(photo.selected ? "Selected" : "Not selected");
    }

    private GradientDrawable tileBackground(boolean selected, boolean deleted) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? accentSurface : deleted ? blend(colors.danger, colors.background, 0.90f) : colors.surface);
        bg.setStroke(selected ? dp(2) : dp(1), selected ? accent : deleted ? blend(colors.danger, colors.border, 0.42f) : colors.border);
        bg.setCornerRadius(dp(8));
        return bg;
    }

    private GridLayout.LayoutParams tileParams() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = GridLayout.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        return params;
    }

    private Button smallButton(String label, View.OnClickListener listener) {
        Button button = UiStyle.button(context, label, colors.textPrimary, colors.surfaceRaised, colors.border);
        button.setTextSize(11);
        button.setPadding(dp(11), dp(7), dp(11), dp(7));
        button.setBackground(buttonBackground(colors.surfaceRaised, colors.actionPressed, colors.disabledBackground));
        button.setOnClickListener(listener);
        return button;
    }

    private void showPaletteMenu() {
        if (busy) {
            return;
        }

        int originalIndex = paletteIndex;
        boolean[] committed = new boolean[] { false };
        int popupWidth = Math.min(dp(340), root.getWidth() - dp(72));
        LinearLayout menu = new LinearLayout(context);
        menu.setOrientation(LinearLayout.VERTICAL);
        ScrollView menuScroll = new ScrollView(context);
        menuScroll.setBackground(rounded(colors.surfaceRaised, colors.borderStrong, 8, 1));
        menuScroll.addView(menu);

        PopupWindow popup = new PopupWindow(
                menuScroll,
                popupWidth,
                Math.min(dp(48) * paletteLabels.length, dp(336)),
                true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        popup.setAnimationStyle(android.R.style.Animation);
        popup.setOnDismissListener(() -> {
            if (!committed[0]) {
                listener.onPalettePreviewCanceled(originalIndex);
            }
        });

        for (int index : paletteMenuOrder()) {
            menu.addView(paletteMenuItem(index, originalIndex, popup, committed));
        }

        popup.showAsDropDown(paletteField, paletteField.getWidth() - popupWidth, dp(6));
    }

    private View paletteMenuItem(int index, int originalIndex, PopupWindow popup, boolean[] committed) {
        boolean selected = index == paletteIndex;
        LinearLayout item = row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(12), 0, dp(12), 0);
        item.setBackgroundColor(selected ? accentSurface : colors.surfaceRaised);
        item.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)));

        LinearLayout swatch = paletteSwatch(dp(56), dp(20));
        setSwatchColors(swatch, paletteColorsForIndex(index));
        item.addView(swatch, new LinearLayout.LayoutParams(dp(56), dp(20)));

        TextView label = new TextView(context);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setIncludeFontPadding(false);
        label.setTextSize(13);
        label.setText(paletteLabels[index]);
        label.setTextColor(selected ? accent : colors.textPrimary);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1);
        labelParams.setMargins(dp(10), 0, 0, 0);
        item.addView(label, labelParams);

        TextView star = new TextView(context);
        star.setText(paletteFavorites[index] ? "★" : "☆");
        star.setTextSize(22);
        star.setGravity(Gravity.CENTER);
        star.setMinWidth(dp(56));
        star.setMinHeight(dp(48));
        star.setPadding(dp(8), 0, dp(8), 0);
        star.setTextColor(paletteFavorites[index] ? accent : colors.textSecondary);
        star.setContentDescription((paletteFavorites[index] ? "Remove favorite: " : "Add favorite: ") + paletteLabels[index]);
        star.setOnClickListener(v -> {
            listener.onPaletteFavoriteToggled(index);
            star.setText(paletteFavorites[index] ? "★" : "☆");
            star.setTextColor(paletteFavorites[index] ? accent : colors.textSecondary);
            star.setContentDescription((paletteFavorites[index] ? "Remove favorite: " : "Add favorite: ") + paletteLabels[index]);
        });
        item.addView(star, new LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.MATCH_PARENT));

        item.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN && index != paletteIndex) {
                listener.onPalettePreview(index);
            }
            return false;
        });
        item.setOnClickListener(v -> {
            committed[0] = true;
            popup.dismiss();
            setPaletteIndex(index);
            if (index != originalIndex) {
                listener.onPaletteChanged(index);
            }
        });
        return item;
    }

    private int[] paletteMenuOrder() {
        int[] order = new int[paletteLabels.length];
        boolean[] added = new boolean[paletteLabels.length];
        int count = 0;

        for (int i = 0; i < paletteFavorites.length; i++) {
            if (paletteFavorites[i]) {
                order[count++] = i;
                added[i] = true;
            }
        }

        if (recentPalettes != null) {
            for (int recent : recentPalettes) {
                int index = safePaletteIndex(recent);
                if (!added[index]) {
                    order[count++] = index;
                    added[index] = true;
                }
            }
        }

        for (int i = 0; i < paletteLabels.length; i++) {
            if (!added[i]) {
                order[count++] = i;
            }
        }

        return order;
    }

    private LinearLayout paletteSwatch(int width, int height) {
        LinearLayout swatch = row();
        swatch.setClipToOutline(false);
        swatch.setBackground(rounded(colors.surface, colors.borderStrong, 4, 1));
        swatch.setPadding(dp(1), dp(1), dp(1), dp(1));
        for (int i = 0; i < 4; i++) {
            View color = new View(context);
            swatch.addView(color, new LinearLayout.LayoutParams(
                    width / 4,
                    height - dp(2),
                    1));
        }
        return swatch;
    }

    private void setSwatchColors(LinearLayout swatch, int[] palette) {
        for (int i = 0; i < swatch.getChildCount(); i++) {
            swatch.getChildAt(i).setBackgroundColor(palette[Math.min(i, palette.length - 1)]);
        }
    }

    private void updatePaletteAccent() {
        if (loadButton == null) {
            return;
        }

        loadButton.setTextColor(accent);
        loadButton.setBackground(buttonBackground(colors.surfaceRaised, accentSurface, colors.disabledBackground, accent));
        if (recoverButton != null) {
            recoverButton.setTextColor(accent);
            recoverButton.setBackground(buttonBackground(colors.surfaceRaised, accentSurface, colors.disabledBackground, accent));
        }
    }

    private void setAccentForPalette(int paletteIndex) {
        int[] palette = paletteColorsForIndex(safePaletteIndex(paletteIndex));
        accent = palette[Math.min(1, palette.length - 1)];
        accentPressed = blend(accent, Color.BLACK, 0.18f);
        accentSurface = blend(accent, colors.background, 0.72f);
        accentText = contrastText(accent);
    }

    private int[] paletteColorsForIndex(int index) {
        if (index >= 0 && index < paletteColors.length && paletteColors[index].length > 0) {
            return paletteColors[index];
        }
        return fallbackPaletteColors();
    }

    private static int[] fallbackPaletteColors() {
        return new int[] {
                0xFFFFFFFF,
                0xFFB0B0B0,
                0xFF686868,
                0xFF000000
        };
    }

    private static int blend(int foreground, int background, float backgroundAmount) {
        return UiStyle.blend(foreground, background, backgroundAmount);
    }

    private static int contrastText(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        return luminance > 0.58 ? Color.rgb(15, 23, 42) : Color.WHITE;
    }

    private void addActionButton(LinearLayout row, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(8), 0);
        row.addView(button, params);
    }

    private LinearLayout toolbarRow(String label) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));
        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextColor(colors.textMuted);
        labelView.setTextSize(10);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        labelView.setAllCaps(false);
        labelView.setGravity(Gravity.CENTER);
        labelView.setIncludeFontPadding(false);
        labelView.setPadding(dp(9), 0, dp(9), 0);
        labelView.setBackground(rounded(colors.background, colors.border, 999, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(28));
        params.setMargins(0, 0, dp(8), 0);
        row.addView(labelView, params);
        return row;
    }

    private HorizontalScrollView wrapHorizontal(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.addView(row, matchWidthWrapContent());
        return scroll;
    }

    private void setButtonAvailability(Button button, boolean enabled) {
        button.setAlpha(enabled ? 1.0f : 0.42f);
    }

    private StateListDrawable buttonBackground(int normal, int pressed, int disabled) {
        return buttonBackground(normal, pressed, disabled, colors.border);
    }

    private StateListDrawable buttonBackground(int normal, int pressed, int disabled, int stroke) {
        return UiStyle.buttonBackground(context, normal, pressed, disabled, stroke);
    }

    private StateListDrawable dangerButtonBackground() {
        return UiStyle.dangerButtonBackground(context);
    }

    private GradientDrawable chipBackground() {
        return rounded(colors.surfaceRaised, colors.border, 8, 1);
    }

    private GradientDrawable checkBackground(boolean selected) {
        return rounded(
                selected ? accent : colors.checkSurface,
                selected ? accentPressed : colors.borderStrong,
                6,
                1);
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp, int strokeDp) {
        return UiStyle.rounded(context, fill, stroke, radiusDp, strokeDp);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private static LinearLayout.LayoutParams matchWidthWrapContent() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return UiStyle.dp(context, value);
    }

    private static final class CameraImageFrame extends FrameLayout {
        CameraImageFrame(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = Math.round(width * 7f / 8f);
            super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

}
