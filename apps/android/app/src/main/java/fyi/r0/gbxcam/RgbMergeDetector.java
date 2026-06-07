package fyi.r0.gbxcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

final class RgbMergeDetector {

    // --- Algorithm tables (single source: MergeAlgorithm) ----------------------

    static final String[] ALGORITHM_IDS = MergeAlgorithm.allIds();
    static final String[] ALGORITHM_LABELS = MergeAlgorithm.allLabels();

    private static final int IMAGE_WIDTH             = 128;
    private static final int IMAGE_HEIGHT            = 112;

    private RgbMergeDetector() {}

    // --- Public API ------------------------------------------------------------

    static GalleryState addAutoMergedPhotos(
            GalleryState gallery,
            File outputRoot,
            String savePath,
            String order4,
            String order3,
            String defaultAlgorithm,
            Map<String, String> algorithmOverrides) {

        File mergeDir = new File(outputRoot, "rgb-merged");
        deleteOldMergedFiles(mergeDir);

        GalleryState coreMerged = addAutoMergedPhotosFromCore(gallery, mergeDir, savePath,
                order4, order3, defaultAlgorithm, algorithmOverrides);
        return coreMerged != null ? coreMerged : gallery;
    }

    private static GalleryState addAutoMergedPhotosFromCore(
            GalleryState gallery,
            File mergeDir,
            String savePath,
            String order4,
            String order3,
            String defaultAlgorithm,
            Map<String, String> algorithmOverrides) {
        if (savePath == null || savePath.isEmpty()) return null;

        List<CoreMergeCandidate> candidates;
        try {
            candidates = parseCoreMergeCandidates(NativeGbcam.detectRgbMergesFromSave(
                    savePath, order4, order3, defaultAlgorithm, overridesJson(algorithmOverrides)));
        } catch (Throwable ignored) {
            return null;
        }
        if (candidates.isEmpty()) return gallery;

        List<GalleryPhoto> output = new ArrayList<>();
        int candidateIndex = 0;
        int mergedCount = 0;
        for (int i = 0; i < gallery.photos.size();) {
            GalleryPhoto current = gallery.photos.get(i);
            while (candidateIndex < candidates.size()
                    && candidates.get(candidateIndex).sourceStartDisplayIndex < current.displayIndex) {
                candidateIndex++;
            }

            CoreMergeCandidate candidate = candidateIndex < candidates.size()
                    && candidates.get(candidateIndex).sourceStartDisplayIndex == current.displayIndex
                    ? candidates.get(candidateIndex)
                    : null;
            GalleryPhoto[] sources = candidate != null ? validatedSources(gallery.photos, i, candidate) : null;
            if (candidate == null || sources == null) {
                output.add(current);
                i++;
                continue;
            }

            MergeLayout layout = candidate.sourceCount == 3
                    ? layoutFromOrder3(candidate.order)
                    : layoutFromOrder4(candidate.order);
            MergeAlgorithm algorithm = MergeAlgorithm.resolve(candidate.algorithm, layout.clearIndex >= 0);
            if (!mergeDir.mkdirs() && !mergeDir.isDirectory()) return null;
            File out = new File(mergeDir, String.format(Locale.US, "RGB_%02d_from_%02d_%s.png",
                    mergedCount + 1, sources[0].displayIndex + 1, layout.label));
            try {
                NativeGbcam.mergeRgbFromSave(savePath, out.getAbsolutePath(),
                        sourceSlotsCsv(sources, candidate.sourceCount), layout.label, algorithm.id());
            } catch (Throwable ignored) {
                if (out.exists() && !out.delete()) out.deleteOnExit();
                return null;
            }
            if (!out.isFile()) return null;

            for (int s = 0; s < candidate.sourceCount; s++) {
                output.add(sources[s]);
            }
            output.add(GalleryPhoto.builder(
                            out.getName(), out.getAbsolutePath(),
                            sources[0].displayIndex, -1,
                            IMAGE_WIDTH, IMAGE_HEIGHT)
                    .metadataValid(true)
                    .mergedRgb(true)
                    .mergedKind(layout.label)
                    .mergedSourceCount(candidate.sourceCount)
                    .mergedSourceStartDisplayIndex(sources[0].displayIndex)
                    .mergedAlgorithm(algorithm.id())
                    .build());
            mergedCount++;
            candidateIndex++;
            i += candidate.sourceCount;
        }

        return mergedCount == 0 ? gallery : gallery.withPhotos(output);
    }

