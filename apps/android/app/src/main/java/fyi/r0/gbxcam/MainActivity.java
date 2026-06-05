package fyi.r0.gbxcam;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.os.Bundle;
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
import java.util.concurrent.Executors;

/**
 * Thin Activity shell: builds the collaborators, owns the lifecycle, hosts the USB
 * receiver reactions, and shows the startup connection popup. All gallery business
 * logic lives in {@link GalleryController}.
 */
public class MainActivity extends Activity implements UsbDeviceController.Listener {
    private static final String TAG = "GbcamApp";

    private UsbDeviceController usb;
    private AppSettings settings;
    private MainScreen screen;
    private GbcamOperationRunner operationRunner;
    private GalleryController controller;
    private final ExecutorService previewExecutor = Executors.newFixedThreadPool(3);
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private boolean destroyed;

    private TextView startupStep1Label;
    private TextView startupStep2Label;
    private int startupStepDoneColor;
    private int startupStepDefaultColor;
    private boolean startupStep2Checking;
    private final Handler startupHandler = new Handler(Looper.getMainLooper());
    private final Runnable startupCartridgeCheckRunnable = this::doStartupCartridgeCheck;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usb = new UsbDeviceController(this, this);
        operationRunner = new GbcamOperationRunner();
        settings = new AppSettings(this);
        EmptyImageCache emptyImages = new EmptyImageCache();
        ManualMergeStore mergeStore = new ManualMergeStore(this);
        BackupRepository backups = new BackupRepository(this, settings, emptyImages);
        GalleryPipeline pipeline = new GalleryPipeline(settings, emptyImages, this::log);
        int defaultPaletteIndex = NativeGbcam.defaultPaletteIndex();
        PaletteCatalog palettes = PaletteCatalog.load();
        int paletteIndex = settings.paletteIndex(defaultPaletteIndex);
        paletteIndex = Math.max(0, Math.min(paletteIndex, palettes.labels.length - 1));
        screen = new MainScreen(
                this,
                palettes.labels,
                palettes.colors,
                settings.paletteFavorites(palettes.labels),
                settings.recentPalettes(palettes.labels),
                defaultPaletteIndex);
        controller = new GalleryController(this, screen, usb, operationRunner, settings, mergeStore,
                backups, pipeline, palettes, previewExecutor, backgroundExecutor, paletteIndex,
                () -> { if (startupStep2Label != null) startupStep2Label.setTextColor(startupStepDoneColor); });
        screen.setListener(controller);
        screen.setBitmapExecutor(previewExecutor);
        screen.setPaletteIndex(paletteIndex);
        screen.setLogsVisibleFromSettings(settings.showLogs());
        screen.setShowMeta(settings.showPhotoMeta());
        UiStyle.setLogger(this::log);
        setContentView(screen.view());

