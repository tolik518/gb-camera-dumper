package fyi.r0.gbxcam;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The Settings dialog: toggles persisted in {@link AppSettings}, the RGB-merge
 * order/algorithm pickers, and action rows that defer to the host (backups,
 * import/export, palette icon, share logs, about).
 */
final class SettingsDialog {

    /** App-side actions the settings rows trigger. */
    interface Host {
        void onAboutRequested();
        void onBackupsRequested();
        void onImportSaveRequested();
        void onExportSaveRequested();
        void shareLogs();
        void applyPaletteIcon();
        /** Re-render the gallery after a change that affects auto RGB merging. */
        void recolorGallery();
    }

    private final Activity activity;
    private final AppSettings settings;
    private final MainScreen screen;
    private final Host host;

    SettingsDialog(Activity activity, AppSettings settings, MainScreen screen, Host host) {
        this.activity = activity;
        this.settings = settings;
        this.screen = screen;
        this.host = host;
    }

    void show() {
        Dialog dialog = UiStyle.baseDialog(activity);
        UiStyle.Palette colors = UiStyle.palette(activity);
        int accent = screen.accentColor();

        LinearLayout content = UiStyle.dialog(activity, dialog, "Settings", null);
        // Insert About button before Close in the dialog header
        LinearLayout header = (LinearLayout) content.getChildAt(0);
        Button headerAbout = UiStyle.button(activity, "About", colors.textSecondary, colors.surfaceRaised, colors.border);
        headerAbout.setTextSize(13);
        headerAbout.setOnClickListener(v -> { dialog.dismiss(); host.onAboutRequested(); });
        LinearLayout.LayoutParams headerAboutParams = new LinearLayout.LayoutParams(dp(72), dp(44));
        headerAboutParams.setMargins(0, 0, dp(6), 0);
        header.addView(headerAbout, header.getChildCount() - 1, headerAboutParams);

        LinearLayout options = new LinearLayout(activity);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(0, dp(12), 0, 0);

        CheckBox autoLoad = UiStyle.settingsCheckBox(
                activity,
                "Auto-load camera on launch",
                "Starts reading the camera automatically when a GBxCart RW is connected.",
                settings.autoLoad(),
                accent);
        CheckBox loadCache = UiStyle.settingsCheckBox(
                activity,
                "Load last gallery when offline",
                "Shows the most recent save backup when no camera is connected or auto-load is off.",
                settings.loadCache(),
                accent);
        CheckBox showLogs = UiStyle.settingsCheckBox(
                activity,
                "Show logs by default",
                "Keeps the operation log panel open after app startup.",
                settings.showLogs(),
                accent);
        CheckBox showPhotoMetaCb = UiStyle.settingsCheckBox(
                activity,
                "Show photo metadata",
                "Shows slot number and merge info below each photo in the gallery.",
                settings.showPhotoMeta(),
                accent);
        CheckBox showStartupPopupCb = UiStyle.settingsCheckBox(
                activity,
                "Show popup on startup",
                "Displays connection instructions when the app launches.",
                settings.showStartupPopup(),
                accent);
        CheckBox confirmWrites = UiStyle.settingsCheckBox(
                activity,
                "Confirm album writes",
                "Asks before delete, recover, reorder, compact, or clear operations.",
                settings.confirmAlbumWrites(),
                accent);
        CheckBox exportDeleted = UiStyle.settingsCheckBox(
                activity,
                "Export deleted photos",
                "Includes selected recoverable deleted slots when saving or sharing images.",
                settings.exportDeleted(),
                accent);
        CheckBox autoRgbMerge = UiStyle.settingsCheckBox(
                activity,
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
                host.recolorGallery();
            }
        };

        View rgb4Row = settingsPickerRow(
                "4-shot order",
                "Position of C (clear), R, G, B in consecutive shots.",
                MergeOrder.ORDERS_4, rgb4Value, saveAll);
        View rgb3Row = settingsPickerRow(
                "3-shot order",
                "Position of R, G, B in consecutive shots.",
                MergeOrder.ORDERS_3, rgb3Value, saveAll);
        View algoRow = settingsIdPickerRow(
                "Default merge algorithm",
                "Algorithm used when auto-detecting RGB sets.",
                RgbMergeDetector.ALGORITHM_IDS,
                RgbMergeDetector.ALGORITHM_LABELS,
                MergeAlgorithm.allShortLabels(),
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

        options.addView(UiStyle.settingsRow(activity, autoLoad));
        options.addView(UiStyle.settingsRow(activity, loadCache));
        options.addView(UiStyle.settingsRow(activity, showLogs));
        options.addView(UiStyle.settingsRow(activity, showPhotoMetaCb));
        options.addView(UiStyle.settingsRow(activity, showStartupPopupCb));
        options.addView(UiStyle.settingsRow(activity, confirmWrites));
        options.addView(UiStyle.settingsRow(activity, exportDeleted));
        options.addView(UiStyle.settingsRow(activity, autoRgbMerge));
        options.addView(rgb4Row);
        options.addView(rgb3Row);
        options.addView(algoRow);
        options.addView(settingsActionRow(
                "Backups",
                "Browse and restore save file backups.",
                accent,
                () -> {
                    dialog.dismiss();
                    host.onBackupsRequested();
                }));
        options.addView(settingsActionRow(
                "Import save",
                "Load a .sav file from your device.",
                accent,
                () -> {
                    dialog.dismiss();
                    host.onImportSaveRequested();
                }));
        options.addView(settingsActionRow(
                "Export save",
                "Save the current camera backup to your device.",
                accent,
                () -> {
                    dialog.dismiss();
                    host.onExportSaveRequested();
                }));
        options.addView(settingsActionRow(
                "Apply current palette as app icon",
                "Updates the launcher icon to match the selected palette. The app will briefly restart.",
                accent,
                () -> {
                    dialog.dismiss();
                    host.applyPaletteIcon();
                }));
        options.addView(settingsActionRow(
                "Share logs",
                "Share the current session log text.",
                accent,
                () -> {
                    dialog.dismiss();
                    host.shareLogs();
                }));

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(options);
        content.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dp(360), dp(72) * 11 + dp(12))));

        dialog.setContentView(content);
        UiStyle.sizeDialog(dialog, activity, 32, 560);
        dialog.show();
    }

    private View settingsActionRow(String title, String description, int accentColor, Runnable action) {
        UiStyle.Palette colors = UiStyle.palette(activity);
        FrameLayout row = new FrameLayout(activity);
        row.setBackground(UiStyle.rounded(activity, colors.surfaceRaised, colors.border, 10, 1));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());

        LinearLayout inner = new LinearLayout(activity);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.setPadding(dp(14), dp(10), dp(10), dp(10));

        TextView text = new TextView(activity);
        text.setText(UiStyle.twoLineText(title, description, colors.textPrimary, colors.textSecondary));
        text.setTextSize(12);
        inner.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = new TextView(activity);
        arrow.setText("›");
        arrow.setTextColor(accentColor);
        arrow.setTextSize(22);
        arrow.setGravity(Gravity.CENTER);
        arrow.setPadding(dp(8), 0, dp(4), 0);
        inner.addView(arrow);

        row.addView(inner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

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
                activity,
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
                activity,
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

    private int dp(int value) {
        return UiStyle.dp(activity, value);
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
}
