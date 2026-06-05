package fyi.r0.gbxcam;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The palette picker popup: a favorites/recents-ordered list of palettes, each with
 * a 4-color swatch and a favorite star. Reads palette state and reports selection /
 * favorite toggles through a {@link Host} so the live arrays stay owned by
 * {@link MainScreen}.
 */
final class PaletteMenu {
    interface Host {
        String[] paletteLabels();
        boolean[] paletteFavorites();
        int[] recentPalettes();
        int currentPaletteIndex();
        int[] colorsForIndex(int index);
        int accent();
        int accentSurface();
        /** Apply the picked palette (recolor swatch/gallery) without notifying the listener. */
        void applyPalette(int index);
        /** Report that the user changed the palette. */
        void paletteChanged(int index);
        /** Toggle the favorite flag for a palette; updates {@link #paletteFavorites()}. */
        void toggleFavorite(int index);
    }

    private final Context context;
    private final UiStyle.Palette colors;
    private final Host host;

    PaletteMenu(Context context, UiStyle.Palette colors, Host host) {
        this.context = context;
        this.colors = colors;
        this.host = host;
    }

    /** Shows the menu anchored to {@code anchor}; {@code rootWidth} bounds the popup width. */
    void show(View anchor, int rootWidth) {
        String[] labels = host.paletteLabels();
        if (labels.length == 0) {
            return;
        }
        int originalIndex = host.currentPaletteIndex();
        int availableWidth = rootWidth > 0 ? rootWidth - dp(72) : anchor.getWidth();
        int popupWidth = Math.max(dp(220), Math.min(dp(340), Math.max(availableWidth, anchor.getWidth())));
        LinearLayout menu = new LinearLayout(context);
        menu.setOrientation(LinearLayout.VERTICAL);
        ScrollView menuScroll = new ScrollView(context);
        menuScroll.setBackground(UiStyle.rounded(context, colors.surfaceRaised, colors.borderStrong, 8, 1));
        menuScroll.addView(menu);

        PopupWindow popup = new PopupWindow(
                menuScroll,
                popupWidth,
                Math.max(dp(48), Math.min(dp(48) * labels.length, dp(336))),
                true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        popup.setAnimationStyle(android.R.style.Animation);
        for (int index : menuOrder()) {
            menu.addView(menuItem(index, originalIndex, popup));
        }

        popup.showAsDropDown(anchor, anchor.getWidth() - popupWidth, dp(6));
    }

    private View menuItem(int index, int originalIndex, PopupWindow popup) {
        String[] labels = host.paletteLabels();
        boolean[] favorites = host.paletteFavorites();
        int accent = host.accent();
        boolean selected = index == host.currentPaletteIndex();
        LinearLayout item = row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(12), 0, dp(12), 0);
        item.setBackgroundColor(selected ? host.accentSurface() : colors.surfaceRaised);
        item.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)));

        LinearLayout swatch = swatch(dp(56), dp(20));
        setSwatchColors(swatch, host.colorsForIndex(index));
        item.addView(swatch, new LinearLayout.LayoutParams(dp(56), dp(20)));

        TextView label = new TextView(context);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setIncludeFontPadding(false);
        label.setTextSize(13);
        label.setText(labels[index]);
        label.setTextColor(selected ? accent : colors.textPrimary);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1);
        labelParams.setMargins(dp(10), 0, 0, 0);
        item.addView(label, labelParams);

        TextView star = new TextView(context);
        star.setText(favorites[index] ? "★" : "☆");
        star.setTextSize(22);
        star.setGravity(Gravity.CENTER);
        star.setMinWidth(dp(56));
        star.setMinHeight(dp(48));
        star.setPadding(dp(8), 0, dp(8), 0);
        star.setTextColor(favorites[index] ? accent : colors.textSecondary);
        star.setContentDescription((favorites[index] ? "Remove favorite: " : "Add favorite: ") + labels[index]);
        star.setOnClickListener(v -> {
            host.toggleFavorite(index);
            star.setText(favorites[index] ? "★" : "☆");
            star.setTextColor(favorites[index] ? accent : colors.textSecondary);
            star.setContentDescription((favorites[index] ? "Remove favorite: " : "Add favorite: ") + labels[index]);
        });
        item.addView(star, new LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.MATCH_PARENT));

        item.setOnClickListener(v -> {
            popup.dismiss();
            host.applyPalette(index);
            if (index != originalIndex) {
                host.paletteChanged(index);
            }
        });
        return item;
    }

    private int[] menuOrder() {
        String[] labels = host.paletteLabels();
        boolean[] favorites = host.paletteFavorites();
        int[] recent = host.recentPalettes();
        int[] order = new int[labels.length];
        boolean[] added = new boolean[labels.length];
        int count = 0;

        for (int i = 0; i < favorites.length; i++) {
            if (favorites[i]) {
                order[count++] = i;
                added[i] = true;
            }
        }

        if (recent != null) {
            for (int r : recent) {
                int index = Math.max(0, Math.min(r, labels.length - 1));
                if (!added[index]) {
                    order[count++] = index;
                    added[index] = true;
                }
            }
        }

        for (int i = 0; i < labels.length; i++) {
            if (!added[i]) {
                order[count++] = i;
            }
        }

        return order;
    }

    private LinearLayout swatch(int width, int height) {
        LinearLayout swatch = row();
        swatch.setClipToOutline(false);
        swatch.setBackground(UiStyle.rounded(context, colors.surface, colors.borderStrong, 4, 1));
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

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private int dp(int value) {
        return UiStyle.dp(context, value);
    }
}
