package fyi.r0.gbxcam;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

final class MainScreen {
    interface Listener {
        void onManualMergeRequested();

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

        void onPaletteChanged(int paletteIndex);

        void onPaletteFavoriteToggled(int paletteIndex);
    }

    private final Context context;
    private Listener listener;
    private final String[] paletteLabels;
    private final int[][] paletteColors;
    private final boolean[] paletteFavorites;
    private int[] recentPalettes;
    private final UiStyle.Palette colors;
    private final LinearLayout root;
    private final TextView subtitle;
    private HorizontalScrollView albumActionsWrapper;
    private final TextView empty;
    private final TextView logs;
    private final TextView logTitle;
    private final GridLayout grid;
    private final ScrollView logScroll;
    private final FrameLayout paletteField;
    private final LinearLayout paletteSwatch;
    private final TextView paletteValue;
    private final Button loadButton;
    private final Button selectAllButton;
    private final Button selectModeButton;
    private final Button deselectAllButton;
    private final Button saveButton;
    private final Button shareButton;
    private final Button settingsButton;
    private final Button deleteButton;
    private final Button recoverButton;
    private final Button moveFirstButton;
    private final Button compactButton;
    private final Button clearAlbumButton;
    private final Button mergeButton;

    private GalleryState gallery;
    private boolean deviceConnected;
    private boolean busy;
    private boolean logsVisible;
    private boolean selectMode;
    private Dialog busyDialog;
    private LinearLayout busyContent;
    private GifView busyGif;
    private TextView busyProgressText;
    private TextView busyStatusText;
    private TextView busySlowText;
    private Runnable busySlowReveal;
    private boolean busyHasError = false;
    private int lastBusyPercent = -1;
    private int paletteIndex;
    private int accent;
    private int accentPressed;
    private int accentSurface;
    private int accentText;

    private boolean showMeta = true;
    private boolean reserveCaptionSpace;
    private Executor bitmapExecutor;
    private TileHolder[] tileHolders;
    private final AtomicInteger displayGeneration = new AtomicInteger(0);

    private static final class TileHolder {
        final GalleryPhoto photo;
        final LinearLayout tile;
        final ImageView image;
        final TextView selectionMarker;
        final LinearLayout captionBlock;
        TileHolder(GalleryPhoto photo, LinearLayout tile, ImageView image, TextView selectionMarker,
                   LinearLayout captionBlock) {
            this.photo = photo;
            this.tile = tile;
            this.image = image;
            this.selectionMarker = selectionMarker;
            this.captionBlock = captionBlock;
        }
    }

    void setBitmapExecutor(Executor executor) {
        this.bitmapExecutor = executor;
    }

