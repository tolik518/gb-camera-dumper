package fyi.r0.gbxcam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

final class PhotoExporter {
    private static final int BUFFER_SIZE = 8192;
    private static final String BACKUP_SAVE_NAME = "GAMEBOYCAMERA.sav";
    private static final String IMAGE_MIME_PNG = "image/png";
    private static final String IMAGE_MIME_JPEG = "image/jpeg";

    private PhotoExporter() {
    }

    static final class ExportResult {
        final String imageLocation;
        final String backupLocation;
        final ArrayList<Uri> imageUris;
        final int imageCount;

        ExportResult(String imageLocation, String backupLocation, ArrayList<Uri> imageUris, int imageCount) {
            this.imageLocation = imageLocation;
            this.backupLocation = backupLocation;
            this.imageUris = imageUris;
            this.imageCount = imageCount;
        }

        String summary() {
            return imageLocation + "\nBackup save: " + backupLocation;
        }
    }

    static ExportResult exportSelected(Context context, GalleryState gallery, int[] palette, boolean includeDeleted) throws IOException {
        return exportPhotos(context, gallery, palette, true, includeDeleted);
    }

    private static ExportResult exportPhotos(Context context, GalleryState gallery, int[] palette, boolean selectedOnly, boolean includeDeleted) throws IOException {
        int count = eligiblePhotoCount(gallery, selectedOnly, includeDeleted);
        if (count == 0) {
            throw new IOException(selectedOnly ? "No exportable photos selected." : "No exportable photos.");
        }

        String stamp = timestamp();
        String album = albumPath(gallery, stamp);
        String imageLocation;
        ArrayList<Uri> imageUris;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            imageUris = exportImagesToMediaStore(context, gallery, palette, album, selectedOnly, includeDeleted);
            imageLocation = "Pictures/" + album;
        } else {
            File output = exportImagesToAppFolder(context, gallery, palette, album, selectedOnly, includeDeleted);
            imageUris = new ArrayList<>();
            imageLocation = output.getAbsolutePath();
        }