        usb.register();
        log("Rust core loaded: " + NativeGbcam.version());
        boolean deviceFound = usb.refresh();
        screen.setDeviceConnected(deviceFound);
        if (deviceFound && settings.autoLoad()) {
            controller.autoLoadCamera();
        } else if (settings.loadCache()) {
            controller.loadCachedGallery();
        }
        if (settings.showStartupPopup()) {
            showStartupPopup();
        }
    }

    @Override
    public void onBackPressed() {
        if (screen != null && screen.isSelectMode()) {
            screen.selectAll(false);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        startupHandler.removeCallbacks(startupCartridgeCheckRunnable);
        if (controller != null) controller.dispose();
        usb.unregister();
        UiStyle.setLogger(null);
        operationRunner.shutdown();
        backgroundExecutor.shutdownNow();
        previewExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        boolean deviceFound = usb.refresh();
        screen.setDeviceConnected(deviceFound);
        if (deviceFound && settings.autoLoad()) {
            controller.autoLoadCamera();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        controller.handleActivityResult(requestCode, resultCode, data);
    }

    // --- UsbDeviceController.Listener: host reactions to USB events ------------

    @Override
    public void onUsbLog(String message) {
        log(message);
    }

    @Override
    public void onConnectionChanged(boolean connected) {
        screen.setDeviceConnected(connected);
    }

    @Override
    public void onDeviceAttached(boolean found) {
        screen.setDeviceConnected(found);
        if (found && settings.autoLoad()) {
            controller.autoLoadCamera();
        }
        if (found && startupStep1Label != null) {
            startupStep1Label.setTextColor(startupStepDoneColor);
            startupHandler.removeCallbacks(startupCartridgeCheckRunnable);
            startupStep2Checking = false;
            doStartupCartridgeCheck();
        }
    }

    @Override
    public void onDeviceDetached(boolean wasDisconnected) {
        if (wasDisconnected) {
            log("GBxCart RW disconnected.");
            screen.setDeviceConnected(false);
        }
        if (startupStep1Label != null) startupStep1Label.setTextColor(startupStepDefaultColor);
        if (startupStep2Label != null) startupStep2Label.setTextColor(startupStepDefaultColor);
        startupHandler.removeCallbacks(startupCartridgeCheckRunnable);
        startupStep2Checking = false;
    }

    @Override
    public void onPermissionGranted() {
        log("USB permission granted.");
    }

    @Override
    public void onPermissionDenied() {
        Log.w(TAG, "USB permission denied");
        log("USB permission denied.");
    }

    /** Forwards a log line to the controller (which owns the on-screen log). */
    private void log(String message) {
        if (controller != null) {
            controller.onLog(message);
        } else {
            Log.i(TAG, message);
        }
    }

    private void showStartupPopup() {
        startupHandler.removeCallbacks(startupCartridgeCheckRunnable);
        startupStep2Checking = false;
        Dialog dialog = UiStyle.baseDialog(this);
        UiStyle.Palette colors = UiStyle.palette(this);
        int accent = screen.accentColor();

        LinearLayout content = UiStyle.dialog(this, dialog, "How to connect", "Game Boy Camera + GBxCart RW + phone");

        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        steps.setPadding(0, dp(12), 0, 0);
        startupStepDoneColor = Color.rgb(76, 175, 80);
        startupStepDefaultColor = colors.textPrimary;

        startupStep1Label = new TextView(this);
        startupStep2Label = new TextView(this);
        TextView step3Label = new TextView(this);
        steps.addView(connectionStep("1", boldSpan("Connect GBxCart RW to your phone via USB (USB-C to USB-C cable).", "USB-C to USB-C"), startupStep1Label, colors, accent));
        steps.addView(connectionStep("2", boldSpan("Insert the Game Boy Camera cartridge into GBxCart RW.", "Game Boy Camera"), startupStep2Label, colors, accent));
        steps.addView(connectionStep("3", boldSpan("Tap \"Load Camera\".", "Load Camera"), step3Label, colors, accent));

        if (usb.device() != null) {
            startupStep1Label.setTextColor(startupStepDoneColor);
            if (screen.gallery() != null) {
                startupStep2Label.setTextColor(startupStepDoneColor);
            }
            doStartupCartridgeCheck();
        }
        content.addView(steps, matchWidthWrapContent());

        TextView cartWarning = new TextView(this);
        cartWarning.setText("Only the Game Boy Camera cartridge is supported.");
        cartWarning.setTextColor(colors.textMuted);
        cartWarning.setTextSize(11);
        cartWarning.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams warnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        warnParams.setMargins(0, dp(8), 0, 0);
        content.addView(cartWarning, warnParams);

        CheckBox dontShow = UiStyle.settingsCheckBox(
                this, "Don't show on startup", null, false, accent);
        View cbRow = UiStyle.settingsRow(this, dontShow);
        LinearLayout.LayoutParams cbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        cbParams.setMargins(0, dp(12), 0, 0);
        content.addView(cbRow, cbParams);

        Button loadCamera = UiStyle.button(this, "Load Camera", accent, colors.surfaceRaised, accent);
        loadCamera.setOnClickListener(v -> {
            if (!usb.isConnected()) {
                controller.onLoadRequested(); // shows toast; popup stays open
                return;
            }
            if (dontShow.isChecked()) {
                settings.saveShowStartupPopup(false);
            }
            startupHandler.removeCallbacks(startupCartridgeCheckRunnable);
            startupStep1Label = null;
            startupStep2Label = null;
            startupStep2Checking = false;
            dialog.dismiss();
            controller.onLoadRequested();
        });
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        okParams.setMargins(0, dp(10), 0, 0);
        content.addView(loadCamera, okParams);

        dialog.setOnDismissListener(d -> {
            startupHandler.removeCallbacks(startupCartridgeCheckRunnable);
            startupStep1Label = null;
            startupStep2Label = null;
            startupStep2Checking = false;
        });
        dialog.setContentView(content);
        UiStyle.sizeDialog(dialog, this, 32, 480);
        dialog.show();
    }

    private void doStartupCartridgeCheck() {
        if (startupStep2Label == null || usb.device() == null || startupStep2Checking) return;
        if (screen != null && screen.isBusy()) {
            startupHandler.postDelayed(startupCartridgeCheckRunnable, 5_000);
            return;
        }
        startupStep2Checking = true;
        UsbDevice device = usb.device();
        runInBackground(() -> {
            UsbDeviceConnection conn = null;
            boolean isCamera = false;
            try {
                conn = usb.manager().openDevice(device);
                if (conn != null) {
                    isCamera = NativeGbcam.isGameBoyCameraInserted(conn.getFileDescriptor());
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
                startupStep2Checking = false;
                if (startupStep2Label == null || usb.device() != device) return;
                startupStep2Label.setTextColor(finalIsCamera ? startupStepDoneColor : startupStepDefaultColor);
                if (startupStep2Label.isAttachedToWindow()) {
                    startupHandler.postDelayed(startupCartridgeCheckRunnable, 12_000);
                }
            });
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

    private View connectionStep(String number, CharSequence text, TextView label, UiStyle.Palette colors, int accent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
        row.setLayoutParams(params);

        TextView badge = new TextView(this);
        badge.setText(number);
        badge.setTextColor(accent);
        badge.setTextSize(12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(UiStyle.rounded(this, colors.surfaceRaised, accent, 999, 1));
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

    private void runInBackground(Runnable action) {
        backgroundExecutor.execute(action);
    }

    private void postToUi(Runnable action) {
        runOnUiThread(() -> {
            if (!destroyed) {
                action.run();
            }
        });
    }

    private int dp(int value) {
        return UiStyle.dp(this, value);
    }

    private static LinearLayout.LayoutParams matchWidthWrapContent() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
