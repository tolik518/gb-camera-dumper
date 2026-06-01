package fyi.r0.gbxcam;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

final class MainScreen {
    interface Listener {
        void onLoadRequested();

        void onSelectAllRequested();

        void onDeselectAllRequested();

        void onSaveSelectedRequested();

        void onDeleteSelectedRequested();

        void onPhotoSelectionChanged(GalleryPhoto photo, boolean selected);

        void onPaletteChanged(int paletteIndex);
    }

    private final Context context;
    private final Listener listener;
    private final String[] paletteLabels;
    private final int[][] paletteColors;
    private final Palette colors;
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
    private final Button deleteButton;

    private GalleryState gallery;
    private boolean busy;
    private boolean logsVisible;
    private int paletteIndex;
    private int accent;
    private int accentPressed;
    private int accentSurface;
    private int accentText;

    MainScreen(Context context, Listener listener, String[] paletteLabels, int[][] paletteColors, int defaultPaletteIndex) {
        this.context = context;
        this.listener = listener;
        this.paletteLabels = paletteLabels.length == 0 ? new String[] { "Monochrome - Grayscale" } : paletteLabels;
        this.paletteColors = paletteColors.length == 0 ? new int[][] { fallbackPaletteColors() } : paletteColors;
        this.colors = Palette.from(context);

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

        loadButton = new Button(context);
        loadButton.setText("Load Camera");
        loadButton.setAllCaps(false);
        loadButton.setTextSize(12);
        loadButton.setMinHeight(0);
        loadButton.setMinWidth(0);
        loadButton.setMinimumHeight(0);
        loadButton.setMinimumWidth(0);
        loadButton.setIncludeFontPadding(false);
        loadButton.setPadding(dp(14), dp(9), dp(14), dp(9));
        loadButton.setTextColor(accent);
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
        loadingText.setPadding(16, 0, 0, 0);
        loadingRow.addView(loadingText);
        root.addView(loadingRow, matchWidthWrapContent());

        LinearLayout paletteRow = row();
        paletteRow.setGravity(Gravity.CENTER_VERTICAL);
        paletteRow.setPadding(0, dp(4), 0, dp(5));
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

        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        selectAllButton = smallButton("Select all", v -> listener.onSelectAllRequested());
        deselectAllButton = smallButton("Deselect", v -> listener.onDeselectAllRequested());
        saveButton = smallButton("Save selected", v -> listener.onSaveSelectedRequested());
        deleteButton = smallButton("Delete selected", v -> listener.onDeleteSelectedRequested());
        deleteButton.setTextColor(Color.WHITE);
        deleteButton.setBackgroundColor(colors.danger);
        addActionButton(actions, selectAllButton);
        addActionButton(actions, deselectAllButton);
        addActionButton(actions, saveButton);
        //actions.addView(deleteButton);
        selection = new TextView(context);
        selection.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        selection.setTextSize(12);
        selection.setTextColor(colors.textSecondary);
        selection.setPadding(dp(6), 0, 0, 0);
        actions.addView(selection, new LinearLayout.LayoutParams(
                0,
                dp(34),
                1));
        root.addView(actions, matchWidthWrapContent());

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
        empty.setPadding(0, 80, 0, 80);

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
        logTitle.setPadding(0, 8, 0, 4);
        root.addView(logTitle, matchWidthWrapContent());

        logs = new TextView(context);
        logs.setTextSize(12);
        logs.setTextColor(colors.logText);
        logs.setTextIsSelectable(true);
        logs.setPadding(12, 12, 12, 12);
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
        setPaletteIndex(gallery.paletteIndex);
        grid.removeAllViews();
        for (GalleryPhoto photo : gallery.photos) {
            grid.addView(photoTile(photo), tileParams());
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
        setAccentForPalette(this.paletteIndex);
        paletteValue.setText(paletteLabels[this.paletteIndex]);
        setSwatchColors(paletteSwatch, paletteColorsForIndex(this.paletteIndex));
        updatePaletteAccent();
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

    GalleryState gallery() {
        return gallery;
    }

    void selectAll(boolean selected) {
        if (gallery == null) {
            return;
        }
        for (GalleryPhoto photo : gallery.photos) {
            photo.selected = selected;
        }
        showGallery(gallery);
    }

    void updateActions() {
        int selected = gallery == null ? 0 : gallery.selectedCount();
        int total = gallery == null ? 0 : gallery.photos.size();
        selection.setText(total == 0 ? "No photos" : selected == 0 ? "0 selected" : selected + " of " + total + " selected");

        loadButton.setEnabled(!busy);
        selectAllButton.setEnabled(!busy && total > 0 && selected < total);
        selectAllButton.setVisibility(total > 0 && selected < total ? View.VISIBLE : View.GONE);
        deselectAllButton.setEnabled(!busy && selected > 0);
        deselectAllButton.setVisibility(selected > 0 ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!busy && selected > 0);
        saveButton.setVisibility(selected > 0 ? View.VISIBLE : View.GONE);
        deleteButton.setEnabled(!busy && selected > 0);
        paletteField.setEnabled(!busy);
        paletteField.setAlpha(busy ? 0.42f : 1.0f);
        setButtonAvailability(loadButton, !busy);
        setButtonAvailability(selectAllButton, !busy && total > 0 && selected < total);
        setButtonAvailability(deselectAllButton, !busy && selected > 0);
        setButtonAvailability(saveButton, !busy && selected > 0);
        if (!busy && selected > 0) {
            deleteButton.setTextColor(Color.WHITE);
            deleteButton.setBackgroundColor(colors.danger);
        } else {
            deleteButton.setTextColor(colors.disabledText);
            deleteButton.setBackgroundColor(colors.disabledBackground);
        }
    }

    private View photoTile(GalleryPhoto photo) {
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(6), dp(6), dp(6), dp(7));
        tile.setBackground(tileBackground(photo.selected));
        tile.setContentDescription("Photo " + String.format("%02d", photo.displayIndex + 1)
                + (photo.selected ? ", selected" : ", not selected"));
        tile.setOnClickListener(v -> {
            photo.selected = !photo.selected;
            listener.onPhotoSelectionChanged(photo, photo.selected);
            showGallery(gallery);
        });

        FrameLayout imageFrame = new CameraImageFrame(context);
        ImageView image = new ImageView(context);
        image.setImageBitmap(BitmapFactory.decodeFile(photo.path));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(false);
        image.setBackgroundColor(colors.photoBackground);
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
        selectionMarker.setVisibility(photo.selected ? View.VISIBLE : View.GONE);
        selectionMarker.setOnClickListener(v -> {
            photo.selected = !photo.selected;
            listener.onPhotoSelectionChanged(photo, photo.selected);
            showGallery(gallery);
        });
        FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(dp(26), dp(26));
        checkParams.gravity = Gravity.TOP | Gravity.START;
        checkParams.setMargins(dp(6), dp(6), 0, 0);
        imageFrame.addView(selectionMarker, checkParams);
        tile.addView(imageFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(6), dp(4), 0);
        TextView label = new TextView(context);
        label.setText("Photo " + String.format("%02d", photo.displayIndex + 1));
        label.setTextColor(colors.textPrimary);
        label.setTextSize(12);
        row.addView(label);
        tile.addView(row, matchWidthWrapContent());

        return tile;
    }

    private GradientDrawable tileBackground(boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? accentSurface : colors.surface);
        bg.setStroke(selected ? dp(2) : dp(1), selected ? accent : colors.border);
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
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setMinimumHeight(0);
        button.setMinimumWidth(0);
        button.setIncludeFontPadding(false);
        button.setPadding(dp(11), dp(7), dp(11), dp(7));
        button.setTextColor(colors.textPrimary);
        button.setBackground(buttonBackground(colors.surfaceRaised, colors.actionPressed, colors.disabledBackground));
        button.setOnClickListener(listener);
        return button;
    }