    /** Sets the action listener. The listener is read lazily by click handlers, so it
     * may be assigned after construction (e.g. once the controller exists). */
    void setListener(Listener listener) {
        this.listener = listener;
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
        subtitle = new TextView(context);
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
        settingsButton = smallButton("⚙", v -> listener.onSettingsRequested());
        settingsButton.setTextSize(18);
        settingsButton.setContentDescription("Settings");
        LinearLayout.LayoutParams settingsHeaderParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        settingsHeaderParams.setMargins(dp(8), 0, 0, 0);
        header.addView(settingsButton, settingsHeaderParams);
        loadParams.setMargins(dp(8), 0, 0, 0);
        header.addView(loadButton, loadParams);
        root.addView(header, matchWidthWrapContent());

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
        selectAllButton = smallButton("Select all", v -> listener.onSelectAllRequested());
        selectModeButton = smallButton("Select", v -> {
            selectMode = true;
            updateAllTileSelectModeAppearance();
            updateActions();
        });
        deselectAllButton = smallButton("Deselect all", v -> listener.onDeselectAllRequested());
        saveButton = smallButton("Save", v -> listener.onSaveSelectedRequested());
        shareButton = smallButton("Share", v -> listener.onShareSelectedRequested());
        deleteButton = smallButton("Delete", v -> listener.onDeleteSelectedRequested());
        deleteButton.setTextColor(Color.WHITE);
        deleteButton.setBackground(dangerButtonBackground());
        addActionButton(actions, selectAllButton);
        addActionButton(actions, selectModeButton);
        addActionButton(actions, deselectAllButton);
        addActionButton(actions, saveButton);
        addActionButton(actions, shareButton);
        addActionButton(actions, deleteButton);
        root.addView(wrapHorizontal(actions), matchWidthWrapContent());

        LinearLayout albumActions = toolbarRow("Album tools");
        moveFirstButton = smallButton("Move first", v -> listener.onMoveSelectedFirstRequested());
        recoverButton = smallButton("Recover deleted", v -> listener.onRecoverSelectedRequested());
        recoverButton.setTextColor(accent);
        recoverButton.setBackground(buttonBackground(colors.surfaceRaised, accentSurface, colors.disabledBackground, accent));
        compactButton = smallButton("Compact gaps", v -> listener.onCompactAlbumRequested());
        clearAlbumButton = smallButton("Clear album", v -> listener.onClearAlbumRequested());
        clearAlbumButton.setTextColor(colors.danger);
        clearAlbumButton.setBackground(buttonBackground(colors.surfaceRaised, blend(colors.danger, colors.background, 0.82f), colors.disabledBackground, colors.danger));
        mergeButton = smallButton("Merge to RGB", v -> listener.onManualMergeRequested());
        mergeButton.setTextColor(accent);
        mergeButton.setBackground(buttonBackground(colors.surfaceRaised, accentSurface, colors.disabledBackground, accent));
        addActionButton(albumActions, moveFirstButton);
        addActionButton(albumActions, recoverButton);
        addActionButton(albumActions, mergeButton);
        addActionButton(albumActions, compactButton);
        addActionButton(albumActions, clearAlbumButton);
        albumActionsWrapper = wrapHorizontal(albumActions);
        root.addView(albumActionsWrapper, matchWidthWrapContent());

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
        empty.setText("No photos loaded yet");
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
        if (selectMode && gallery.selectedCount() == 0) selectMode = false;
        tileHolders = null;
        int gen = displayGeneration.incrementAndGet();
        setPaletteIndexOnly(gallery.paletteIndex);
        grid.removeAllViews();
        reserveCaptionSpace = hasAnyPhotoMeta(gallery);
        tileHolders = new TileHolder[gallery.photos.size()];
        for (int i = 0; i < gallery.photos.size(); i++) {
            GalleryPhoto photo = gallery.photos.get(i);
            TileHolder holder = photoTile(photo, gen);
            tileHolders[i] = holder;
            grid.addView(holder.tile, tileParams());
        }
        empty.setVisibility(gallery.photos.isEmpty() ? View.VISIBLE : View.GONE);
        grid.setVisibility(gallery.photos.isEmpty() ? View.GONE : View.VISIBLE);
        subtitle.setText((deviceConnected ? "Connected" : "Cached") + " · " + photoCount(gallery.photos.size()));
        updateActions();
    }

    void setDeviceConnected(boolean connected) {
        this.deviceConnected = connected;
        if (gallery != null) {
            subtitle.setText((connected ? "Connected" : "Cached") + " · " + photoCount(gallery.photos.size()));
        } else {
            subtitle.setText(connected ? "Device connected — tap Load Camera" : "Connect GBxCart to load photos");
        }
        updateActions();
    }

    void setBusy(boolean busy, String message) {
        this.busy = busy;
        if (busy) {
            showBusyDialog(message);
        } else if (!busyHasError) {
            dismissBusyDialog();
        }
        updateActions();
    }

    private void showBusyDialog(String message) {
        dismissBusyDialog();
        UiStyle.Palette colors = UiStyle.palette(context);
        Dialog dialog = UiStyle.baseDialog(context);
        dialog.setCancelable(false);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(32), dp(28), dp(32), dp(28));
        content.setBackground(UiStyle.rounded(context, colors.surface, colors.borderStrong, 14, 1));

