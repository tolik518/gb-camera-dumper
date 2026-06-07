package fyi.r0.gbxcam;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

final class UiStyle {
    private UiStyle() {
    }

    interface Logger {
        void log(String message);
    }

    private static Logger logger;

    static void setLogger(Logger l) {
        logger = l;
    }

    static final class LoggingButton extends Button {
        LoggingButton(Context context) {
            super(context);
        }

        @Override
        public boolean performClick() {
            if (logger != null) {
                CharSequence cd = getContentDescription();
                String label = (cd != null && cd.length() > 0)
                        ? cd.toString()
                        : getText() != null ? getText().toString() : "";
                if (!label.isEmpty()) logger.log("Button: " + label);
            }
            return super.performClick();
        }
    }

    static Palette palette(Context context) {
        return Palette.from(context);
    }

    static Button button(Context context, String text, int textColor, int fillColor, int strokeColor) {
        Button button = new LoggingButton(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(textColor);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setMinimumHeight(0);
        button.setMinimumWidth(0);
        button.setIncludeFontPadding(false);
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setBackground(buttonBackground(context, fillColor, blend(fillColor, Color.BLACK, 0.08f), fillColor, strokeColor));
        return button;
    }

    static LinearLayout actionRow(Context context, Button left, Button right) {
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(left, new LinearLayout.LayoutParams(0, dp(context, 44), 1));
        View gap = new View(context);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(context, 10), 1));
        actions.addView(right, new LinearLayout.LayoutParams(0, dp(context, 44), 1));
        return actions;
    }

    static TextView chip(Context context, String text, int textColor, int fillColor, int strokeColor) {
        TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextColor(textColor);
        chip.setTextSize(11);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setIncludeFontPadding(false);
        chip.setSingleLine(true);
        chip.setPadding(dp(context, 10), 0, dp(context, 10), 0);
        chip.setBackground(rounded(context, fillColor, strokeColor, 999, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(context, 28));
        params.setMargins(0, 0, dp(context, 8), 0);
        chip.setLayoutParams(params);
        return chip;
    }

    static CheckBox settingsCheckBox(
            Context context,
            String title,
            String description,
            boolean checked,
            int accent) {
        Palette colors = palette(context);
        CheckBox box = new CheckBox(context);
        if (description != null && !description.isEmpty()) {
            box.setText(twoLineText(title, description, colors.textPrimary, colors.textSecondary));
        } else {
            box.setText(title);
        }
        box.setTextColor(colors.textPrimary);
        box.setTextSize(12);
        box.setChecked(checked);
        box.setMinHeight(0);
        box.setMinimumHeight(0);
        box.setPadding(dp(context, 10), 0, dp(context, 10), 0);
        box.setButtonTintList(new ColorStateList(
                new int[][] {
                        new int[] { android.R.attr.state_checked },
                        new int[] {}
                },
                new int[] {
                        accent,
                        colors.borderStrong
                }));
        return box;
    }

    static View settingsRow(Context context, CheckBox box) {
        Palette colors = palette(context);
        FrameLayout row = new FrameLayout(context);
        row.setBackground(rounded(context, colors.surfaceRaised, colors.border, 10, 1));
        row.setClickable(true);
        row.setOnClickListener(v -> box.toggle());
        row.addView(box, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 66));
        params.setMargins(0, 0, 0, dp(context, 6));
        row.setLayoutParams(params);
        return row;
    }

    static View settingsChoiceRow(
            Context context,
            String title,
            String description,
            String[] dialogLabels,
            int selectedIndex,
            String badgeText,
            int badgeWidthDp,
            int badgeTextSize,
            boolean monospaceBadge,
            int accent,
            ChoiceCommitListener listener
    ) {
        Palette colors = palette(context);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(context, colors.surfaceRaised, colors.border, 10, 1));
        row.setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10));

        TextView label = new TextView(context);
        label.setText(twoLineText(title, description, colors.textPrimary, colors.textSecondary));
        label.setTextSize(12);
        label.setPadding(0, 0, dp(context, 8), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView badge = new TextView(context);
        badge.setText(badgeText);
        badge.setTextColor(accent);
        badge.setTextSize(badgeTextSize);
        if (monospaceBadge) {
            badge.setTypeface(Typeface.MONOSPACE);
        }
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(context, 8), 0, dp(context, 8), 0);
        badge.setBackground(rounded(context, colors.surfaceRaised, accent, 6, 1));
        row.addView(badge, new LinearLayout.LayoutParams(dp(context, badgeWidthDp), dp(context, 36)));

        final int[] currentIndex = { selectedIndex };
        row.setClickable(true);
        row.setOnClickListener(v -> singleChoiceDialog(context, title, dialogLabels, currentIndex[0], accent, which -> {
            currentIndex[0] = which;
            String newBadge = listener.onChoice(which);
            badge.setText(newBadge);
        }));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 66));
        params.setMargins(0, 0, 0, dp(context, 6));
        row.setLayoutParams(params);
        return row;
    }

    static PopupWindow dropdown(
            Context context,
            View anchor,
            String[] labels,
            int selectedIndex,
            int accent,
            ChoiceListener listener) {
        Palette colors = palette(context);
        LinearLayout menu = new LinearLayout(context);
        menu.setOrientation(LinearLayout.VERTICAL);

        ScrollView menuScroll = new ScrollView(context);
        menuScroll.setBackground(rounded(context, colors.surfaceRaised, colors.borderStrong, 8, 1));
        menuScroll.addView(menu);

        int itemHeight = dp(context, 48);
        int popupHeight = Math.min(itemHeight * labels.length, dp(context, 288));
        int popupWidth = Math.max(anchor.getWidth(), dp(context, 240));

        PopupWindow popup = new PopupWindow(menuScroll, popupWidth, popupHeight, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(context, 8));
        popup.setAnimationStyle(android.R.style.Animation);

        int accentSurface = blend(accent, colors.background, 0.72f);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            boolean selected = i == selectedIndex;
            LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(context, 16), 0, dp(context, 16), 0);
            item.setBackgroundColor(selected ? accentSurface : colors.surfaceRaised);
            item.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    itemHeight));
            TextView itemLabel = new TextView(context);
            itemLabel.setText(labels[i]);
            itemLabel.setTextSize(13);
            itemLabel.setTextColor(selected ? accent : colors.textPrimary);
            itemLabel.setSingleLine(true);
            itemLabel.setIncludeFontPadding(false);
            item.addView(itemLabel, new LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1));
            item.setOnClickListener(v -> {
                popup.dismiss();
                listener.onChoice(index);
            });
            menu.addView(item);
        }

        popup.showAsDropDown(anchor, 0, -(popupHeight + anchor.getHeight() + dp(context, 4)));
        return popup;
    }

    static StateListDrawable buttonBackground(Context context, int normal, int pressed, int disabled, int stroke) {
        StateListDrawable states = new StateListDrawable();
        Palette colors = palette(context);
        states.addState(new int[] { -android.R.attr.state_enabled }, rounded(context, disabled, colors.border, 6, 1));
        states.addState(new int[] { android.R.attr.state_pressed }, rounded(context, pressed, colors.borderStrong, 6, 1));
        states.addState(new int[] {}, rounded(context, normal, stroke, 6, 1));
        return states;
    }

    static StateListDrawable dangerButtonBackground(Context context) {
        Palette colors = palette(context);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { -android.R.attr.state_enabled }, rounded(context, colors.disabledBackground, colors.border, 6, 1));
        states.addState(new int[] { android.R.attr.state_pressed }, rounded(context, blend(colors.danger, Color.BLACK, 0.18f), colors.danger, 6, 1));
        states.addState(new int[] {}, rounded(context, colors.danger, colors.danger, 6, 1));
        return states;
    }

    static GradientDrawable rounded(Context context, int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setStroke(dp(context, strokeDp), stroke);
        bg.setCornerRadius(dp(context, radiusDp));
        return bg;
    }

    static Dialog singleChoiceDialog(
            Context context,
            String title,
            String[] labels,
            int selectedIndex,
            ChoiceListener listener) {
        return singleChoiceDialog(context, title, labels, selectedIndex, palette(context).primary, listener);
    }

    static Dialog singleChoiceDialog(
            Context context,
            String title,
            String[] labels,
            int selectedIndex,
            int accent,
            ChoiceListener listener) {
        Palette colors = palette(context);
        Dialog dialog = baseDialog(context);

        LinearLayout content = dialogContent(context, colors);
        content.addView(dialogHeader(context, title, labels.length + " option" + (labels.length == 1 ? "" : "s"), colors, dialog));

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(context, 12), 0, 0);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            boolean selected = i == selectedIndex;
            TextView item = new TextView(context);
            item.setText(labels[i]);
            item.setTextColor(selected ? accent : colors.textPrimary);
            item.setTextSize(14);
            item.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setSingleLine(true);
            item.setPadding(dp(context, 14), 0, dp(context, 14), 0);
            item.setBackground(rounded(context,
                    selected ? blend(accent, colors.background, 0.72f) : colors.surfaceRaised,
                    selected ? accent : colors.border,
                    8,
                    selected ? 2 : 1));
            item.setOnClickListener(v -> {
                listener.onChoice(index);
                dialog.dismiss();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(context, 48));
            params.setMargins(0, 0, 0, dp(context, 6));
            list.addView(item, params);
        }

        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        content.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(dp(context, 360), dp(context, 54) * labels.length + dp(context, 12))));
        dialog.setContentView(content);
        sizeDialog(dialog, context, 32, 560);
        dialog.show();
        return dialog;
    }

    static Dialog confirmDialog(
            Context context,
            String title,
            String message,
            String action,
            Runnable onConfirm) {
        Palette colors = palette(context);
        Dialog dialog = baseDialog(context);

        LinearLayout content = dialogContent(context, colors);
        content.addView(dialogHeader(context, title, null, colors, dialog));

        TextView body = new TextView(context);
        body.setText(message);
        body.setTextColor(colors.textSecondary);
        body.setTextSize(14);
        body.setLineSpacing(dp(context, 2), 1.0f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyParams.setMargins(0, dp(context, 12), 0, dp(context, 14));
        content.addView(body, bodyParams);

        Button cancel = button(context, "Cancel", colors.textMuted, colors.surfaceRaised, colors.border);
        cancel.setOnClickListener(v -> dialog.dismiss());
        Button confirm = button(context, action, colors.primary, colors.surfaceRaised, colors.primary);
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            onConfirm.run();
        });
        content.addView(actionRow(context, cancel, confirm), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(content);
        sizeDialog(dialog, context, 32, 560);
        dialog.show();
        return dialog;
    }

    static LinearLayout dialogContent(Context context, Palette colors) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        content.setBackground(rounded(context, colors.surface, colors.borderStrong, 14, 1));
        return content;
    }

    static LinearLayout dialog(Context context, Dialog dialog, String title, String subtitle) {
        Palette colors = palette(context);
        LinearLayout content = dialogContent(context, colors);
        content.addView(dialogHeader(context, title, subtitle, colors, dialog));
        return content;
    }

    static LinearLayout dialogHeader(
            Context context,
            String title,
            String subtitle,
            Palette colors,
            Dialog dialog) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBlock = new LinearLayout(context);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(colors.textPrimary);
        titleView.setTextSize(19);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleBlock.addView(titleView);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subtitleView = new TextView(context);
            subtitleView.setText(subtitle);
            subtitleView.setTextColor(colors.textSecondary);
            subtitleView.setTextSize(12);
            subtitleView.setSingleLine(true);
            titleBlock.addView(subtitleView);
        }

        header.addView(titleBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button close = button(context, "Close", colors.textSecondary, colors.surfaceRaised, colors.border);
        close.setTextSize(13);
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(context, 82), dp(context, 44)));
        return header;
    }

    static Dialog baseDialog(Context context) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setWindowAnimations(R.style.DialogFade);
        }
        return dialog;
    }

    static void sizeDialog(Dialog dialog, Context context, int horizontalMarginDp, int maxWidthDp) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = Math.min(
                context.getResources().getDisplayMetrics().widthPixels - dp(context, horizontalMarginDp),
                dp(context, maxWidthDp));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        window.setAttributes(params);
    }

    static int blend(int foreground, int background, float amount) {
        int r = Math.round(Color.red(foreground) * (1 - amount) + Color.red(background) * amount);
        int g = Math.round(Color.green(foreground) * (1 - amount) + Color.green(background) * amount);
        int b = Math.round(Color.blue(foreground) * (1 - amount) + Color.blue(background) * amount);
        return Color.rgb(r, g, b);
    }

    static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    static SpannableString twoLineText(String title, String description, int titleColor, int descriptionColor) {
        SpannableString text = new SpannableString(title + "\n" + description);
        text.setSpan(new ForegroundColorSpan(titleColor), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(descriptionColor), title.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(0.88f), title.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    interface ChoiceListener {
        void onChoice(int which);
    }

    interface ChoiceCommitListener {
        String onChoice(int which);
    }

    static final class Palette {
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
            this.actionPressed = actionPressed;
            this.checkSurface = checkSurface;
            this.danger = danger;
            this.disabledText = disabledText;
            this.disabledBackground = disabledBackground;
        }

        private static Palette from(Context context) {
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
                    Color.rgb(226, 232, 240),
                    Color.WHITE,
                    Color.rgb(185, 28, 28),
                    Color.rgb(148, 163, 184),
                    Color.rgb(241, 245, 249));
        }
    }
}