    private void showPaletteMenu() {
        if (busy) {
            return;
        }

        int popupWidth = Math.min(dp(340), root.getWidth() - dp(72));
        LinearLayout menu = new LinearLayout(context);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackground(rounded(colors.surfaceRaised, colors.borderStrong, 8, 1));
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

        for (int i = 0; i < paletteLabels.length; i++) {
            int index = i;
            View item = paletteMenuItem(index);
            item.setOnClickListener(v -> {
                popup.dismiss();
                if (index != paletteIndex) {
                    setPaletteIndex(index);
                    listener.onPaletteChanged(index);
                }
            });
            menu.addView(item);
        }

        popup.showAsDropDown(paletteField, paletteField.getWidth() - popupWidth, dp(6));
    }

    private View paletteMenuItem(int index) {
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
        return item;
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
        float foregroundAmount = 1.0f - backgroundAmount;
        return Color.rgb(
                Math.round(Color.red(foreground) * foregroundAmount + Color.red(background) * backgroundAmount),
                Math.round(Color.green(foreground) * foregroundAmount + Color.green(background) * backgroundAmount),
                Math.round(Color.blue(foreground) * foregroundAmount + Color.blue(background) * backgroundAmount));
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

    private void setButtonAvailability(Button button, boolean enabled) {
        button.setAlpha(enabled ? 1.0f : 0.42f);
    }

    private StateListDrawable buttonBackground(int normal, int pressed, int disabled) {
        return buttonBackground(normal, pressed, disabled, colors.border);
    }

    private StateListDrawable buttonBackground(int normal, int pressed, int disabled, int stroke) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { -android.R.attr.state_enabled }, rounded(disabled, colors.border, 6, 1));
        states.addState(new int[] { android.R.attr.state_pressed }, rounded(pressed, colors.borderStrong, 6, 1));
        states.addState(new int[] {}, rounded(normal, stroke, 6, 1));
        return states;
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
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setStroke(dp(strokeDp), stroke);
        bg.setCornerRadius(dp(radiusDp));
        return bg;
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
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
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