        File backupDir = appExportDir(context, stamp);
        copyFile(new File(gallery.savePath), new File(backupDir, BACKUP_SAVE_NAME));
        return new ExportResult(imageLocation, backupDir.getAbsolutePath(), imageUris, count);
    }

    /**
     * Exports selected photos as JPEG at the given pixel scale for sharing.
     * Nearest-neighbor scaling preserves the pixel-art look.
     */
    static ExportResult exportSelectedScaled(
            Context context,
            GalleryState gallery,
            int[] palette,
            boolean includeDeleted,
            int scale) throws IOException {
        int count = eligiblePhotoCount(gallery, true, includeDeleted);
        if (count == 0) throw new IOException("No exportable photos selected.");

        int safeScale = Math.max(1, scale);
        String stamp = timestamp();
        String album = albumPath(gallery, stamp);
        String imageLocation;
        ArrayList<Uri> imageUris;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            imageUris = exportScaledToMediaStore(context, gallery, palette, album, includeDeleted, safeScale);
            imageLocation = "Pictures/" + album;
        } else {
            File output = exportScaledToAppFolder(context, gallery, palette, album, includeDeleted, safeScale);
            imageUris = new ArrayList<>();
            imageLocation = output.getAbsolutePath();
        }

        File backupDir = appExportDir(context, stamp);
        copyFile(new File(gallery.savePath), new File(backupDir, BACKUP_SAVE_NAME));
        return new ExportResult(imageLocation, backupDir.getAbsolutePath(), imageUris, count);
    }

    private static ArrayList<Uri> exportScaledToMediaStore(
            Context context, GalleryState gallery, int[] palette,
            String album, boolean includeDeleted, int scale) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ArrayList<Uri> uris = new ArrayList<>();
        for (GalleryPhoto photo : gallery.photos) {
            if (!isExportable(gallery, photo, true, includeDeleted)) continue;
            String jpegName = jpegName(photo.name);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, jpegName);
            values.put(MediaStore.Images.Media.MIME_TYPE, IMAGE_MIME_JPEG);
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + album);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Could not create MediaStore image: " + photo.name);
            try {
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) throw new IOException("Could not open output: " + photo.name);
                    writeScaledJpeg(photo, palette, scale, out);
                }
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                uris.add(uri);
            } catch (IOException | RuntimeException e) {
                resolver.delete(uri, null, null);
                throw e;
            }
        }
        return uris;
    }

    private static File exportScaledToAppFolder(
            Context context, GalleryState gallery, int[] palette,
            String album, boolean includeDeleted, int scale) throws IOException {
        File out = ensureDir(new File(AppFiles.appFilesDir(context, Environment.DIRECTORY_PICTURES), album),
                "Cannot create dir");
        for (GalleryPhoto photo : gallery.photos) {
            if (!isExportable(gallery, photo, true, includeDeleted)) continue;
            String jpegName = jpegName(photo.name);
            try (FileOutputStream stream = new FileOutputStream(new File(out, jpegName))) {
                writeScaledJpeg(photo, palette, scale, stream);
            }
        }
        return out;
    }

    private static void writeScaledJpeg(
            GalleryPhoto photo, int[] palette, int scale, OutputStream out) throws IOException {
        Bitmap bitmap = PhotoRenderer.renderBitmap(photo, palette);
        if (bitmap == null) throw new IOException("Could not render: " + photo.name);
        try {
            Bitmap toWrite = scale > 1
                    ? Bitmap.createScaledBitmap(
                            bitmap, bitmap.getWidth() * scale, bitmap.getHeight() * scale,
                            false)  // false = nearest-neighbour, keeps pixel-art crispness
                    : bitmap;
            if (!toWrite.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                throw new IOException("Could not encode: " + photo.name);
            }
            if (toWrite != bitmap) toWrite.recycle();
        } finally {
            bitmap.recycle();
        }
    }

    static void copyToStream(File source, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (FileInputStream in = new FileInputStream(source)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    static void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create directory: " + parent);
        }
        try (FileOutputStream out = new FileOutputStream(target)) {
            copyToStream(source, out);
        }
    }

    static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static ArrayList<Uri> exportImagesToMediaStore(
            Context context,
            GalleryState gallery,
            int[] palette,
            String album,
            boolean selectedOnly,
            boolean includeDeleted) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ArrayList<Uri> uris = new ArrayList<>();
        for (GalleryPhoto photo : gallery.photos) {
            if (!isExportable(gallery, photo, selectedOnly, includeDeleted)) {
                continue;
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, photo.name);
            values.put(MediaStore.Images.Media.MIME_TYPE, IMAGE_MIME_PNG);
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + album);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Could not create MediaStore image: " + photo.name);
            }

            try {
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) {
                        throw new IOException("Could not open MediaStore output: " + photo.name);
                    }
                    writePhoto(photo, palette, out);
                }

                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                uris.add(uri);
            } catch (IOException | RuntimeException e) {
                resolver.delete(uri, null, null);
                throw e;
            }
        }
        return uris;
    }

    private static File exportImagesToAppFolder(
            Context context,
            GalleryState gallery,
            int[] palette,
            String album,
            boolean selectedOnly,
            boolean includeDeleted) throws IOException {
        File out = ensureDir(new File(AppFiles.appFilesDir(context, Environment.DIRECTORY_PICTURES), album),
                "Could not create export directory");
        for (GalleryPhoto photo : gallery.photos) {
            if (isExportable(gallery, photo, selectedOnly, includeDeleted)) {
                try (FileOutputStream stream = new FileOutputStream(new File(out, photo.name))) {
                    writePhoto(photo, palette, stream);
                }
            }
        }
        return out;
    }

    private static int eligiblePhotoCount(GalleryState gallery, boolean selectedOnly, boolean includeDeleted) {
        int count = 0;
        for (GalleryPhoto photo : gallery.photos) {
            if (isExportable(gallery, photo, selectedOnly, includeDeleted)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isExportable(GalleryState gallery, GalleryPhoto photo, boolean selectedOnly, boolean includeDeleted) {
        if (selectedOnly && !gallery.isSelected(photo)) {
            return false;
        }
        return includeDeleted || !photo.deleted;
    }

    private static File appExportDir(Context context, String stamp) throws IOException {
        File root = new File(AppFiles.appFilesDir(context, null), "exports");
        return ensureDir(new File(root, "gbxcam-" + stamp), "Could not create backup directory");
    }

    private static void writePhoto(GalleryPhoto photo, int[] palette, OutputStream out) throws IOException {
        if (palette != null && PhotoRenderer.writePng(photo, palette, out)) {
            return;
        }
        copyToStream(new File(photo.path), out);
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    private static String albumPath(GalleryState gallery, String stamp) {
        return "GBxCAM Viewer/" + AppFiles.safeFolderName(gallery.palette.name) + "/" + stamp;
    }

    private static File ensureDir(File dir, String errorPrefix) throws IOException {
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException(errorPrefix + ": " + dir);
        }
        return dir;
    }

    private static String jpegName(String name) {
        String base = name == null || name.isEmpty() ? "photo.png" : name;
        String converted = base.replaceFirst("(?i)\\.png$", ".jpg");
        return converted.equals(base) ? base + ".jpg" : converted;
    }
}
