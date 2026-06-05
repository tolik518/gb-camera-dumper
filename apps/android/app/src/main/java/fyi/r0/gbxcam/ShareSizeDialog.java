package fyi.r0.gbxcam;

import android.app.Activity;

/** Single-choice picker for the share/export pixel scale, persisted in settings. */
final class ShareSizeDialog {
    private static final int[] SCALES = { 1, 2, 4, 8 };
    private static final String[] LABELS = {
        "1×  ·  128 × 112  (native pixel size)",
        "2×  ·  256 × 224",
        "4×  ·  512 × 448",
        "8×  ·  1024 × 896"
    };

    private ShareSizeDialog() {
    }

    /** Shows the picker; reports the chosen scale via {@code onPicked} and remembers it. */
    static void show(Activity activity, AppSettings settings, UiStyle.ChoiceListener onPicked) {
        int lastScale = settings.shareMultiplier();
        int selectedIdx = 0;
        for (int i = 0; i < SCALES.length; i++) {
            if (SCALES[i] == lastScale) { selectedIdx = i; break; }
        }
        UiStyle.singleChoiceDialog(activity, "Share size", LABELS, selectedIdx, which -> {
            settings.saveShareMultiplier(SCALES[which]);
            onPicked.onChoice(SCALES[which]);
        });
    }
}