    private static final class Palette {
        final int background;
        final int surface;
        final int surfaceRaised;
        final int logBackground;
        final int photoBackground;
        final int textPrimary;
        final int textSecondary;
        final int textMuted;
        final int logText;
        final int border;
        final int borderStrong;
        final int selectedBackground;
        final int selectedBorder;
        final int primary;
        final int primaryPressed;
        final int primaryButtonText;
        final int actionPressed;
        final int checkSurface;
        final int danger;
        final int disabledText;
        final int disabledBackground;

        private Palette(
                int background,
                int surface,
                int surfaceRaised,
                int logBackground,
                int photoBackground,
                int textPrimary,
                int textSecondary,
                int textMuted,
                int logText,
                int border,
                int borderStrong,
                int selectedBackground,
                int selectedBorder,
                int primary,
                int primaryPressed,
                int primaryButtonText,
                int actionPressed,
                int checkSurface,
                int danger,
                int disabledText,
                int disabledBackground) {
            this.background = background;
            this.surface = surface;
            this.surfaceRaised = surfaceRaised;
            this.logBackground = logBackground;
            this.photoBackground = photoBackground;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.textMuted = textMuted;
            this.logText = logText;
            this.border = border;
            this.borderStrong = borderStrong;
            this.selectedBackground = selectedBackground;
            this.selectedBorder = selectedBorder;
            this.primary = primary;
            this.primaryPressed = primaryPressed;
            this.primaryButtonText = primaryButtonText;
            this.actionPressed = actionPressed;
            this.checkSurface = checkSurface;
            this.danger = danger;
            this.disabledText = disabledText;
            this.disabledBackground = disabledBackground;
        }

        static Palette from(Context context) {
            int nightMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
                return new Palette(
                        Color.rgb(15, 23, 42),
                        Color.rgb(30, 41, 59),
                        Color.rgb(37, 52, 76),
                        Color.rgb(2, 6, 23),
                        Color.rgb(15, 23, 42),
                        Color.rgb(241, 245, 249),
                        Color.rgb(203, 213, 225),
                        Color.rgb(148, 163, 184),
                        Color.rgb(226, 232, 240),
                        Color.rgb(51, 65, 85),
                        Color.rgb(71, 85, 105),
                        Color.rgb(19, 54, 39),
                        Color.rgb(74, 222, 128),
                        Color.rgb(74, 222, 128),
                        Color.rgb(34, 197, 94),
                        Color.rgb(5, 46, 22),
                        Color.rgb(51, 65, 85),
                        Color.rgb(15, 23, 42),
                        Color.rgb(220, 38, 38),
                        Color.rgb(100, 116, 139),
                        Color.rgb(30, 41, 59));
            }

            return new Palette(
                    Color.rgb(248, 250, 252),
                    Color.WHITE,
                    Color.rgb(241, 245, 249),
                    Color.WHITE,
                    Color.WHITE,
                    Color.rgb(15, 23, 42),
                    Color.rgb(71, 85, 105),
                    Color.rgb(100, 116, 139),
                    Color.rgb(39, 39, 42),
                    Color.rgb(226, 232, 240),
                    Color.rgb(203, 213, 225),
                    Color.rgb(240, 253, 244),
                    Color.rgb(22, 163, 74),
                    Color.rgb(22, 163, 74),
                    Color.rgb(21, 128, 61),
                    Color.WHITE,
                    Color.rgb(226, 232, 240),
                    Color.WHITE,
                    Color.rgb(185, 28, 28),
                    Color.rgb(148, 163, 184),
                    Color.rgb(241, 245, 249));
        }
    }
}
