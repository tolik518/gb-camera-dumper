package com.tolik518.gbcam;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    }

    private final Context context;
    private final Listener listener;
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
    private final Button loadButton;
    private final Button selectAllButton;
    private final Button deselectAllButton;
    private final Button saveButton;
    private final Button deleteButton;

    private GalleryState gallery;
    private boolean busy;
    private boolean logsVisible;

    MainScreen(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.colors = Palette.from(context);

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 20);
        root.setBackgroundColor(colors.background);

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBlock = new LinearLayout(context);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText("GBxCAM Viewer");
        title.setTextSize(26);
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
        loadButton.setOnClickListener(v -> listener.onLoadRequested());
        header.addView(loadButton);
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

        LinearLayout actions = row();
        selectAllButton = smallButton("Select all", v -> listener.onSelectAllRequested());
        deselectAllButton = smallButton("Deselect", v -> listener.onDeselectAllRequested());
        saveButton = smallButton("Save selected", v -> listener.onSaveSelectedRequested());
        deleteButton = smallButton("Delete selected", v -> listener.onDeleteSelectedRequested());
        deleteButton.setTextColor(Color.WHITE);
        deleteButton.setBackgroundColor(colors.danger);
        actions.addView(selectAllButton);
        actions.addView(deselectAllButton);
        actions.addView(saveButton);
        //actions.addView(deleteButton);
        root.addView(actions, matchWidthWrapContent());

        selection = new TextView(context);
        selection.setTextSize(13);
        selection.setTextColor(colors.textSecondary);
        selection.setPadding(0, 8, 0, 8);
        root.addView(selection, matchWidthWrapContent());

        grid = new GridLayout(context);
        grid.setColumnCount(3);
        grid.setUseDefaultMargins(false);
        grid.setPadding(0, 4, 0, 4);

        empty = new TextView(context);
        empty.setText("No camera photos loaded yet.");
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(colors.textMuted);
        empty.setPadding(0, 80, 0, 80);

        LinearLayout galleryContainer = new LinearLayout(context);
        galleryContainer.setOrientation(LinearLayout.VERTICAL);
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
        selection.setText(total == 0 ? "No photos selected." : selected + " of " + total + " selected.");

        loadButton.setEnabled(!busy);
        selectAllButton.setEnabled(!busy && total > 0 && selected < total);
        deselectAllButton.setEnabled(!busy && selected > 0);
        saveButton.setEnabled(!busy && selected > 0);
        deleteButton.setEnabled(!busy && selected > 0);
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
        tile.setPadding(8, 8, 8, 8);
        tile.setBackground(tileBackground(photo.selected));
        tile.setOnClickListener(v -> {
            photo.selected = !photo.selected;
            listener.onPhotoSelectionChanged(photo, photo.selected);
            showGallery(gallery);
        });

        ImageView image = new ImageView(context);
        image.setImageURI(Uri.fromFile(new File(photo.path)));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(false);
        image.setBackgroundColor(colors.photoBackground);
        tile.addView(image, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(106)));

        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox check = new CheckBox(context);
        check.setChecked(photo.selected);
        check.setOnClickListener(v -> {
            photo.selected = ((CheckBox) v).isChecked();
            listener.onPhotoSelectionChanged(photo, photo.selected);
            showGallery(gallery);
        });
        row.addView(check);
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
        bg.setColor(selected ? colors.selectedBackground : colors.surface);
        bg.setStroke(dp(1), selected ? colors.selectedBorder : colors.border);
        bg.setCornerRadius(dp(8));
        return bg;
    }

    private GridLayout.LayoutParams tileParams() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = GridLayout.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(4, 4, 4, 4);
        return params;
    }

    private Button smallButton(String label, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
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

    private static final class Palette {
        final int background;
        final int surface;
        final int logBackground;
        final int photoBackground;
        final int textPrimary;
        final int textSecondary;
        final int textMuted;
        final int logText;
        final int border;
        final int selectedBackground;
        final int selectedBorder;
        final int danger;
        final int disabledText;
        final int disabledBackground;

        private Palette(
                int background,
                int surface,
                int logBackground,
                int photoBackground,
                int textPrimary,
                int textSecondary,
                int textMuted,
                int logText,
                int border,
                int selectedBackground,
                int selectedBorder,
                int danger,
                int disabledText,
                int disabledBackground) {
            this.background = background;
            this.surface = surface;
            this.logBackground = logBackground;
            this.photoBackground = photoBackground;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.textMuted = textMuted;
            this.logText = logText;
            this.border = border;
            this.selectedBackground = selectedBackground;
            this.selectedBorder = selectedBorder;
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
                        Color.rgb(2, 6, 23),
                        Color.rgb(15, 23, 42),
                        Color.rgb(241, 245, 249),
                        Color.rgb(203, 213, 225),
                        Color.rgb(148, 163, 184),
                        Color.rgb(226, 232, 240),
                        Color.rgb(51, 65, 85),
                        Color.rgb(30, 58, 138),
                        Color.rgb(96, 165, 250),
                        Color.rgb(220, 38, 38),
                        Color.rgb(100, 116, 139),
                        Color.rgb(30, 41, 59));
            }

            return new Palette(
                    Color.rgb(248, 250, 252),
                    Color.WHITE,
                    Color.WHITE,
                    Color.WHITE,
                    Color.rgb(15, 23, 42),
                    Color.rgb(71, 85, 105),
                    Color.rgb(100, 116, 139),
                    Color.rgb(39, 39, 42),
                    Color.rgb(226, 232, 240),
                    Color.rgb(219, 234, 254),
                    Color.rgb(37, 99, 235),
                    Color.rgb(185, 28, 28),
                    Color.rgb(148, 163, 184),
                    Color.rgb(241, 245, 249));
        }
    }
}
