package fyi.r0.gbxcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches per-path "is this PNG a single flat color" checks (blank-photo detection).
 * Shared by the gallery pipeline and the backup preview loader.
 */
final class EmptyImageCache {
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();

    boolean isEmpty(String path) {
        Boolean cached = cache.get(path);
        if (cached != null) return cached;
        boolean empty = decodeAndCheckEmpty(path);
        cache.put(path, empty);
        return empty;
    }

    private static boolean decodeAndCheckEmpty(String path) {
        Bitmap bmp = BitmapFactory.decodeFile(path);
        if (bmp == null) return true;
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        bmp.recycle();
        if (pixels.length == 0) return true;
        int first = pixels[0];
        for (int p : pixels) {
            if (p != first) return false;
        }
        return true;
    }
}
