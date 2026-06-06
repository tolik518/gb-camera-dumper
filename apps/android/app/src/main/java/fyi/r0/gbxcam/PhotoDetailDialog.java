package fyi.r0.gbxcam;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The full-screen photo detail dialog: status/metadata chips, the rendered image,
 * swipe navigation between photos, and (for merged photos) order/algorithm
 * dropdowns with a live preview re-merge. Applying/saving a changed merge variant
 * and sharing a single photo are delegated to the {@link Host}.
 */
final class PhotoDetailDialog {

    /** App-side actions the detail dialog defers to. */
    interface Host {
        void applyOrSaveDetailChanges(GalleryPhoto photo, String order, String algorithm);
        void shareSinglePhoto(GalleryPhoto photo);
        /** Commit a pending order/algorithm change, then share the re-merged result. */
        void applyDetailChangesThenShare(GalleryPhoto photo, String order, String algorithm);
    }

    private final Activity activity;
    private final MainScreen screen;
    private final AppSettings settings;
    private final GalleryPipeline pipeline;
    private final Executor previewExecutor;
    private final AppCallback<Runnable> postToUi;
    private final AppSupplier<int[]> currentPaletteColors;
    private final Host host;

    PhotoDetailDialog(Activity activity, MainScreen screen, AppSettings settings, GalleryPipeline pipeline,
            Executor previewExecutor, AppCallback<Runnable> postToUi,
            AppSupplier<int[]> currentPaletteColors, Host host) {
        this.activity = activity;
        this.screen = screen;
        this.settings = settings;
        this.pipeline = pipeline;
        this.previewExecutor = previewExecutor;
        this.postToUi = postToUi;
        this.currentPaletteColors = currentPaletteColors;
        this.host = host;
    }