        GifView gif = new GifView(context, R.raw.gbcam_logo);
        content.addView(gif, new LinearLayout.LayoutParams(dp(128), dp(64)));
        busyGif = gif;

        if (message != null && !message.isEmpty()) {
            TextView text = new TextView(context);
            text.setText(message);
            text.setTextColor(colors.textSecondary);
            text.setTextSize(13);
            text.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            textParams.setMargins(0, dp(12), 0, 0);
            content.addView(text, textParams);
        }

        TextView warnText = new TextView(context);
        warnText.setText("⚠ DO NOT DISCONNECT");
        warnText.setTextColor(colors.danger);
        warnText.setTypeface(Typeface.DEFAULT_BOLD);
        warnText.setTextSize(11);
        warnText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams warnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        warnParams.setMargins(0, dp(10), 0, 0);
        content.addView(warnText, warnParams);

        busyProgressText = new TextView(context);
        busyProgressText.setTextSize(32);
        busyProgressText.setTypeface(Typeface.DEFAULT_BOLD);
        busyProgressText.setTextColor(accent);
        busyProgressText.setGravity(Gravity.CENTER);
        busyProgressText.setText("0%");
        busyProgressText.setVisibility(View.VISIBLE);
        lastBusyPercent = 0;
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressParams.setMargins(0, dp(8), 0, 0);
        content.addView(busyProgressText, progressParams);

