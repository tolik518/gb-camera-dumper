package fyi.r0.gbxcam;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * The save-backup picker dialog: a scrollable list of backup saves with a 2×2
 * thumbnail mosaic loaded off the UI thread. Data comes from {@link BackupRepository};
 * selection is reported via the {@code onSelected} callback.
 */
final class BackupPickerDialog {
    private final Activity activity;
    private final MainScreen screen;
    private final BackupRepository backups;
    private final Executor previewExecutor;
    private final AppCallback<Runnable> postToUi;
    private final AppCallback<File> onSelected;

    BackupPickerDialog(Activity activity, MainScreen screen, BackupRepository backups,
            Executor previewExecutor, AppCallback<Runnable> postToUi, AppCallback<File> onSelected) {
        this.activity = activity;
        this.screen = screen;
        this.backups = backups;
        this.previewExecutor = previewExecutor;
        this.postToUi = postToUi;
        this.onSelected = onSelected;
    }

    void show(File[] saves) {
        Dialog dialog = UiStyle.baseDialog(activity);
        UiStyle.Palette colors = UiStyle.palette(activity);
        int panelRaised = colors.surfaceRaised;
        int border = colors.borderStrong;
        int textPrimary = colors.textPrimary;
        int textSecondary = colors.textSecondary;
        int textMuted = colors.textMuted;
        int accent = screen.accentColor();

        LinearLayout content = UiStyle.dialog(
                activity,
                dialog,
                "Save Backups",
                saves.length + " file" + (saves.length == 1 ? "" : "s") + " available");

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(12), 0, 0);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (File save : saves) {
            list.addView(backupRow(save, format, dialog, panelRaised, border, textPrimary, textSecondary, textMuted, accent, colors.logBackground));
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dp(420), dp(86) * saves.length + dp(12)));
        content.addView(scroll, scrollParams);

        dialog.setContentView(content);
        UiStyle.sizeDialog(dialog, activity, 32, 560);
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
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(UiStyle.rounded(activity, panelRaised, current ? accent : border, 10, current ? 2 : 1));
        row.setOnClickListener(v -> {
            dialog.dismiss();
            onSelected.accept(save);
        });

        ImageView[] tiles = new ImageView[4];
        View[] badge = new View[1];
        row.addView(backupMosaic(tiles, badge, current, border, accent, textSecondary, previewBackground),
                new LinearLayout.LayoutParams(dp(58), dp(58)));
        previewExecutor.execute(() -> {
            GalleryPhoto[] photos = backups.previewPhotos(save);
            postToUi.accept(() -> {
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

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);

        TextView name = new TextView(activity);
        name.setText(save.getName());
        name.setTextColor(textPrimary);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        text.addView(name);

        TextView meta = new TextView(activity);
        meta.setText(format.format(new Date(save.lastModified()))
                + " · "
                + Math.max(1, save.length() / 1024)
                + " KB");
        meta.setTextColor(current ? textSecondary : textMuted);
        meta.setTextSize(12);
        meta.setSingleLine(true);
        text.addView(meta);
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = new TextView(activity);
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
        FrameLayout frame = new FrameLayout(activity);
        frame.setPadding(dp(2), dp(2), dp(2), dp(2));
        frame.setBackground(UiStyle.rounded(activity, background, current ? accent : border, 8, current ? 2 : 1));

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipToOutline(true);
        for (int y = 0; y < 2; y++) {
            LinearLayout line = new LinearLayout(activity);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int x = 0; x < 2; x++) {
                int index = y * 2 + x;
                ImageView tile = new ImageView(activity);
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

        TextView badge = new TextView(activity);
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

    private int dp(int value) {
        return UiStyle.dp(activity, value);
    }
}