    void show(GalleryPhoto photo) {
        Dialog dialog = UiStyle.baseDialog(activity);

        // Mutable state shared across in-place navigations
        GalleryPhoto[] photoRef       = { photo };
        String[]       algoRef        = { photo.mergedRgb && photo.mergedAlgorithm != null ? photo.mergedAlgorithm : "" };
        boolean[]      algoChangedRef  = { false };
        String[]       orderRef       = { photo.mergedKind != null && !photo.mergedKind.isEmpty() ? photo.mergedKind
                                          : MergeOrder.defaultFor(photo.mergedSourceCount) };
        boolean[]      orderChangedRef = { false };
        int[]          genRef         = { 0 };

        // The scrollable photo content slot is rebuilt on every navigation;
        // the nav row lives outside the scroll so its position never changes.
        ScrollView photoScroll = new ScrollView(activity);
        photoScroll.setFillViewport(true);

        UiStyle.Palette colors0 = UiStyle.palette(activity);
        Button shareBtn = UiStyle.button(activity, "Share", screen.accentColor(), colors0.surfaceRaised, screen.accentColor());

        // Header title refs — updated on every navigation
        TextView[] titleViewRef    = { null };
        TextView[] subtitleViewRef = { null };

        // Rebuild trigger: resets algo state, repopulates scroll, updates header + nav.
        Runnable[] rebuildRef = { null };
        rebuildRef[0] = () -> {
            GalleryPhoto p = photoRef[0];
            algoRef[0]         = p.mergedRgb && p.mergedAlgorithm != null ? p.mergedAlgorithm : "";
            algoChangedRef[0]  = false;
            orderRef[0]        = p.mergedKind != null && !p.mergedKind.isEmpty() ? p.mergedKind
                                 : MergeOrder.defaultFor(p.mergedSourceCount);
            orderChangedRef[0] = false;
            genRef[0]++;

            // Update header title / subtitle
            if (titleViewRef[0] != null)    titleViewRef[0].setText(photoDetailTitle(p));
            if (subtitleViewRef[0] != null) subtitleViewRef[0].setText(photoDetailSubtitle(p));

            // Rebuild scroll content for the new photo
            photoScroll.removeAllViews();
            photoScroll.addView(buildDetailScrollContent(
                    p, algoRef, algoChangedRef, orderRef, orderChangedRef, genRef,
                    titleViewRef, subtitleViewRef));
            photoScroll.scrollTo(0, 0);

            shareBtn.setOnClickListener(v -> {
                // Share what's displayed: if order/algorithm changed but isn't committed yet
                // (the file is only written on dismiss), commit it first, then share the result.
                if ((algoChangedRef[0] || orderChangedRef[0]) && p.mergedRgb) {
                    algoChangedRef[0] = false;   // committed now — don't re-apply on dismiss
                    orderChangedRef[0] = false;
                    host.applyDetailChangesThenShare(p, orderRef[0], algoRef[0]);
                } else {
                    host.shareSinglePhoto(p);
                }
            });
        };

        // Swipe left/right anywhere in the photo area to navigate.
        GestureDetector swipeDetector = new GestureDetector(
                activity, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2,
                                   float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                // Require a clearly horizontal gesture (1.2× wider than tall) and minimum travel.
                if (Math.abs(dx) <= Math.abs(dy) * 1.2f || Math.abs(dx) < dp(50)) return false;
                GalleryState g = screen.gallery();
                if (g == null) return false;
                int idx = g.photos.indexOf(photoRef[0]);
                if (dx < 0 && idx >= 0 && idx < g.photos.size() - 1) {
                    if ((algoChangedRef[0] || orderChangedRef[0]) && photoRef[0].mergedRgb)
                        host.applyOrSaveDetailChanges(photoRef[0], orderRef[0], algoRef[0]);
                    photoRef[0] = g.photos.get(idx + 1);
                    rebuildRef[0].run();
                    return true;
                } else if (dx > 0 && idx > 0) {
                    if ((algoChangedRef[0] || orderChangedRef[0]) && photoRef[0].mergedRgb)
                        host.applyOrSaveDetailChanges(photoRef[0], orderRef[0], algoRef[0]);
                    photoRef[0] = g.photos.get(idx - 1);
                    rebuildRef[0].run();
                    return true;
                }
                return false;
            }
        });
        photoScroll.setOnTouchListener((v, event) -> {
            swipeDetector.onTouchEvent(event);
            return false;  // let ScrollView handle vertical scrolling normally
        });

        // Outer dialog shell: inline header (holds live title refs) + scroll + share button
        LinearLayout outer = UiStyle.dialogContent(activity, colors0);
        {
            LinearLayout header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout titleBlock = new LinearLayout(activity);
            titleBlock.setOrientation(LinearLayout.VERTICAL);
            TextView titleView = new TextView(activity);
            titleView.setTextColor(colors0.textPrimary);
            titleView.setTextSize(19);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleViewRef[0] = titleView;
            titleBlock.addView(titleView);
            TextView subtitleView = new TextView(activity);
            subtitleView.setTextColor(colors0.textSecondary);
            subtitleView.setTextSize(12);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            subtitleViewRef[0] = subtitleView;
            titleBlock.addView(subtitleView);
            header.addView(titleBlock, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button closeBtn = UiStyle.button(activity, "Close",
                    colors0.textSecondary, colors0.surfaceRaised, colors0.border);
            closeBtn.setTextSize(13);
            closeBtn.setOnClickListener(v -> dialog.dismiss());
            header.addView(closeBtn, new LinearLayout.LayoutParams(dp(82), dp(44)));
            outer.addView(header);
        }
        rebuildRef[0].run();   // sets title, subtitle, scroll content, nav buttons

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.setMargins(0, dp(4), 0, 0);
        outer.addView(photoScroll, scrollParams);

        LinearLayout.LayoutParams shareBtnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        shareBtnParams.setMargins(0, dp(8), 0, 0);
        outer.addView(shareBtn, shareBtnParams);

        dialog.setContentView(outer);
        dialog.setOnDismissListener(d -> {
            if ((algoChangedRef[0] || orderChangedRef[0]) && photoRef[0].mergedRgb)
                host.applyOrSaveDetailChanges(photoRef[0], orderRef[0], algoRef[0]);
        });
        UiStyle.sizeDialog(dialog, activity, 32, 560);
        dialog.show();

        // Lock the dialog height after first layout so navigating between photos
        // (which differ in chip/dropdown count) never shifts the nav row.
        outer.post(() -> {
            if (dialog.isShowing() && dialog.getWindow() != null) {
                WindowManager.LayoutParams wp = dialog.getWindow().getAttributes();
                if (wp.height == WindowManager.LayoutParams.WRAP_CONTENT) {
                    wp.height = outer.getHeight();
                    dialog.getWindow().setAttributes(wp);
                }
            }
        });
    }

    private LinearLayout buildDetailScrollContent(
            GalleryPhoto photo,
            String[]  algoRef,
            boolean[] algoChangedRef,
            String[]  orderRef,
            boolean[] orderChangedRef,
            int[]     genRef,
            TextView[] titleViewRef,
            TextView[] subtitleViewRef) {
        UiStyle.Palette colors = UiStyle.palette(activity);
        int panel      = colors.surface;
        int panelRaised = colors.surfaceRaised;
        int panelSoft  = UiStyle.blend(colors.surfaceRaised, colors.surface, 0.45f);
        int border     = colors.borderStrong;
        int borderSoft = colors.border;
        int textPrimary   = colors.textPrimary;
        int textSecondary = colors.textSecondary;
        int textMuted     = colors.textMuted;
        int danger = colors.danger;
        int accent = screen.accentColor();

        LinearLayout inner = new LinearLayout(activity);
        inner.setOrientation(LinearLayout.VERTICAL);

        // Status chips
        LinearLayout statusRow = new LinearLayout(activity);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(8), 0, 0);
        statusRow.addView(detailChip(
                photo.deleted ? "Deleted" : photo.mergedRgb ? mergedPhotoTitle(photo) : "Original",
                photo.deleted ? danger : accent, panelSoft, photo.deleted ? danger : accent));
        String mergeKindLabel = photo.mergedRgb
                ? (photo.isManualMerge() ? "Manually merged" : "Auto-merged")
                : (photo.copy ? "Copy" : "Camera photo");
        statusRow.addView(detailChip(mergeKindLabel, textSecondary, panelSoft, borderSoft));
        if (photo.mergedRgb && photo.mergedAlgorithm != null && !photo.mergedAlgorithm.isEmpty()) {
            statusRow.addView(detailChip(RgbMergeDetector.algorithmShortLabel(photo.mergedAlgorithm), textSecondary, panelSoft, borderSoft));
        }
        if (!photo.mergedRgb) {
            statusRow.addView(detailChip(
                    photo.metadataValid ? "Metadata OK" : "Check metadata",
                    photo.metadataValid ? textSecondary : danger,
                    panelSoft,
                    photo.metadataValid ? borderSoft : danger));
        }
        inner.addView(statusRow, statusParams);

        // Technical metadata (toggleable)
        if (settings.showPhotoMeta()) {
            LinearLayout infoRow = new LinearLayout(activity);
            infoRow.setOrientation(LinearLayout.HORIZONTAL);
            infoRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            infoParams.setMargins(0, dp(8), 0, 0);
            infoRow.addView(detailChip(
                    photo.mergedRgb ? mergedSourceLabel(photo) : "Album " + String.format(Locale.US, "%02d", photo.displayIndex + 1),
                    textMuted, panel, borderSoft));
            if (!photo.mergedRgb) infoRow.addView(detailChip("Slot " + (photo.physicalSlot + 1), textMuted, panel, borderSoft));
            infoRow.addView(detailChip(photo.width + "×" + photo.height, textMuted, panel, borderSoft));
            infoRow.addView(detailChip("Border " + photo.border, textMuted, panel, borderSoft));
            inner.addView(infoRow, infoParams);

            if (!photo.ownerUserId.isEmpty()) {
                LinearLayout ownerRow = new LinearLayout(activity);
                ownerRow.setOrientation(LinearLayout.HORIZONTAL);
                ownerRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams ownerParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                ownerParams.setMargins(0, dp(8), 0, 0);
                ownerRow.addView(detailChip("Owner " + photo.ownerUserId, textMuted, panel, borderSoft));
                inner.addView(ownerRow, ownerParams);
            }
        }

        // Photo image
        FrameLayout imageMat = new FrameLayout(activity);
        imageMat.setPadding(dp(8), dp(8), dp(8), dp(8));
        imageMat.setBackground(UiStyle.rounded(activity, colors.logBackground, border, 12, 1));
        LinearLayout.LayoutParams matParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        matParams.setMargins(0, dp(12), 0, dp(12));

        ImageView image = new PixelImageView(activity);
        Bitmap bmp = PhotoRenderer.renderBitmap(photo, currentPaletteColors.get());
        if (bmp != null) image.setImageBitmap(bmp);
        else             image.setImageURI(Uri.fromFile(new File(photo.path)));
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAlpha(photo.deleted ? 0.86f : 1.0f);
        imageMat.addView(image, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        final ProgressBar[] progressRef = { null };
        if (photo.mergedRgb) {
            ProgressBar progress = new ProgressBar(activity);
            progress.setIndeterminate(true);
            progress.setVisibility(View.GONE);
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(48), dp(48));
            pp.gravity = Gravity.CENTER;
            imageMat.addView(progress, pp);
            progressRef[0] = progress;
        }
        inner.addView(imageMat, matParams);

        // Order + algorithm selectors (merged photos only)
        if (photo.mergedRgb) {
            boolean isManual = photo.isManualMerge();

            // Channel-order dropdown — only for manual merges (auto-merged order is fixed by detection)
            if (isManual) {
                String[] orders = MergeOrder.optionsFor(photo.mergedSourceCount);
                FrameLayout orderField = new FrameLayout(activity);
                orderField.setBackground(UiStyle.rounded(activity, panelRaised, borderSoft, 8, 1));
                orderField.setClickable(true);
                orderField.setFocusable(true);
                TextView orderDropText = new TextView(activity);
                orderDropText.setSingleLine(true);
                orderDropText.setGravity(Gravity.CENTER_VERTICAL);
                orderDropText.setIncludeFontPadding(false);
                orderDropText.setTextColor(textPrimary);
                orderDropText.setTextSize(13);
                orderDropText.setPadding(dp(14), 0, dp(36), 0);
                orderDropText.setText("Order: " + orderRef[0]);
                orderField.addView(orderDropText, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                TextView orderArrow = new TextView(activity);
                orderArrow.setText("▼");
                orderArrow.setTextSize(10);
                orderArrow.setTextColor(textSecondary);
                orderArrow.setGravity(Gravity.CENTER);
                orderArrow.setEnabled(false);
                FrameLayout.LayoutParams oArrowP = new FrameLayout.LayoutParams(
                        dp(28), FrameLayout.LayoutParams.MATCH_PARENT);
                oArrowP.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
                orderField.addView(orderArrow, oArrowP);
                orderField.setOnClickListener(v -> {
                    int cur = indexOf(orders, orderRef[0]);
                    UiStyle.dropdown(activity, orderField, orders, cur, accent, which -> {
                        if (orders[which].equals(orderRef[0])) return;
                        orderRef[0]        = orders[which];
                        orderChangedRef[0] = true;
                        orderDropText.setText("Order: " + orders[which]);
                        // Update header title ("Merged RGB" → "Merged BRG") immediately
                        if (titleViewRef[0] != null)
                            titleViewRef[0].setText("Merged " + orders[which]);
                        if (subtitleViewRef[0] != null)
                            subtitleViewRef[0].setText(mergedVariantSubtitle(photo, orderRef[0], algoRef[0]));
                        runPreviewMerge(photo, orderRef[0], algoRef[0], image, progressRef[0], genRef);
                    });
                });
                LinearLayout.LayoutParams orderP = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
                orderP.setMargins(0, 0, 0, dp(6));
                inner.addView(orderField, orderP);
            }

            // Algorithm dropdown
            FrameLayout algoField = new FrameLayout(activity);
            algoField.setBackground(UiStyle.rounded(activity, panelRaised, borderSoft, 8, 1));
            algoField.setClickable(true);
            algoField.setFocusable(true);
            TextView algoDropText = new TextView(activity);
            algoDropText.setSingleLine(true);
            algoDropText.setEllipsize(TextUtils.TruncateAt.END);
            algoDropText.setGravity(Gravity.CENTER_VERTICAL);
            algoDropText.setIncludeFontPadding(false);
            algoDropText.setTextColor(textPrimary);
            algoDropText.setTextSize(13);
            algoDropText.setPadding(dp(14), 0, dp(36), 0);
            algoDropText.setText(RgbMergeDetector.algorithmLabel(algoRef[0]));
            algoField.addView(algoDropText, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            TextView algoArrow = new TextView(activity);
            algoArrow.setText("▼");
            algoArrow.setTextSize(10);
            algoArrow.setTextColor(textSecondary);
            algoArrow.setGravity(Gravity.CENTER);
            algoArrow.setEnabled(false);
            FrameLayout.LayoutParams arrowP = new FrameLayout.LayoutParams(
                    dp(28), FrameLayout.LayoutParams.MATCH_PARENT);
            arrowP.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            algoField.addView(algoArrow, arrowP);
            algoField.setOnClickListener(v -> {
                boolean hasClear = photo.mergedSourceCount == 4;
                String[] ids    = RgbMergeDetector.compatibleAlgorithmIds(hasClear);
                String[] labels = RgbMergeDetector.compatibleAlgorithmLabels(hasClear);
                UiStyle.dropdown(activity, algoField, labels, indexOf(ids, algoRef[0]), accent, which -> {
                    if (ids[which].equals(algoRef[0])) return;
                    algoRef[0]        = ids[which];
                    algoChangedRef[0] = true;
                    algoDropText.setText(labels[which]);
                    if (subtitleViewRef[0] != null)
                        subtitleViewRef[0].setText(mergedVariantSubtitle(photo, orderRef[0], algoRef[0]));
                    runPreviewMerge(photo, orderRef[0], algoRef[0], image, progressRef[0], genRef);
                });
            });
            LinearLayout.LayoutParams algoP = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
            algoP.setMargins(0, 0, 0, dp(12));
            inner.addView(algoField, algoP);
        }

        return inner;
    }

    private void runPreviewMerge(
            GalleryPhoto mergedPhoto,
            String order,
            String algorithm,
            ImageView imageView,
            ProgressBar progressBar,
            int[] generation) {
        generation[0]++;
        int gen = generation[0];
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);

        GalleryState gallery = screen.gallery();
        previewExecutor.execute(() -> {
            Bitmap preview = null;
            try {
                List<GalleryPhoto> monoPhotos = pipeline.monoSourcePhotos(gallery);
                int startIdx = mergedPhoto.mergedSourceStartDisplayIndex;
                int count = mergedPhoto.mergedSourceCount;

                // Build a display-index → mono-photo map so we're immune to merged photos
                // being interspersed in gallery.photos and to empty-deleted filtering offsets.
                Map<Integer, GalleryPhoto> monoByIndex = new HashMap<>();
                for (GalleryPhoto mp : monoPhotos) {
                    if (!mp.mergedRgb) monoByIndex.put(mp.displayIndex, mp);
                }

                GalleryPhoto[] sourceForPreview = new GalleryPhoto[count];
                for (int pos = 0; pos < count; pos++) {
                    sourceForPreview[pos] = monoByIndex.get(startIdx + pos);
                }
                preview = RgbMergeDetector.previewMerge(
                        sourceForPreview, order, count, algorithm);
            } catch (Exception ignored) {
            }
            final Bitmap result = preview;
            postToUi.accept(() -> {
                if (gen != generation[0]) {
                    if (result != null) result.recycle();
                    return;
                }
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (result != null) {
                    int w = imageView.getWidth();
                    int h = imageView.getHeight();
                    Bitmap display = (w > 0 && h > 0 && (w != result.getWidth() || h != result.getHeight()))
                            ? Bitmap.createScaledBitmap(
                                    result,
                                    w,
                                    h,
                                    shouldFilterScale(result.getWidth(), result.getHeight(), w, h))
                            : result;
                    if (display != result) result.recycle();
                    imageView.setAdjustViewBounds(false);
                    imageView.setScaleType(ImageView.ScaleType.FIT_XY);
                    imageView.setImageBitmap(display);
                }
            });
        });
    }

    private String photoDetailTitle(GalleryPhoto photo) {
        if (photo.mergedRgb) {
            return mergedPhotoTitle(photo);
        }
        return (photo.deleted ? "Deleted " : "Photo ") + String.format(Locale.US, "%02d", photo.displayIndex + 1);
    }

    private String photoDetailSubtitle(GalleryPhoto photo) {
        if (photo.mergedRgb) {
            return mergedVariantSubtitle(photo,
                    photo.mergedKind != null && !photo.mergedKind.isEmpty() ? photo.mergedKind
                            : MergeOrder.defaultFor(photo.mergedSourceCount),
                    photo.mergedAlgorithm);
        }
        String label = photo.deleted ? "Recoverable" : "Album";
        if (photo.physicalSlot >= 0) {
            return label + " " + String.format(Locale.US, "%02d", photo.displayIndex + 1)
                    + " · Slot " + (photo.physicalSlot + 1);
        }
        return label + " " + String.format(Locale.US, "%02d", photo.displayIndex + 1);
    }

    private String mergedPhotoTitle(GalleryPhoto photo) {
        return "Merged " + (photo.mergedKind == null || photo.mergedKind.isEmpty() ? "RGB" : photo.mergedKind);
    }

    private String mergedSourceLabel(GalleryPhoto photo) {
        return photo.mergedSourceCount > 0
                ? "Sources " + mergedSourceRange(photo)
                : "Sources";
    }

    private String mergedVariantSubtitle(GalleryPhoto photo, String order, String algorithm) {
        StringBuilder subtitle = new StringBuilder(photo.isManualMerge() ? "Manual merge" : "Auto merge");
        if (photo.mergedSourceCount > 0) {
            subtitle.append(" · ").append(mergedSourceRange(photo));
        }
        if (algorithm != null && !algorithm.isEmpty()) {
            subtitle.append(" · ").append(RgbMergeDetector.algorithmShortLabel(algorithm));
        }
        return subtitle.toString();
    }

    private String mergedSourceRange(GalleryPhoto photo) {
        int start = photo.mergedSourceStartDisplayIndex + 1;
        int end = start + Math.max(0, photo.mergedSourceCount - 1);
        return String.format(Locale.US, "%02d-%02d", start, end);
    }

    private TextView detailChip(String text, int textColor, int fillColor, int strokeColor) {
        return UiStyle.chip(activity, text, textColor, fillColor, strokeColor);
    }

    private int dp(int value) {
        return UiStyle.dp(activity, value);
    }

    private static boolean shouldFilterScale(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        return targetWidth < sourceWidth || targetHeight < sourceHeight;
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }
}