    private static GalleryPhoto[] validatedSources(
            List<GalleryPhoto> photos,
            int start,
            CoreMergeCandidate candidate) {
        if (candidate.sourceCount != 3 && candidate.sourceCount != 4) return null;
        MergeLayout layout = candidate.sourceCount == 3
                ? layoutFromOrder3(candidate.order)
                : layoutFromOrder4(candidate.order);
        if (layout == null) return null;
        if (start + candidate.sourceCount > photos.size()) return null;

        GalleryPhoto first = photos.get(start);
        if (first.displayIndex != candidate.sourceStartDisplayIndex) return null;
        GalleryPhoto[] sources = new GalleryPhoto[candidate.sourceCount];
        for (int offset = 0; offset < candidate.sourceCount; offset++) {
            GalleryPhoto photo = photos.get(start + offset);
            if (photo.deleted || photo.isMerge() || !photo.isAlbumBacked()) return null;
            if (photo.displayIndex != first.displayIndex + offset) return null;
            if (candidate.sourceSlots[offset] != photo.slot.index()) return null;
            sources[offset] = photo;
        }
        return sources;
    }

    private static List<CoreMergeCandidate> parseCoreMergeCandidates(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        List<CoreMergeCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            JSONArray slotsJson = obj.getJSONArray("sourceSlots");
            int count = obj.getInt("sourceCount");
            if (slotsJson.length() != count) continue;
            int[] slots = new int[count];
            for (int s = 0; s < count; s++) {
                slots[s] = slotsJson.getInt(s);
            }
            candidates.add(new CoreMergeCandidate(
                    slots,
                    obj.getInt("sourceStartDisplayIndex"),
                    count,
                    obj.getString("order"),
                    obj.getString("algorithm")));
        }
        return candidates;
    }

    private static String overridesJson(Map<String, String> algorithmOverrides) throws Exception {
        JSONObject obj = new JSONObject();
        if (algorithmOverrides != null) {
            for (Map.Entry<String, String> entry : algorithmOverrides.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }
        }
        return obj.toString();
    }

    static GalleryPhoto manualMerge(
            GalleryPhoto[] sources,
            int count,
            String order,
            File outputRoot,
            String defaultAlgorithm) {
        return manualMerge(sources, count, order, outputRoot, defaultAlgorithm, null);
    }

    static GalleryPhoto manualMerge(
            GalleryPhoto[] sources,
            int count,
            String order,
            File outputRoot,
            String defaultAlgorithm,
            String savePath) {
        if (!hasValidSources(sources, count)) return null;
        MergeLayout layout = count == 3 ? layoutFromOrder3(order) : layoutFromOrder4(order);
        if (layout == null) return null;
        File mergeDir = new File(outputRoot, "rgb-merged-manual");
        if (!mergeDir.mkdirs() && !mergeDir.isDirectory()) return null;
        MergeAlgorithm resolved = MergeAlgorithm.resolve(defaultAlgorithm, layout.clearIndex >= 0);
        File out = uniqueManualMergeFile(mergeDir, sources, count, layout.label, resolved.id());
        if (!writeManualMergePng(sources, count, savePath, layout, out, resolved)) return null;
        return GalleryPhoto.builder(
                        out.getName(), out.getAbsolutePath(),
                        sources[0].displayIndex, -1,
                        IMAGE_WIDTH, IMAGE_HEIGHT)
                .metadataValid(true)
                .mergedRgb(true)
                .mergedKind(layout.label)
                .mergedSourceCount(count)
                .mergedSourceStartDisplayIndex(sources[0].displayIndex)
                .mergedSourceSlots(sourceSlots(sources, count))
                .mergedAlgorithm(resolved.id())
                .manualMerge(true)
                .build();
    }

    /** Returns the algorithm IDs valid for the given set size. */
    static String[] compatibleAlgorithmIds(boolean hasClear) {
        return MergeAlgorithm.compatibleIds(hasClear);
    }

    /** Returns the display labels for algorithm IDs valid for the given set size. */
    static String[] compatibleAlgorithmLabels(boolean hasClear) {
        return MergeAlgorithm.compatibleLabels(hasClear);
    }

    /** Short label shown in chips/badges. */
    static String algorithmShortLabel(String id) {
        MergeAlgorithm a = MergeAlgorithm.fromId(id);
        return a != null ? a.shortLabel() : id;
    }

    /** Full label shown in pickers. */
    static String algorithmLabel(String id) {
        MergeAlgorithm a = MergeAlgorithm.fromId(id);
        return a != null ? a.label() : id;
    }

    /**
     * Produces a preview Bitmap by merging the given monochrome source photos with the
     * specified algorithm. Returns null if any source photo cannot be loaded.
     * Safe to call from a background thread.
     */
    static Bitmap previewMerge(GalleryPhoto[] sourcePhotos, String order, int count, String algorithm) {
        if (!hasValidSources(sourcePhotos, count)) return null;
        MergeLayout layout = count == 3 ? layoutFromOrder3(order) : layoutFromOrder4(order);
        if (layout == null) return null;
        ImageData[] images = new ImageData[count];
        for (int i = 0; i < count; i++) {
            images[i] = ImageData.from(sourcePhotos[i]);
            if (images[i] == null) return null;
        }
        MergeAlgorithm resolved = MergeAlgorithm.resolve(algorithm, layout.clearIndex >= 0);
        int[] pixels = mergePixels(images, layout, resolved);
        Bitmap bmp = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, IMAGE_WIDTH, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        return bmp;
    }

    // --- Detection internals ---------------------------------------------------

    private static String slotKey(GalleryPhoto[] sources, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append('-');
            sb.append(sources[i].slot.twoDigitLabel());
        }
        return sb.toString();
    }

    private static int[] sourceSlots(GalleryPhoto[] sources, int count) {
        int[] slots = new int[count];
        for (int i = 0; i < count; i++) {
            slots[i] = sources[i].slot.index();
        }
        return slots;
    }

    private static File uniqueManualMergeFile(
            File mergeDir, GalleryPhoto[] sources, int count, String order, String algorithm) {
        // Use physical slot numbers (not display indices) so the source part stays stable
        // even when photos are deleted and the album is renumbered on reload.
        String base = String.format(Locale.US, "MANUAL_s%s_n%d_%s_%s",
                slotKey(sources, count), count, order, safeFilePart(algorithm));
        for (int attempt = 0; attempt < 100; attempt++) {
            String suffix = Long.toString(System.currentTimeMillis(), 36)
                    + "-" + Long.toString(System.nanoTime(), 36);
            if (attempt > 0) suffix += "-" + attempt;
            File out = new File(mergeDir, base + "_" + suffix + ".png");
            if (!out.exists()) return out;
        }
        return new File(mergeDir, base + "_" + System.nanoTime() + ".png");
    }

    private static String safeFilePart(String value) {
        if (value == null || value.isEmpty()) return "merge";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static boolean writeManualMergePng(
            GalleryPhoto[] sources, int count, String savePath, MergeLayout layout, File out, MergeAlgorithm algorithm) {
        if (savePath != null && !savePath.isEmpty() && allAlbumBacked(sources, count)) {
            try {
                NativeGbcam.mergeRgbFromSave(savePath, out.getAbsolutePath(),
                        sourceSlotsCsv(sources, count), layout.label, algorithm.id());
                return out.isFile();
            } catch (Throwable ignored) {
                if (out.exists() && !out.delete()) out.deleteOnExit();
            }
        }

        ImageData[] images = new ImageData[count];
        for (int i = 0; i < count; i++) {
            images[i] = ImageData.from(sources[i]);
            if (images[i] == null) return false;
        }
        return writeMergedPng(images, layout, out, algorithm);
    }

    private static boolean allAlbumBacked(GalleryPhoto[] sources, int count) {
        for (int i = 0; i < count; i++) {
            if (sources[i] == null || !sources[i].isAlbumBacked()) {
                return false;
            }
        }
        return true;
    }

    private static String sourceSlotsCsv(GalleryPhoto[] sources, int count) {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(sources[i].slot.index());
        }
        return csv.toString();
    }

    private static boolean hasValidSources(GalleryPhoto[] sources, int count) {
        if (count != 3 && count != 4) return false;
        if (sources == null || sources.length < count) return false;
        for (int i = 0; i < count; i++) {
            if (sources[i] == null) return false;
        }
        return true;
    }

    private static void deleteOldMergedFiles(File mergeDir) {
        File[] files = mergeDir.listFiles((dir, name) -> name.startsWith("RGB_") && name.endsWith(".png"));
        if (files == null) return;
        for (File f : files) {
            if (!f.delete()) f.deleteOnExit();
        }
    }

    // --- Layout helpers --------------------------------------------------------

    static MergeLayout layoutFromOrder4(String order) {
        if (order == null || order.length() != 4) return null;
        int c = -1, r = -1, g = -1, b = -1;
        for (int i = 0; i < 4; i++) {
            switch (order.charAt(i)) {
                case 'C': if (c >= 0) return null; c = i; break;
                case 'R': if (r >= 0) return null; r = i; break;
                case 'G': if (g >= 0) return null; g = i; break;
                case 'B': if (b >= 0) return null; b = i; break;
                default: return null;
            }
        }
        if (c < 0 || r < 0 || g < 0 || b < 0) return null;
        return new MergeLayout(order, c, r, g, b);
    }

    static MergeLayout layoutFromOrder3(String order) {
        if (order == null || order.length() != 3) return null;
        int r = -1, g = -1, b = -1;
        for (int i = 0; i < 3; i++) {
            switch (order.charAt(i)) {
                case 'R': if (r >= 0) return null; r = i; break;
                case 'G': if (g >= 0) return null; g = i; break;
                case 'B': if (b >= 0) return null; b = i; break;
                default: return null;
            }
        }
        if (r < 0 || g < 0 || b < 0) return null;
        return new MergeLayout(order, -1, r, g, b);
    }

    // --- PNG writing & algorithm dispatch -------------------------------------

    private static boolean writeMergedPng(ImageData[] images, MergeLayout layout, File out, MergeAlgorithm algorithm) {
        int[] pixels = mergePixels(images, layout, algorithm);
        Bitmap merged = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888);
        merged.setPixels(pixels, 0, IMAGE_WIDTH, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        try (FileOutputStream stream = new FileOutputStream(out)) {
            return merged.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } catch (Exception e) {
            return false;
        } finally {
            merged.recycle();
        }
    }

    private static int[] mergePixels(ImageData[] images, MergeLayout layout, MergeAlgorithm algorithm) {
        int[] red   = images[layout.redIndex].gray;
        int[] green = images[layout.greenIndex].gray;
        int[] blue  = images[layout.blueIndex].gray;
        int[] clear = layout.clearIndex >= 0 ? images[layout.clearIndex].gray : null;

        switch (algorithm) {
            case BASIC:
                return composeRgb(red, green, blue, null, 0f);

            case CLEAR_LUM:
                return composeRgb(red, green, blue, clear, 1.0f);

            case NORM: {
                return composeRgb(normalize(red), normalize(green), normalize(blue), null, 0f);
            }

            case NORM_CLEAR_LUM: {
                int[] nc = clear != null ? normalize(clear) : null;
                return composeRgb(normalize(red), normalize(green), normalize(blue), nc, 0.65f);
            }

            case SAT_BOOST: {
                int[] base = composeRgb(normalize(red), normalize(green), normalize(blue), null, 0f);
                return boostSaturation(base, 1.25f);
            }

            case GRAY_WORLD: {
                int[][] rgb = normalizedBalancedChannels(red, green, blue);
                return composeRgb(rgb[0], rgb[1], rgb[2], null, 0f);
            }

            case BROVEY_CLEAR: {
                int[][] rgb = normalizedBalancedChannels(red, green, blue);
                int[] nc = clear != null ? normalize2(clear) : null;
                return nc != null ? broveyFusion(rgb[0], rgb[1], rgb[2], nc) : composeRgb(rgb[0], rgb[1], rgb[2], null, 0f);
            }

            case IHS_CLEAR: {
                int[][] rgb = normalizedBalancedChannels(red, green, blue);
                int[] nc = clear != null ? normalize2(clear) : null;
                return nc != null ? replaceLuma(rgb[0], rgb[1], rgb[2], nc) : composeRgb(rgb[0], rgb[1], rgb[2], null, 0f);
            }

            case DETAIL_CLEAR: {
                int[][] rgb = normalizedBalancedChannels(red, green, blue);
                int[] nc = clear != null ? normalize2(clear) : null;
                return nc != null ? injectClearDetail(rgb[0], rgb[1], rgb[2], nc) : composeRgb(rgb[0], rgb[1], rgb[2], null, 0f);
            }

            case ADAPTIVE: {
                int[] nr = normalize2(red);
                int[] ng = normalize2(green);
                int[] nb = normalize2(blue);
                balanceChannels(nr, ng, nb);
                int[] nc = clear != null ? normalize2(clear) : null;
                int[] base = composeRgb(nr, ng, nb, nc, nc != null ? 0.5f : 0f);
                return applyBrightness(base, 1.15f);
            }

            default:
                return composeRgb(red, green, blue, null, 0f);
        }
    }

    // --- Algorithm primitives -------------------------------------------------

    private static int[][] normalizedBalancedChannels(int[] red, int[] green, int[] blue) {
        int[] nr = normalize2(red);
        int[] ng = normalize2(green);
        int[] nb = normalize2(blue);
        balanceChannels(nr, ng, nb);
        return new int[][] { nr, ng, nb };
    }

    /**
     * Composes an RGB pixel array.
     * clearStrength=0 ignores the clear channel entirely.
     * clearStrength=1 fully multiplies each channel by clear/255.
     * Values in between blend between identity and full multiplication.
     */
    private static int[] composeRgb(int[] r, int[] g, int[] b, int[] clear, float clearStrength) {
        int n = r.length;
        int[] pixels = new int[n];
        for (int i = 0; i < n; i++) {
            float cf = clear != null
                    ? (1f - clearStrength) + clearStrength * (clear[i] / 255f)
                    : 1f;
            int rv = clamp(Math.round(r[i] * cf));
            int gv = clamp(Math.round(g[i] * cf));
            int bv = clamp(Math.round(b[i] * cf));
            pixels[i] = 0xFF000000 | (rv << 16) | (gv << 8) | bv;
        }
        return pixels;
    }

    private static int[] broveyFusion(int[] r, int[] g, int[] b, int[] pan) {
        int n = r.length;
        int[] pixels = new int[n];
        for (int i = 0; i < n; i++) {
            float sum = r[i] + g[i] + b[i];
            if (sum <= 1f) {
                pixels[i] = 0xFF000000 | (pan[i] << 16) | (pan[i] << 8) | pan[i];
                continue;
            }
            float scale = (3f * pan[i]) / sum;
            int rv = clamp(Math.round(r[i] * scale));
            int gv = clamp(Math.round(g[i] * scale));
            int bv = clamp(Math.round(b[i] * scale));
            pixels[i] = 0xFF000000 | (rv << 16) | (gv << 8) | bv;
        }
        return pixels;
    }

    private static int[] replaceLuma(int[] r, int[] g, int[] b, int[] luma) {
        int n = r.length;
        int[] pixels = new int[n];
        for (int i = 0; i < n; i++) {
            float y = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i];
            float cr = r[i] - y;
            float cb = b[i] - y;
            float target = luma[i];
            int rv = clamp(Math.round(target + cr));
            int bv = clamp(Math.round(target + cb));
            int gv = clamp(Math.round((target - 0.299f * rv - 0.114f * bv) / 0.587f));
            pixels[i] = 0xFF000000 | (rv << 16) | (gv << 8) | bv;
        }
        return pixels;
    }

    private static int[] injectClearDetail(int[] r, int[] g, int[] b, int[] clear) {
        int[] smooth = blur(clear);
        int n = r.length;
        int[] pixels = new int[n];
        for (int i = 0; i < n; i++) {
            float ratio = (clear[i] + 16f) / (smooth[i] + 16f);
            float factor = 1f + 0.75f * (ratio - 1f);
            int rv = clamp(Math.round(r[i] * factor));
            int gv = clamp(Math.round(g[i] * factor));
            int bv = clamp(Math.round(b[i] * factor));
            pixels[i] = 0xFF000000 | (rv << 16) | (gv << 8) | bv;
        }
        return pixels;
    }

    /** 1%/99% percentile stretch. */
    private static int[] normalize(int[] gray) {
        return normalizePercentile(gray, 0.01f, 0.99f);
    }

    /** 2%/98% percentile stretch (slightly more aggressive, used in adaptive). */
    private static int[] normalize2(int[] gray) {
        return normalizePercentile(gray, 0.02f, 0.98f);
    }

    private static int[] normalizePercentile(int[] gray, float pLow, float pHigh) {
        int[] sorted = Arrays.copyOf(gray, gray.length);
        Arrays.sort(sorted);
        int lo = sorted[Math.max(0, (int) (gray.length * pLow))];
        int hi = sorted[Math.min(gray.length - 1, (int) (gray.length * pHigh))];
        if (hi <= lo) return Arrays.copyOf(gray, gray.length);
        int range = hi - lo;
        int[] result = new int[gray.length];
        for (int i = 0; i < gray.length; i++) {
            result[i] = clamp((gray[i] - lo) * 255 / range);
        }
        return result;
    }

    /** Scales each channel toward the mean of the three channel means. */
    private static void balanceChannels(int[] r, int[] g, int[] b) {
        float mr = mean(r), mg = mean(g), mb = mean(b);
        float target = (mr + mg + mb) / 3f;
        if (mr > 1f) scale(r, target / mr);
        if (mg > 1f) scale(g, target / mg);
        if (mb > 1f) scale(b, target / mb);
    }

    private static float mean(int[] arr) {
        long sum = 0;
        for (int v : arr) sum += v;
        return sum / (float) arr.length;
    }

    private static void scale(int[] arr, float factor) {
        for (int i = 0; i < arr.length; i++) {
            arr[i] = clamp(Math.round(arr[i] * factor));
        }
    }

    /** Multiplies each channel's brightness linearly (not true gamma). */
    private static int[] applyBrightness(int[] pixels, float factor) {
        int[] result = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int rv = clamp(Math.round(((pixels[i] >> 16) & 0xFF) * factor));
            int gv = clamp(Math.round(((pixels[i] >>  8) & 0xFF) * factor));
            int bv = clamp(Math.round(( pixels[i]        & 0xFF) * factor));
            result[i] = 0xFF000000 | (rv << 16) | (gv << 8) | bv;
        }
        return result;
    }

    /** Multiplies HSV saturation by satMult (clamped to [0, 1]). */
    private static int[] boostSaturation(int[] pixels, float satMult) {
        int[] result = new int[pixels.length];
        float[] hsv = new float[3];
        for (int i = 0; i < pixels.length; i++) {
            Color.RGBToHSV((pixels[i] >> 16) & 0xFF, (pixels[i] >> 8) & 0xFF, pixels[i] & 0xFF, hsv);
            hsv[1] = Math.min(1f, hsv[1] * satMult);
            result[i] = Color.HSVToColor(hsv) | 0xFF000000;
        }
        return result;
    }

    private static int clamp(int v) {
        return Math.min(255, Math.max(0, v));
    }

    // --- Inner classes -------------------------------------------------------

    private static final class CoreMergeCandidate {
        final int[] sourceSlots;
        final int sourceStartDisplayIndex;
        final int sourceCount;
        final String order;
        final String algorithm;

        CoreMergeCandidate(
                int[] sourceSlots,
                int sourceStartDisplayIndex,
                int sourceCount,
                String order,
                String algorithm) {
            this.sourceSlots = sourceSlots;
            this.sourceStartDisplayIndex = sourceStartDisplayIndex;
            this.sourceCount = sourceCount;
            this.order = order;
            this.algorithm = algorithm;
        }
    }

    static final class MergeLayout {
        final String label;
        final int clearIndex;
        final int redIndex;
        final int greenIndex;
        final int blueIndex;

        MergeLayout(String label, int clearIndex, int redIndex, int greenIndex, int blueIndex) {
            this.label = label;
            this.clearIndex = clearIndex;
            this.redIndex = redIndex;
            this.greenIndex = greenIndex;
            this.blueIndex = blueIndex;
        }
    }

    private static final class ImageData {
        final int[] gray;

        private ImageData(int[] gray) {
            this.gray = gray;
        }

        static ImageData from(GalleryPhoto photo) {
            Bitmap bitmap = BitmapFactory.decodeFile(photo.path);
            if (bitmap == null) return null;
            Bitmap scaled = bitmap;
            try {
                if (bitmap.getWidth() != IMAGE_WIDTH || bitmap.getHeight() != IMAGE_HEIGHT) {
                    scaled = Bitmap.createScaledBitmap(
                            bitmap,
                            IMAGE_WIDTH,
                            IMAGE_HEIGHT,
                            shouldFilterScale(bitmap.getWidth(), bitmap.getHeight(), IMAGE_WIDTH, IMAGE_HEIGHT));
                }
                int[] pixels = new int[IMAGE_WIDTH * IMAGE_HEIGHT];
                scaled.getPixels(pixels, 0, IMAGE_WIDTH, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
                int[] gray = new int[pixels.length];
                for (int i = 0; i < pixels.length; i++) {
                    int c = pixels[i];
                    gray[i] = Math.round(0.299f * ((c >> 16) & 0xFF)
                            + 0.587f * ((c >> 8) & 0xFF)
                            + 0.114f * (c & 0xFF));
                }
                return new ImageData(gray);
            } finally {
                if (scaled != bitmap) scaled.recycle();
                bitmap.recycle();
            }
        }
    }

    private static boolean shouldFilterScale(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        return targetWidth < sourceWidth || targetHeight < sourceHeight;
    }

    private static int[] blur(int[] gray) {
        int[] out = new int[gray.length];
        for (int y = 0; y < IMAGE_HEIGHT; y++) {
            for (int x = 0; x < IMAGE_WIDTH; x++) {
                int sum = 0, count = 0;
                for (int yy = Math.max(0, y - 1); yy <= Math.min(IMAGE_HEIGHT - 1, y + 1); yy++) {
                    for (int xx = Math.max(0, x - 1); xx <= Math.min(IMAGE_WIDTH - 1, x + 1); xx++) {
                        sum += gray[yy * IMAGE_WIDTH + xx]; count++;
                    }
                }
                out[y * IMAGE_WIDTH + x] = sum / count;
            }
        }
        return out;
    }

}
