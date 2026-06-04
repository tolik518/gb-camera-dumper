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

final class RgbMergeDetector {

    // --- Algorithm IDs ---------------------------------------------------------

    static final String ALGO_BASIC           = "basic";
    static final String ALGO_CLEAR_LUM       = "clear_lum";
    static final String ALGO_NORM            = "norm";
    static final String ALGO_NORM_CLEAR_LUM  = "norm_clear_lum";
    static final String ALGO_SAT_BOOST       = "sat_boost";
    static final String ALGO_ADAPTIVE        = "adaptive";

    static final String[] ALGORITHM_IDS = {
            ALGO_BASIC, ALGO_CLEAR_LUM, ALGO_NORM, ALGO_NORM_CLEAR_LUM,
            ALGO_SAT_BOOST, ALGO_ADAPTIVE
    };
    static final String[] ALGORITHM_LABELS = {
            "Basic RGB",
            "RGB + Clear Luminance",
            "Normalized RGB",
            "Normalized RGB + Clear Luminance",
            "Saturation Boosted",
            "Experimental Adaptive ★"
    };
    private static final String[] ALGORITHM_SHORT_LABELS = {
            "Basic", "Clear Lum", "Norm RGB", "Norm+Clear", "Sat Boost", "Adaptive ★"
    };

    // --- Detection thresholds --------------------------------------------------

    private static final int RGB_PHASH_MAX_DISTANCE  = 14;
    private static final int CRGB_PHASH_MAX_DISTANCE = 16;
    private static final int DHASH_MAX_DISTANCE      = 24;
    private static final int SHIFT_RANGE             = 8;
    private static final double NCC_MIN              = 0.65;
    private static final int IMAGE_WIDTH             = 128;
    private static final int IMAGE_HEIGHT            = 112;

    private RgbMergeDetector() {}

    // --- Public API ------------------------------------------------------------

    static GalleryState addAutoMergedPhotos(
            GalleryState gallery,
            List<GalleryPhoto> sourcePhotos,
            File outputRoot,
            String order4,
            String order3,
            String defaultAlgorithm,
            Map<String, String> algorithmOverrides) {

        List<GalleryPhoto> output = new ArrayList<>();
        File mergeDir = new File(outputRoot, "rgb-merged");
        deleteOldMergedFiles(mergeDir);
        int mergedCount = 0;

        for (int i = 0; i < gallery.photos.size();) {
            MergeCandidate candidate = null;
            if (i + 4 <= gallery.photos.size()) {
                candidate = evaluate(gallery.photos, sourcePhotos, i, 4, mergeDir, mergedCount + 1,
                        order4, defaultAlgorithm, algorithmOverrides);
            }
            if (candidate == null && i + 3 <= gallery.photos.size()) {
                candidate = evaluate(gallery.photos, sourcePhotos, i, 3, mergeDir, mergedCount + 1,
                        order3, defaultAlgorithm, algorithmOverrides);
            }

            if (candidate == null) {
                output.add(gallery.photos.get(i));
                i++;
                continue;
            }

            for (int s = 0; s < candidate.count; s++) {
                output.add(gallery.photos.get(i + s));
            }
            output.add(candidate.photo);
            mergedCount++;
            i += candidate.count;
        }

        if (mergedCount == 0) {
            return gallery;
        }

        return new GalleryState(
                gallery.connected, gallery.savePath, gallery.outputDir,
                gallery.paletteIndex, gallery.paletteName,
                gallery.validationErrors, gallery.validationWarnings,
                output);
    }