        busyStatusText = new TextView(context);
        busyStatusText.setTextSize(11);
        busyStatusText.setTextColor(colors.textMuted);
        busyStatusText.setGravity(Gravity.CENTER);
        busyStatusText.setSingleLine(true);
        busyStatusText.setEllipsize(TextUtils.TruncateAt.END);
        busyStatusText.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(6), 0, 0);
        content.addView(busyStatusText, statusParams);

        busySlowText = new TextView(context);
        busySlowText.setText("Please stand by... Game Boy Camera cartridges run on battery-backed SRAM from the '90s — they write one byte at a time. Vintage hardware, vintage speed. 🎮");
        busySlowText.setTextColor(colors.textMuted);
        busySlowText.setTextSize(11);
        busySlowText.setGravity(Gravity.CENTER);
        busySlowText.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams slowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slowParams.setMargins(0, dp(10), 0, 0);
        content.addView(busySlowText, slowParams);

        busySlowReveal = () -> {
            if (busySlowText != null) {
                busySlowText.setVisibility(View.VISIBLE);
            }
        };
        root.postDelayed(busySlowReveal, 20_000);

        busyContent = content;
        dialog.setContentView(content);
        UiStyle.sizeDialog(dialog, context, 48, 340);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        busyDialog = dialog;
    }

    private void dismissBusyDialog() {
        if (busySlowReveal != null) {
            root.removeCallbacks(busySlowReveal);
        }
        if (busyDialog != null) {
            busyDialog.dismiss();
            busyDialog = null;
        }
        busyContent = null;
        busyGif = null;
        busyProgressText = null;
        busyStatusText = null;
        busySlowText = null;
        busySlowReveal = null;
        busyHasError = false;
        lastBusyPercent = -1;
    }

    void showBusyError(String errorMessage) {
        if (busyContent == null || busyHasError) return;
        busyHasError = true;
        if (busyGif != null) {
            busyGif.setVisibility(View.GONE);
        }
        if (busyProgressText != null) {
            busyProgressText.setVisibility(View.GONE);
        }

        UiStyle.Palette colors = UiStyle.palette(context);

        TextView errorText = new TextView(context);
        errorText.setText(errorMessage);
        errorText.setTextColor(colors.danger);
        errorText.setTextSize(13);
        errorText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        errorParams.setMargins(0, dp(12), 0, 0);
        busyContent.addView(errorText, errorParams);

        Button dismissButton = UiStyle.button(context, "Dismiss", Color.WHITE, colors.danger, colors.danger);
        dismissButton.setBackground(UiStyle.dangerButtonBackground(context));
        LinearLayout.LayoutParams dismissParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        dismissParams.setMargins(0, dp(16), 0, 0);
        dismissButton.setOnClickListener(v -> {
            dismissBusyDialog();
            updateActions();
        });
        busyContent.addView(dismissButton, dismissParams);
        if (busyDialog != null) {
            UiStyle.sizeDialog(busyDialog, context, 48, 340);
        }
    }

    void updateBusyProgress(String message) {
        if (busyProgressText == null || busyHasError) return;
        int percent = parsePercent(message);
        if (percent > lastBusyPercent) {
            lastBusyPercent = percent;
            busyProgressText.setText(percent + "%");
            busyProgressText.setVisibility(View.VISIBLE);
        }
        if (busyStatusText != null && !message.startsWith("[debug]")) {
            busyStatusText.setText(message);
            busyStatusText.setVisibility(View.VISIBLE);
        }
    }

    private static final java.util.regex.Pattern PERCENT_PATTERN =
            java.util.regex.Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    private static int parsePercent(String message) {
        if (message == null) return -1;
        java.util.regex.Matcher m = PERCENT_PATTERN.matcher(message);
        if (m.find()) {
            try {
                int current = Integer.parseInt(m.group(1));
                int total   = Integer.parseInt(m.group(2));
                // Require denominator >= 5 to exclude small step-count patterns like "1/3"
                if (total >= 5 && current <= total) {
                    return Math.min(100, (int) (100.0 * current / total));
                }
            } catch (NumberFormatException ignored) {}
        }
        return -1;
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
        applyPaletteIndex(this.paletteIndex);
        if (gallery != null) {
            gallery = gallery.withPalette(this.paletteIndex, paletteLabels[this.paletteIndex]);
            refreshGalleryPalette(this.paletteIndex);
        }
    }

    private void setPaletteIndexOnly(int paletteIndex) {
        this.paletteIndex = safePaletteIndex(paletteIndex);
        applyPaletteIndex(this.paletteIndex);
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

    private void setLogsVisible(boolean visible) {
        logsVisible = visible;
        int visibility = visible ? View.VISIBLE : View.GONE;
        logTitle.setVisibility(visibility);
        logScroll.setVisibility(visibility);
    }

    void setLogsVisibleFromSettings(boolean visible) {
        setLogsVisible(visible);
    }

    void setShowMeta(boolean show) {
        showMeta = show;
        if (tileHolders == null) {
            return;
        }
        for (TileHolder holder : tileHolders) {
            applyCaptionVisibility(holder);
        }
    }

    String getLogs() {
        return logs.getText().toString();
    }

    boolean isBusy() {
        return busy;
    }

    GalleryState gallery() {
        return gallery;
    }

    int accentColor() {
        return accent;
    }

    boolean isSelectMode() {
        return selectMode;
    }

    void selectAll(boolean selected) {
        if (gallery == null) return;
        if (selected) {
            selectMode = true;
            for (int i = 0; i < gallery.photos.size(); i++) {
                GalleryPhoto photo = gallery.photos.get(i);
                photo.selected = true;
                if (tileHolders != null && i < tileHolders.length) {
                    applySelectionToTile(tileHolders[i], photo);
                }
            }
            updateActions();
        } else {
            for (GalleryPhoto photo : gallery.photos) photo.selected = false;
            exitSelectMode();
        }
    }

    void updateActions() {
        int selected = gallery == null ? 0 : gallery.selectedCount();
        int selectedActive = gallery == null ? 0 : gallery.selectedActiveCount();
        int selectedDeleted = gallery == null ? 0 : gallery.selectedDeletedCount();
        int selectedManualMerges = gallery == null ? 0 : gallery.selectedManualMergeCount();
        boolean showDelete = selectedActive > 0 || selectedManualMerges > 0;
        boolean showRecover = selectedDeleted > 0;
        int total = gallery == null ? 0 : gallery.photos.size();

        loadButton.setEnabled(!busy);
        selectAllButton.setEnabled(!busy && total > 0 && selected < total);
        selectAllButton.setVisibility(total > 0 && selected < total ? View.VISIBLE : View.GONE);
        selectModeButton.setEnabled(!busy && total > 0);
        selectModeButton.setVisibility(!selectMode && total > 0 ? View.VISIBLE : View.GONE);
        deselectAllButton.setEnabled(!busy && selected > 0);
        deselectAllButton.setVisibility(selected > 0 ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!busy && selected > 0);
        saveButton.setVisibility(selected > 0 ? View.VISIBLE : View.GONE);
        shareButton.setEnabled(!busy && selected > 0);
        shareButton.setVisibility(selected > 0 ? View.VISIBLE : View.GONE);
        settingsButton.setEnabled(!busy);
        deleteButton.setEnabled(!busy && showDelete);
        deleteButton.setVisibility(showDelete ? View.VISIBLE : View.GONE);
        recoverButton.setEnabled(!busy && showRecover);
        recoverButton.setVisibility(showRecover ? View.VISIBLE : View.GONE);
        moveFirstButton.setEnabled(!busy && deviceConnected && selectedActive > 0);
        moveFirstButton.setVisibility(deviceConnected && selectedActive > 0 ? View.VISIBLE : View.GONE);
        compactButton.setEnabled(false);
        compactButton.setVisibility(View.GONE);
        clearAlbumButton.setEnabled(!busy && deviceConnected && total > 0);
        clearAlbumButton.setVisibility(deviceConnected && total > 0 ? View.VISIBLE : View.GONE);
        int selectedMergeable = gallery == null ? 0 : gallery.selectedMergeableCount();
        boolean canMerge = !busy && (selectedMergeable == 3 || selectedMergeable == 4);
        mergeButton.setEnabled(canMerge);
        mergeButton.setVisibility(canMerge ? View.VISIBLE : View.GONE);
        mergeButton.setText(selectedMergeable == 4 ? "Merge to CRGB" : "Merge to RGB");
        paletteField.setEnabled(!busy);
        paletteField.setAlpha(busy ? 0.42f : 1.0f);
        setButtonAvailability(loadButton, !busy);
        setButtonAvailability(selectAllButton, !busy && total > 0 && selected < total);
        setButtonAvailability(selectModeButton, !busy && total > 0);
        setButtonAvailability(deselectAllButton, !busy && selected > 0);
        setButtonAvailability(saveButton, !busy && selected > 0);
        setButtonAvailability(shareButton, !busy && selected > 0);
        setButtonAvailability(settingsButton, !busy);
        setButtonAvailability(recoverButton, !busy && showRecover);
        setButtonAvailability(moveFirstButton, !busy && deviceConnected && selectedActive > 0);
        setButtonAvailability(compactButton, false);
        setButtonAvailability(clearAlbumButton, !busy && deviceConnected && total > 0);
        setButtonAvailability(mergeButton, canMerge);
        deleteButton.setTextColor(!busy && showDelete ? Color.WHITE : colors.disabledText);
        boolean anyAlbumAction = moveFirstButton.getVisibility() == View.VISIBLE
                || recoverButton.getVisibility() == View.VISIBLE
                || mergeButton.getVisibility() == View.VISIBLE
                || clearAlbumButton.getVisibility() == View.VISIBLE;
        if (albumActionsWrapper != null) albumActionsWrapper.setVisibility(anyAlbumAction ? View.VISIBLE : View.GONE);
    }

    private TileHolder photoTile(GalleryPhoto photo, int gen) {
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(6), dp(6), dp(6), dp(6));
        tile.setBackground(tileBackground(photo.selected, photo.deleted));
        tile.setContentDescription(photoAccessibilityLabel(photo)
                + (photo.selected ? ", selected" : ", not selected"));

        View.OnClickListener tileClick = v -> {
            if (selectMode) togglePhotoSelection(photo);
            else listener.onPhotoOpenRequested(photo);
        };
        View.OnLongClickListener tileLongPress = v -> {
            if (!selectMode) {
                selectMode = true;
                updateAllTileSelectModeAppearance();
            }
            togglePhotoSelection(photo);
            return true;
        };
        tile.setOnClickListener(tileClick);
        tile.setOnLongClickListener(tileLongPress);

        FrameLayout imageFrame = new CameraImageFrame(context);
        imageFrame.setOnClickListener(tileClick);
        imageFrame.setOnLongClickListener(tileLongPress);
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(false);
        image.setAlpha(photo.deleted ? 0.72f : 1.0f);
        image.setBackgroundColor(colors.photoBackground);
        renderPhotoInto(photo, image, gen, paletteIndex);
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
        if (selectMode) {
            selectionMarker.setAlpha(photo.selected ? 1.0f : 0.74f);
        } else {
            selectionMarker.setAlpha(0f);
        }
        selectionMarker.setOnClickListener(tileClick);
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

        LinearLayout captionBlock = captionBlock(photo, tileClick);
        tile.addView(captionBlock, matchWidthWrapContent());

        return new TileHolder(photo, tile, image, selectionMarker, captionBlock);
    }

    private String photoTitle(GalleryPhoto photo) {
        if (photo.mergedRgb) {
            return (photo.deleted ? "Deleted " : "Merged ") + mergedKindLabel(photo);
        }
        return (photo.deleted ? "Deleted " : "Photo ") + String.format(Locale.US, "%02d", photo.displayIndex + 1);
    }

    private String mergedKindLabel(GalleryPhoto photo) {
        return photo.mergedKind == null || photo.mergedKind.isEmpty() ? "RGB" : photo.mergedKind;
    }

    private String photoMeta(GalleryPhoto photo) {
        if (photo.mergedRgb) {
            if (photo.deleted) return "Deleted merge · recoverable";
            String prefix = photo.isManualMerge() ? "Manual" : "Auto";
            StringBuilder meta = new StringBuilder(prefix);
            if (photo.mergedAlgorithm != null && !photo.mergedAlgorithm.isEmpty()) {
                meta.append(' ').append(compactAlgorithmLabel(photo.mergedAlgorithm));
            }
            if (photo.mergedSourceCount > 0) {
                meta.append(' ').append(mergedSourceRange(photo));
            }
            return meta.toString();
        }
        return photo.deleted ? "Recoverable" : "";
    }

    private boolean hasAnyPhotoMeta(GalleryState gallery) {
        for (GalleryPhoto photo : gallery.photos) {
            if (!photoMeta(photo).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private LinearLayout captionBlock(GalleryPhoto photo, View.OnClickListener tileClick) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_VERTICAL);
        block.setPadding(dp(4), dp(6), dp(4), 0);
        block.setOnClickListener(tileClick);

        TextView caption = new TextView(context);
        caption.setText(photoMeta(photo));
        caption.setTextColor(photo.deleted ? blend(colors.danger, colors.textSecondary, 0.42f)
                : photo.mergedRgb ? colors.textPrimary : colors.textMuted);
        caption.setTextSize(10);
        caption.setIncludeFontPadding(false);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setMinHeight(dp(13));
        block.addView(caption);

        block.setVisibility(captionVisibility());
        return block;
    }

    private void applyCaptionVisibility(TileHolder holder) {
        if (holder == null || holder.captionBlock == null) {
            return;
        }
        holder.captionBlock.setVisibility(captionVisibility());
    }

    private int captionVisibility() {
        return showMeta && reserveCaptionSpace ? View.VISIBLE : View.GONE;
    }

    private String photoAccessibilityLabel(GalleryPhoto photo) {
        StringBuilder label = new StringBuilder(photoTitle(photo));
        if (photo.mergedRgb) {
            String meta = photoMeta(photo);
            if (!meta.isEmpty()) label.append(", ").append(meta);
        } else if (photo.isAlbumBacked()) {
            label.append(", slot ").append(photo.physicalSlot + 1);
        }
        return label.toString();
    }

    private String mergedSourceRange(GalleryPhoto photo) {
        int start = photo.mergedSourceStartDisplayIndex + 1;
        int end = start + Math.max(0, photo.mergedSourceCount - 1);
        return String.format(Locale.US, "%02d-%02d", start, end);
    }

    private String compactAlgorithmLabel(String id) {
        if (RgbMergeDetector.ALGO_BASIC.equals(id)) return "Basic";
        if (RgbMergeDetector.ALGO_CLEAR_LUM.equals(id)) return "Clear";
        if (RgbMergeDetector.ALGO_NORM.equals(id)) return "Norm";
        if (RgbMergeDetector.ALGO_NORM_CLEAR_LUM.equals(id)) return "N+Clr";
        if (RgbMergeDetector.ALGO_SAT_BOOST.equals(id)) return "Sat";
        if (RgbMergeDetector.ALGO_ADAPTIVE.equals(id)) return "Adapt";
        return id;
    }

    private void togglePhotoSelection(GalleryPhoto photo) {
        if (gallery == null) return;
        photo.selected = !photo.selected;
        listener.onPhotoSelectionChanged(photo, photo.selected);
        int idx = gallery.photos.indexOf(photo);
        if (tileHolders != null && idx >= 0 && idx < tileHolders.length) {
            applySelectionToTile(tileHolders[idx], photo);
        }
        if (selectMode && gallery.selectedCount() == 0) {
            exitSelectMode();
        } else {
            updateActions();
        }
    }

    private void applySelectionToTile(TileHolder holder, GalleryPhoto photo) {
        holder.tile.setBackground(tileBackground(photo.selected, photo.deleted));
        holder.tile.setContentDescription(photoAccessibilityLabel(photo)
                + (photo.selected ? ", selected" : ", not selected"));
        holder.selectionMarker.setTextColor(accentText);
        holder.selectionMarker.setText(photo.selected ? "✓" : "");
        holder.selectionMarker.setBackground(checkBackground(photo.selected));
        if (selectMode) {
            holder.selectionMarker.setAlpha(photo.selected ? 1.0f : 0.74f);
        } else {
            holder.selectionMarker.setAlpha(0f);
        }
        holder.selectionMarker.setContentDescription(photo.selected ? "Selected" : "Not selected");
    }

    private void exitSelectMode() {
        selectMode = false;
        updateAllTileSelectModeAppearance();
        updateActions();
    }

    private void updateAllTileSelectModeAppearance() {
        if (tileHolders == null) return;
        for (TileHolder holder : tileHolders) {
            if (holder != null) applySelectionToTile(holder, holder.photo);
        }
    }

    private void refreshGalleryPalette(int paletteIndex) {
        if (tileHolders == null) {
            return;
        }

        int gen = displayGeneration.incrementAndGet();
        for (TileHolder holder : tileHolders) {
            if (holder == null) {
                continue;
            }
            renderPhotoInto(holder.photo, holder.image, gen, paletteIndex);
            applySelectionToTile(holder, holder.photo);
        }
    }

    private void renderPhotoInto(GalleryPhoto photo, ImageView image, int gen, int paletteIndex) {
        int[] palette = paletteColorsForIndex(paletteIndex);
        if (bitmapExecutor != null) {
            bitmapExecutor.execute(() -> {
                Bitmap bmp = PhotoRenderer.renderBitmap(photo, palette);
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
            image.setImageBitmap(PhotoRenderer.renderBitmap(photo, palette));
        }
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
        for (int index : paletteMenuOrder()) {
            menu.addView(paletteMenuItem(index, originalIndex, popup));
        }

        popup.showAsDropDown(paletteField, paletteField.getWidth() - popupWidth, dp(6));
    }

    private View paletteMenuItem(int index, int originalIndex, PopupWindow popup) {
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

        item.setOnClickListener(v -> {
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
        if (mergeButton != null) {
            mergeButton.setTextColor(accent);
            mergeButton.setBackground(buttonBackground(colors.surfaceRaised, accentSurface, colors.disabledBackground, accent));
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

    private static String photoCount(int n) {
        return n + (n == 1 ? " photo" : " photos");
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
