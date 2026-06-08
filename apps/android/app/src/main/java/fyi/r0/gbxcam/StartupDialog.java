package fyi.r0.gbxcam;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;

/**
 * Startup connection guide plus its lightweight cartridge polling. The dialog owns
 * only its UI state; the host still owns USB lifecycle and gallery loading.
 */
final class StartupDialog {
    private static final String TAG = "GbcamApp";

    private final Activity activity;
    private final MainScreen screen;
    private final UsbDeviceController usb;
    private final AppSettings settings;
    private final ExecutorService backgroundExecutor;
    private final Runnable onLoadRequested;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable cartridgeCheckRunnable = this::doCartridgeCheck;

    private TextView step1Label;
    private TextView step2Label;
    private int stepDoneColor;
    private int stepDefaultColor;
    private boolean step2Checking;
    private boolean disposed;

    StartupDialog(Activity activity, MainScreen screen, UsbDeviceController usb,
            AppSettings settings, ExecutorService backgroundExecutor, Runnable onLoadRequested) {
        this.activity = activity;
        this.screen = screen;
        this.usb = usb;
        this.settings = settings;
        this.backgroundExecutor = backgroundExecutor;
        this.onLoadRequested = onLoadRequested;
    }

    void show() {
        stopChecking();
        Dialog dialog = UiStyle.baseDialog(activity);
        UiStyle.Palette colors = UiStyle.palette(activity);
        int accent = screen.accentColor();

        LinearLayout content = UiStyle.dialog(
                activity, dialog, "How to connect", "Game Boy Camera + GBxCart RW 1.4 + phone");

        LinearLayout steps = new LinearLayout(activity);
        steps.setOrientation(LinearLayout.VERTICAL);
        steps.setPadding(0, dp(12), 0, 0);
        stepDoneColor = Color.rgb(76, 175, 80);
        stepDefaultColor = colors.textPrimary;

        step1Label = new TextView(activity);
        step2Label = new TextView(activity);
        TextView step3Label = new TextView(activity);
        steps.addView(connectionStep(
                "1",
                boldSpan("Connect GBxCart RW 1.4 to your phone via USB (USB-C to USB-C cable).",
                        "USB-C to USB-C"),
                step1Label,
                colors,
                accent));
        steps.addView(connectionStep(
                "2",
                boldSpan("Insert the Game Boy Camera cartridge into GBxCart RW 1.4.",
                        "Game Boy Camera"),
                step2Label,
                colors,
                accent));
        steps.addView(connectionStep(
                "3",
                boldSpan("Tap \"Load Camera\".", "Load Camera"),
                step3Label,
                colors,
                accent));

        if (usb.device() != null) {
            step1Label.setTextColor(stepDoneColor);
            if (screen.gallery() != null) {
                step2Label.setTextColor(stepDoneColor);
            }
            doCartridgeCheck();
        }
        content.addView(steps, matchWidthWrapContent());

        TextView cartWarning = new TextView(activity);
        cartWarning.setText("Only the Game Boy Camera cartridge is supported.");
        cartWarning.setTextColor(colors.textMuted);
        cartWarning.setTextSize(11);
        cartWarning.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams warnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        warnParams.setMargins(0, dp(8), 0, 0);
        content.addView(cartWarning, warnParams);

        CheckBox dontShow = UiStyle.settingsCheckBox(
                activity, "Don't show on startup", null, false, accent);
        View cbRow = UiStyle.settingsRow(activity, dontShow);
        LinearLayout.LayoutParams cbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        cbParams.setMargins(0, dp(12), 0, 0);
        content.addView(cbRow, cbParams);

        Button loadCamera = UiStyle.button(activity, "Load Camera", accent, colors.surfaceRaised, accent);
        loadCamera.setOnClickListener(v -> {
            if (!usb.isConnected()) {
                onLoadRequested.run(); // shows toast; popup stays open
                return;
            }
            if (dontShow.isChecked()) {
                settings.saveShowStartupPopup(false);
            }
            clearLabels();
            dialog.dismiss();
            onLoadRequested.run();
        });
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        okParams.setMargins(0, dp(10), 0, 0);
        content.addView(loadCamera, okParams);

        dialog.setOnDismissListener(d -> clearLabels());
        dialog.setContentView(content);
        UiStyle.sizeDialog(dialog, activity, 32, 480);
        dialog.show();
    }