    static GalleryPhoto manualMerge(
            GalleryPhoto[] sources,
            int count,
            String order,
            File outputRoot,
            String defaultAlgorithm) {
        if (count != 3 && count != 4) return null;
        MergeLayout layout = count == 3 ? layoutFromOrder3(order) : layoutFromOrder4(order);
        if (layout == null) return null;
        ImageData[] images = new ImageData[count];
        for (int i = 0; i < count; i++) {
            images[i] = ImageData.from(sources[i]);
            if (images[i] == null) return null;
        }
        File mergeDir = new File(outputRoot, "rgb-merged-manual");
        if (!mergeDir.mkdirs() && !mergeDir.isDirectory()) return null;
        String resolvedAlgorithm = resolveAlgorithm(defaultAlgorithm, layout.clearIndex >= 0);
        // Use physical slot numbers (not display indices) so the filename stays stable
        // even when photos are deleted and the album is renumbered on reload.
        File out = new File(mergeDir, String.format(Locale.US, "MANUAL_s%s_n%d_%s.png",
                slotKey(sources, count), count, layout.label));
        if (!writeMergedPng(images, layout, out, resolvedAlgorithm)) return null;
        return new GalleryPhoto(
                out.getName(), out.getAbsolutePath(),
                sources[0].displayIndex, -1,
                IMAGE_WIDTH, IMAGE_HEIGHT,
                null, false, 0, false, true, "",
                true, layout.label, count, sources[0].displayIndex,
                resolvedAlgorithm);
    }

    /** Returns the algorithm IDs valid for the given set size. */
    static String[] compatibleAlgorithmIds(boolean hasClear) {
        if (hasClear) return ALGORITHM_IDS;
        return new String[]{ ALGO_BASIC, ALGO_NORM, ALGO_SAT_BOOST, ALGO_ADAPTIVE };
    }

    /** Returns the display labels for algorithm IDs valid for the given set size. */
    static String[] compatibleAlgorithmLabels(boolean hasClear) {
        if (hasClear) return ALGORITHM_LABELS;
        return new String[]{ "Basic RGB", "Normalized RGB", "Saturation Boosted", "Experimental Adaptive ★" };
    }

    /** Short label shown in chips/badges. */
    static String algorithmShortLabel(String id) {
        for (int i = 0; i < ALGORITHM_IDS.length; i++) {
            if (ALGORITHM_IDS[i].equals(id)) return ALGORITHM_SHORT_LABELS[i];
        }
        return id;
    }

    /** Full label shown in pickers. */
    static String algorithmLabel(String id) {
        for (int i = 0; i < ALGORITHM_IDS.length; i++) {
            if (ALGORITHM_IDS[i].equals(id)) return ALGORITHM_LABELS[i];
        }
        return id;
    }

    /**
     * Produces a preview Bitmap by merging the given monochrome source photos with the
     * specified algorithm. Returns null if any source photo cannot be loaded.
     * Safe to call from a background thread.
     */
    static Bitmap previewMerge(GalleryPhoto[] sourcePhotos, String order, int count, String algorithm) {
        if (sourcePhotos == null || sourcePhotos.length < count) return null;
        for (int i = 0; i < count; i++) {
            if (sourcePhotos[i] == null) return null;
        }
        MergeLayout layout = count == 3 ? layoutFromOrder3(order) : layoutFromOrder4(order);
        if (layout == null) return null;
        ImageData[] images = new ImageData[count];
        for (int i = 0; i < count; i++) {
            images[i] = ImageData.from(sourcePhotos[i]);
            if (images[i] == null) return null;
        }
        String resolved = resolveAlgorithm(algorithm, layout.clearIndex >= 0);
        int[] pixels = mergePixels(images, layout, resolved);
        Bitmap bmp = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, IMAGE_WIDTH, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        return bmp;
    }

    /**
     * Resolves the requested algorithm for this set, applying a fallback when the
     * requested algorithm requires a clear channel but none is available.
     */
    static String resolveAlgorithm(String requested, boolean hasClear) {
        if (requested == null || requested.isEmpty()) {
            return hasClear ? ALGO_NORM_CLEAR_LUM : ALGO_NORM;
        }
        if (!hasClear) {
            if (ALGO_CLEAR_LUM.equals(requested))      return ALGO_BASIC;
            if (ALGO_NORM_CLEAR_LUM.equals(requested)) return ALGO_NORM;
        }
        return requested;
    }

