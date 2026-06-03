package fyi.r0.gbxcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.IOException;
import java.io.OutputStream;

final class PhotoRenderer {
    private PhotoRenderer() {
    }

    static Bitmap renderBitmap(GalleryPhoto photo, int[] palette) {
        if (!canRenderIndexed(photo)) {
            return BitmapFactory.decodeFile(photo.path);
        }

        int width = photo.width;
        int height = photo.height;
        int count = width * height;
        int[] pixels = new int[count];
        for (int i = 0; i < count; i++) {
            pixels[i] = paletteColor(palette, photo.indexedPixels[i] & 0xFF);
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    static boolean writePng(GalleryPhoto photo, int[] palette, OutputStream out) throws IOException {
        if (!canRenderIndexed(photo)) {
            return false;
        }

        Bitmap bitmap = renderBitmap(photo, palette);
        if (bitmap == null) {
            throw new IOException("Could not render image: " + photo.name);
        }
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("Could not encode image: " + photo.name);
            }
            return true;
        } finally {
            bitmap.recycle();
        }
    }

    private static boolean canRenderIndexed(GalleryPhoto photo) {
        if (photo.mergedRgb || photo.indexedPixels == null || photo.width <= 0 || photo.height <= 0) {
            return false;
        }
        return photo.indexedPixels.length >= photo.width * photo.height;
    }

    private static int paletteColor(int[] palette, int index) {
        if (palette == null || palette.length == 0) {
            return 0xFF000000;
        }
        return palette[Math.min(index, palette.length - 1)];
    }
}