    void markDeviceAttached(boolean found) {
        if (!found || step1Label == null) return;
        step1Label.setTextColor(stepDoneColor);
        stopChecking();
        doCartridgeCheck();
    }

    void markDeviceDetached() {
        if (step1Label != null) step1Label.setTextColor(stepDefaultColor);
        if (step2Label != null) step2Label.setTextColor(stepDefaultColor);
        stopChecking();
    }

    void markCameraLoaded() {
        if (step2Label != null) {
            step2Label.setTextColor(stepDoneColor);
        }
    }

    void dispose() {
        disposed = true;
        clearLabels();
    }

    private void doCartridgeCheck() {
        if (step2Label == null || usb.device() == null || step2Checking) return;
        if (screen != null && screen.isBusy()) {
            handler.postDelayed(cartridgeCheckRunnable, 5_000);
            return;
        }
        step2Checking = true;
        UsbDevice device = usb.device();
        backgroundExecutor.execute(() -> {
            UsbDeviceConnection conn = null;
            boolean isCamera = false;
            try {
                conn = usb.manager().openDevice(device);
                if (conn != null) {
                    GbxCartDevices.NativeTransport transport = GbxCartDevices.nativeTransport(device);
                    if (transport != null) {
                        isCamera = NativeGbcam.isGameBoyCameraInserted(
                                conn.getFileDescriptor(),
                                transport.interfaceNumber,
                                transport.epOut,
                                transport.epIn,
                                transport.initializeCh340);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Startup cartridge check failed", t);
            } finally {
                if (conn != null) {
                    conn.close();
                }
            }
            boolean finalIsCamera = isCamera;
            postToUi(() -> {
                step2Checking = false;
                if (step2Label == null || usb.device() != device) return;
                step2Label.setTextColor(finalIsCamera ? stepDoneColor : stepDefaultColor);
                if (step2Label.isAttachedToWindow()) {
                    handler.postDelayed(cartridgeCheckRunnable, 12_000);
                }
            });
        });
    }

    private void stopChecking() {
        handler.removeCallbacks(cartridgeCheckRunnable);
        step2Checking = false;
    }

    private void clearLabels() {
        stopChecking();
        step1Label = null;
        step2Label = null;
    }

    private void postToUi(Runnable action) {
        activity.runOnUiThread(() -> {
            if (!disposed) {
                action.run();
            }
        });
    }

    private static SpannableString boldSpan(String text, String substring) {
        SpannableString s = new SpannableString(text);
        int i = text.indexOf(substring);
        if (i >= 0) {
            s.setSpan(new StyleSpan(Typeface.BOLD), i, i + substring.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    private View connectionStep(String number, CharSequence text, TextView label,
            UiStyle.Palette colors, int accent) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
        row.setLayoutParams(params);

        TextView badge = new TextView(activity);
        badge.setText(number);
        badge.setTextColor(accent);
        badge.setTextSize(12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(UiStyle.rounded(activity, colors.surfaceRaised, accent, 999, 1));
        row.addView(badge, new LinearLayout.LayoutParams(dp(24), dp(24)));

        label.setText(text);
        label.setTextColor(colors.textPrimary);
        label.setTextSize(13);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        labelParams.setMargins(dp(12), 0, 0, 0);
        label.setLayoutParams(labelParams);
        row.addView(label);

        return row;
    }

    private int dp(int value) {
        return UiStyle.dp(activity, value);
    }

    private static LinearLayout.LayoutParams matchWidthWrapContent() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
