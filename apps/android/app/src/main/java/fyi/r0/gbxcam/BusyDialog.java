package fyi.r0.gbxcam;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "busy" overlay shown during USB operations: an animated logo, a
 * DO-NOT-DISCONNECT warning, a percent readout fed by progress messages, a
 * slow-operation hint revealed after 20 s, and an error state with a dismiss
 * button. {@link MainScreen} keeps the {@code busy} flag and toolbar state; this
 * class owns only the dialog.
 */
final class BusyDialog {
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    private final Context context;
    private final View handlerView;

    private Dialog dialog;
    private LinearLayout content;
    private GifView gif;
    private TextView progressText;
    private TextView statusText;
    private TextView slowText;
    private Runnable slowReveal;
    private boolean hasError;
    private int lastPercent = -1;

    public enum Direction { TO_ANDROID, TO_GBCAM }

    BusyDialog(Context context, View handlerView) {
        this.context = context;
        this.handlerView = handlerView;
    }

    boolean hasError() {
        return hasError;
    }

    /** Shows the overlay with an optional {@code message}; {@code accent} colors the percent. */
    void show(String message, int accent, Direction direction) {
        dismiss();
        UiStyle.Palette colors = UiStyle.palette(context);
        Dialog dialog = UiStyle.baseDialog(context);
        dialog.setCancelable(false);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(32), dp(28), dp(32), dp(28));
        content.setBackground(UiStyle.rounded(context, colors.surface, colors.borderStrong, 14, 1));

        GifView gif;
        if (direction == Direction.TO_ANDROID) {
            gif = new GifView(context, R.raw.gbcam_logo);
        } else {
            gif = new GifView(context, R.raw.gbcam_logo_reversed);
        }

        content.addView(gif, new LinearLayout.LayoutParams(dp(128), dp(64)));
        this.gif = gif;

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

        progressText = new TextView(context);
        progressText.setTextSize(32);
        progressText.setTypeface(Typeface.DEFAULT_BOLD);
        progressText.setTextColor(accent);
        progressText.setGravity(Gravity.CENTER);
        progressText.setText("0%");
        progressText.setVisibility(View.VISIBLE);
        lastPercent = 0;
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressParams.setMargins(0, dp(8), 0, 0);
        content.addView(progressText, progressParams);

        statusText = new TextView(context);
        statusText.setTextSize(11);
        statusText.setTextColor(colors.textMuted);
        statusText.setGravity(Gravity.CENTER);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        statusText.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(6), 0, 0);
        content.addView(statusText, statusParams);

        slowText = new TextView(context);
        slowText.setText("Please stand by... Game Boy Camera cartridges are from the '90s and they write one byte at a time :)");
        slowText.setTextColor(colors.textMuted);
        slowText.setTextSize(11);
        slowText.setGravity(Gravity.CENTER);
        slowText.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams slowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slowParams.setMargins(0, dp(10), 0, 0);
        content.addView(slowText, slowParams);

        slowReveal = () -> {
            if (slowText != null) {
                slowText.setVisibility(View.VISIBLE);
            }
        };
        handlerView.postDelayed(slowReveal, 20_000);

        this.content = content;
        dialog.setContentView(content);
        UiStyle.sizeDialog(dialog, context, 48, 340);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        this.dialog = dialog;
    }

    void dismiss() {
        if (slowReveal != null) {
            handlerView.removeCallbacks(slowReveal);
        }
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        content = null;
        gif = null;
        progressText = null;
        statusText = null;
        slowText = null;
        slowReveal = null;
        hasError = false;
        lastPercent = -1;
    }

    /** Switches the overlay to an error state; {@code onDismissed} runs when the user dismisses. */
    void showError(String errorMessage, Runnable onDismissed) {
        if (content == null || hasError) return;
        hasError = true;
        if (gif != null) {
            gif.setVisibility(View.GONE);
        }
        if (progressText != null) {
            progressText.setVisibility(View.GONE);
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
        content.addView(errorText, errorParams);

        Button dismissButton = UiStyle.button(context, "Dismiss", Color.WHITE, colors.danger, colors.danger);
        dismissButton.setBackground(UiStyle.dangerButtonBackground(context));
        LinearLayout.LayoutParams dismissParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        dismissParams.setMargins(0, dp(16), 0, 0);
        dismissButton.setOnClickListener(v -> {
            dismiss();
            if (onDismissed != null) onDismissed.run();
        });
        content.addView(dismissButton, dismissParams);
        if (dialog != null) {
            UiStyle.sizeDialog(dialog, context, 48, 340);
        }
    }

    void updateProgress(String message) {
        if (progressText == null || hasError) return;
        int percent = parsePercent(message);
        if (percent > lastPercent) {
            lastPercent = percent;
            progressText.setText(percent + "%");
            progressText.setVisibility(View.VISIBLE);
        }
        if (statusText != null && !message.startsWith("[debug]")) {
            statusText.setText(message);
            statusText.setVisibility(View.VISIBLE);
        }
    }

    private static int parsePercent(String message) {
        if (message == null) return -1;
        Matcher m = PERCENT_PATTERN.matcher(message);
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

    private int dp(int value) {
        return UiStyle.dp(context, value);
    }
}