    // --- Detection internals ---------------------------------------------------

    private static String slotKey(GalleryPhoto[] sources, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append('-');
            sb.append(String.format(Locale.US, "%02d", sources[i].physicalSlot + 1));
        }
        return sb.toString();
    }

    private static void deleteOldMergedFiles(File mergeDir) {
        File[] files = mergeDir.listFiles((dir, name) -> name.startsWith("RGB_") && name.endsWith(".png"));
        if (files == null) return;
        for (File f : files) {
            if (!f.delete()) f.deleteOnExit();
        }
    }

    private static MergeCandidate evaluate(
            List<GalleryPhoto> photos,
            List<GalleryPhoto> sourcePhotos,
            int start, int count,
            File mergeDir, int mergeNumber,
            String order,
            String defaultAlgorithm,
            Map<String, String> algorithmOverrides) {

        GalleryPhoto[] source = new GalleryPhoto[count];
        ImageData[] images = new ImageData[count];
        for (int offset = 0; offset < count; offset++) {
            GalleryPhoto photo = photos.get(start + offset);
            if (photo.deleted || photo.mergedRgb || photo.physicalSlot < 0
                    || photo.displayIndex != photos.get(start).displayIndex + offset) {
                return null;
            }
            source[offset] = photo;
            images[offset] = ImageData.from(sourcePhotos.get(start + offset));
            if (images[offset] == null) return null;
        }

        MergeLayout layout = layoutFor(images, count, order);
        if (layout == null) return null;

        int reference = referenceIndex(images, layout);
        for (int i = 0; i < images.length; i++) {
            if (i != reference && bestNcc(images[reference].blurred, images[i].blurred) < NCC_MIN) {
                return null;
            }
        }

        if (!mergeDir.mkdirs() && !mergeDir.isDirectory()) return null;

        // Resolve algorithm
        String identity = layout.label + ":" + source[0].displayIndex + ":" + count;
        String requested = (algorithmOverrides != null && algorithmOverrides.containsKey(identity))
                ? algorithmOverrides.get(identity)
                : defaultAlgorithm;
        String resolvedAlgorithm = resolveAlgorithm(requested, layout.clearIndex >= 0);

        File out = new File(mergeDir, String.format(Locale.US, "RGB_%02d_from_%02d_%s.png",
                mergeNumber, source[0].displayIndex + 1, layout.label));
        if (!writeMergedPng(images, layout, out, resolvedAlgorithm)) return null;

        GalleryPhoto merged = new GalleryPhoto(
                out.getName(), out.getAbsolutePath(),
                source[0].displayIndex, -1,
                IMAGE_WIDTH, IMAGE_HEIGHT,
                null,
                false, 0, false, true, "",
                true, layout.label, count, source[0].displayIndex,
                resolvedAlgorithm);
        return new MergeCandidate(count, merged);
    }

    // --- Layout helpers --------------------------------------------------------

    private static MergeLayout layoutFor(ImageData[] images, int count, String order) {
        MergeLayout layout = count == 3 ? layoutFromOrder3(order) : layoutFromOrder4(order);
        return layout != null && hashesPass(images, layout) ? layout : null;
    }

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

    private static boolean hashesPass(ImageData[] images, MergeLayout layout) {
        int[] ci = layout.colorIndices;
        for (int i = 0; i < ci.length; i++) {
            for (int j = i + 1; j < ci.length; j++) {
                if (Long.bitCount(images[ci[i]].pHash ^ images[ci[j]].pHash) > layout.pHashMaxDistance) return false;
                if (Long.bitCount(images[ci[i]].dHash ^ images[ci[j]].dHash) > DHASH_MAX_DISTANCE) return false;
            }
        }
        return true;
    }

    private static int referenceIndex(ImageData[] images, MergeLayout layout) {
        int[] ci = layout.colorIndices;
        int best = ci[0];
        double bestAvg = Double.MAX_VALUE;
        for (int idx : ci) {
            double total = 0.0;
            for (int jdx : ci) {
                if (idx != jdx) total += Long.bitCount(images[idx].pHash ^ images[jdx].pHash);
            }
            double avg = total / Math.max(1, ci.length - 1);
            if (avg < bestAvg) { bestAvg = avg; best = idx; }
        }
        return best;
    }

    // --- PNG writing & algorithm dispatch -------------------------------------

    private static boolean writeMergedPng(ImageData[] images, MergeLayout layout, File out, String algorithm) {
        int[] pixels = mergePixels(images, layout, algorithm);
        Bitmap merged = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888);
        merged.setPixels(pixels, 0, IMAGE_WIDTH, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        try (FileOutputStream stream = new FileOutputStream(out)) {
            boolean ok = merged.compress(Bitmap.CompressFormat.PNG, 100, stream);
            merged.recycle();
            return ok;
        } catch (Exception e) {
            merged.recycle();
            return false;
        }
    }

    private static int[] mergePixels(ImageData[] images, MergeLayout layout, String algorithm) {
        int[] red   = images[layout.redIndex].gray;
        int[] green = images[layout.greenIndex].gray;
        int[] blue  = images[layout.blueIndex].gray;
        int[] clear = layout.clearIndex >= 0 ? images[layout.clearIndex].gray : null;

        switch (algorithm) {
            case ALGO_BASIC:
                return composeRgb(red, green, blue, null, 0f);

            case ALGO_CLEAR_LUM:
                return composeRgb(red, green, blue, clear, 1.0f);

            case ALGO_NORM: {
                return composeRgb(normalize(red), normalize(green), normalize(blue), null, 0f);
            }

            case ALGO_NORM_CLEAR_LUM: {
                int[] nc = clear != null ? normalize(clear) : null;
                return composeRgb(normalize(red), normalize(green), normalize(blue), nc, 0.65f);
            }

            case ALGO_SAT_BOOST: {
                int[] base = composeRgb(normalize(red), normalize(green), normalize(blue), null, 0f);
                return boostSaturation(base, 1.25f);
            }

            case ALGO_ADAPTIVE: {
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

    // --- Perceptual hashing & NCC -------------------------------------------

    private static double bestNcc(int[] reference, int[] candidate) {
        double best = -1.0;
        for (int dy = -SHIFT_RANGE; dy <= SHIFT_RANGE; dy++) {
            for (int dx = -SHIFT_RANGE; dx <= SHIFT_RANGE; dx++) {
                best = Math.max(best, ncc(reference, candidate, dx, dy));
            }
        }
        return best;
    }

    private static double ncc(int[] a, int[] b, int dx, int dy) {
        int xStart = Math.max(0, dx);
        int xEnd   = Math.min(IMAGE_WIDTH,  IMAGE_WIDTH  + dx);
        int yStart = Math.max(0, dy);
        int yEnd   = Math.min(IMAGE_HEIGHT, IMAGE_HEIGHT + dy);
        int count  = Math.max(0, xEnd - xStart) * Math.max(0, yEnd - yStart);
        if (count == 0) return -1.0;

        double sumA = 0, sumB = 0;
        for (int y = yStart; y < yEnd; y++) {
            for (int x = xStart; x < xEnd; x++) {
                sumA += a[y * IMAGE_WIDTH + x];
                sumB += b[(y - dy) * IMAGE_WIDTH + (x - dx)];
            }
        }
        double meanA = sumA / count, meanB = sumB / count;
        double num = 0, dA = 0, dB = 0;
        for (int y = yStart; y < yEnd; y++) {
            for (int x = xStart; x < xEnd; x++) {
                double da = a[y * IMAGE_WIDTH + x] - meanA;
                double db = b[(y - dy) * IMAGE_WIDTH + (x - dx)] - meanB;
                num += da * db;
                dA  += da * da;
                dB  += db * db;
            }
        }
        double denom = Math.sqrt(dA * dB);
        return denom == 0.0 ? -1.0 : num / denom;
    }

    // --- Inner classes -------------------------------------------------------

    private static final class MergeCandidate {
        final int count;
        final GalleryPhoto photo;
        MergeCandidate(int count, GalleryPhoto photo) { this.count = count; this.photo = photo; }
    }

    static final class MergeLayout {
        final String label;
        final int clearIndex;
        final int redIndex;
        final int greenIndex;
        final int blueIndex;
        final int[] colorIndices;
        final int pHashMaxDistance;

        MergeLayout(String label, int clearIndex, int redIndex, int greenIndex, int blueIndex) {
            this.label = label;
            this.clearIndex = clearIndex;
            this.redIndex = redIndex;
            this.greenIndex = greenIndex;
            this.blueIndex = blueIndex;
            this.colorIndices = new int[]{ redIndex, greenIndex, blueIndex };
            this.pHashMaxDistance = clearIndex >= 0 ? CRGB_PHASH_MAX_DISTANCE : RGB_PHASH_MAX_DISTANCE;
        }
    }

    private static final class ImageData {
        final int[] gray;
        final int[] blurred;
        final long pHash;
        final long dHash;

        private ImageData(int[] gray, int[] blurred, long pHash, long dHash) {
            this.gray = gray; this.blurred = blurred; this.pHash = pHash; this.dHash = dHash;
        }

        static ImageData from(GalleryPhoto photo) {
            Bitmap bitmap = BitmapFactory.decodeFile(photo.path);
            if (bitmap == null) return null;
            Bitmap scaled = bitmap;
            if (bitmap.getWidth() != IMAGE_WIDTH || bitmap.getHeight() != IMAGE_HEIGHT) {
                scaled = Bitmap.createScaledBitmap(bitmap, IMAGE_WIDTH, IMAGE_HEIGHT, true);
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
            if (scaled != bitmap) scaled.recycle();
            bitmap.recycle();
            return new ImageData(gray, blur(gray), pHash(gray), dHash(gray));
        }
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

    private static long dHash(int[] gray) {
        int[] small = resize(gray, 9, 8);
        long hash = 0L;
        int bit = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (small[y * 9 + x] > small[y * 9 + x + 1]) hash |= 1L << bit;
                bit++;
            }
        }
        return hash;
    }

    private static long pHash(int[] gray) {
        int[] small = resize(gray, 32, 32);
        double[] coeffs = new double[64];
        int index = 0;
        for (int v = 0; v < 8; v++) {
            for (int u = 0; u < 8; u++) {
                coeffs[index++] = dctCoefficient(small, u, v);
            }
        }
        double sum = 0.0;
        for (int i = 1; i < coeffs.length; i++) sum += coeffs[i];
        double avg = sum / (coeffs.length - 1);
        long hash = 0L;
        for (int i = 1; i < coeffs.length; i++) {
            if (coeffs[i] > avg) hash |= 1L << (i - 1);
        }
        return hash;
    }

    private static double dctCoefficient(int[] values, int u, int v) {
        double sum = 0.0;
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                sum += values[y * 32 + x]
                        * Math.cos(((2 * x + 1) * u * Math.PI) / 64.0)
                        * Math.cos(((2 * y + 1) * v * Math.PI) / 64.0);
            }
        }
        return sum;
    }

    private static int[] resize(int[] gray, int width, int height) {
        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) {
            int sy = Math.min(IMAGE_HEIGHT - 1, (y * IMAGE_HEIGHT) / height);
            for (int x = 0; x < width; x++) {
                int sx = Math.min(IMAGE_WIDTH - 1, (x * IMAGE_WIDTH) / width);
                out[y * width + x] = gray[sy * IMAGE_WIDTH + sx];
            }
        }
        return out;
    }
}
