package fyi.r0.gbxcam;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.Consumer;

/** The "About" dialog: app version, connected-device info, links, and license. */
final class AboutDialog {
    private final Activity activity;
    private final Consumer<String> logger;

    AboutDialog(Activity activity, Consumer<String> logger) {
        this.activity = activity;
        this.logger = logger;
    }

    /** Shows the dialog. {@code connectedGallery}/{@code deviceConnected} drive the device section. */
    void show(GalleryState connectedGallery, boolean deviceConnected) {
        Dialog dialog = UiStyle.baseDialog(activity);
        UiStyle.Palette colors = UiStyle.palette(activity);

        LinearLayout content = UiStyle.dialog(activity, dialog, "GBxCAM Viewer", "v" + packageVersionName());

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(8), 0, 0);

        if (connectedGallery != null && deviceConnected) {
            body.addView(aboutSection("Connected Device", colors));
            TextView deviceInfo = new TextView(activity);
            deviceInfo.setText(connectedGallery.connected);
            deviceInfo.setTextColor(colors.textPrimary);
            deviceInfo.setTextSize(13);
            deviceInfo.setPadding(0, dp(6), 0, dp(6));
            body.addView(deviceInfo);
        }

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

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        content.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(content);
        UiStyle.sizeDialog(dialog, activity, 32, 560);
        dialog.show();
    }

    private View aboutSection(String label, UiStyle.Palette colors) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, dp(2));
        row.setLayoutParams(params);
        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextColor(colors.textSecondary);
        title.setTextSize(11);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setIncludeFontPadding(false);
        row.addView(title);
        View rule = new View(activity);
        rule.setBackgroundColor(colors.border);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(0, dp(1), 1);
        ruleParams.setMargins(dp(8), 0, 0, 0);
        row.addView(rule, ruleParams);
        return row;
    }

    private View aboutRow(String label, String description, String url, UiStyle.Palette colors) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setOnClickListener(v -> openUrl(url));
        row.setPadding(0, dp(6), 0, dp(6));
        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = new TextView(activity);
        labelView.setText(label);
        labelView.setTextColor(colors.primary);
        labelView.setTextSize(13);
        text.addView(labelView);
        if (description != null && !description.isEmpty()) {
            TextView descView = new TextView(activity);
            descView.setText(description);
            descView.setTextColor(colors.textSecondary);
            descView.setTextSize(11);
            descView.setIncludeFontPadding(false);
            text.addView(descView);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = new TextView(activity);
        arrow.setText("↗");
        arrow.setTextColor(colors.textMuted);
        arrow.setTextSize(14);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.MATCH_PARENT));
        return row;
    }

    private void openUrl(String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            logger.accept("Could not open link.");
        }
    }

    @SuppressWarnings("deprecation")
    private String packageVersionName() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "?";
        }
    }

    private int dp(int value) {
        return UiStyle.dp(activity, value);
    }
}
