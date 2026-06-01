package fyi.r0.gbxcam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

final class PhotoExporter {
    private PhotoExporter() {
    }

    static final class ExportResult {
        final String imageLocation;
        final String backupLocation;
        final ArrayList<Uri> imageUris;

        ExportResult(String imageLocation, String backupLocation, ArrayList<Uri> imageUris) {
            this.imageLocation = imageLocation;
            this.backupLocation = backupLocation;
            this.imageUris = imageUris;
        }

        String summary() {
            return imageLocation + "\nBackup save: " + backupLocation;
        }
    }

    static ExportResult exportSelected(Context context, GalleryState gallery) throws IOException {
        return exportPhotos(context, gallery, true);
    }

    static ExportResult exportAll(Context context, GalleryState gallery) throws IOException {
        return exportPhotos(context, gallery, false);
    }

    static ExportResult exportPhotos(Context context, GalleryState gallery, boolean selectedOnly) throws IOException {
        int count = selectedOnly ? gallery.selectedCount() : gallery.photos.size();
        if (count == 0) {
            throw new IOException("No photos selected.");
        }

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String album = "GBxCAM Viewer/" + safeFolderName(gallery.paletteName) + "/" + stamp;
        String imageLocation;
        ArrayList<Uri> imageUris;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            imageUris = exportImagesToMediaStore(context, gallery, album, selectedOnly);
            imageLocation = "Pictures/" + album;
        } else {
            File output = exportImagesToAppFolder(context, gallery, album, selectedOnly);
            imageUris = new ArrayList<>();
            imageLocation = output.getAbsolutePath();
        }

        File backupDir = appExportDir(context, stamp);
        copy(new File(gallery.savePath), new File(backupDir, "GAMEBOYCAMERA.sav"));
        return new ExportResult(imageLocation, backupDir.getAbsolutePath(), imageUris);
    }

    static void copyToStream(File source, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        try (FileInputStream in = new FileInputStream(source)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static ArrayList<Uri> exportImagesToMediaStore(
            Context context,
            GalleryState gallery,
            String album,
            boolean selectedOnly) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ArrayList<Uri> uris = new ArrayList<>();
        for (GalleryPhoto photo : gallery.photos) {
            if (selectedOnly && !photo.selected) {
                continue;
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, photo.name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
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
                    copyToStream(new File(photo.path), out);
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
            String album,
            boolean selectedOnly) throws IOException {
        File out = new File(appFilesDir(context, Environment.DIRECTORY_PICTURES), album);
        if (!out.mkdirs() && !out.isDirectory()) {
            throw new IOException("Could not create export directory: " + out);
        }
        for (GalleryPhoto photo : gallery.photos) {
            if (!selectedOnly || photo.selected) {
                copy(new File(photo.path), new File(out, photo.name));
            }
        }
        return out;
    }

    private static File appExportDir(Context context, String stamp) throws IOException {
        File root = new File(appFilesDir(context, null), "exports");
        File out = new File(root, "gbxcam-" + stamp);
        if (!out.mkdirs() && !out.isDirectory()) {
            throw new IOException("Could not create backup directory: " + out);
        }
        return out;
    }

    private static File appFilesDir(Context context, String type) {
        File dir = context.getExternalFilesDir(type);
        if (dir != null) {
            return dir;
        }
        if (type == null) {
            return context.getFilesDir();
        }
        return new File(context.getFilesDir(), type);
    }

    private static void copy(File source, File target) throws IOException {
        try (FileOutputStream out = new FileOutputStream(target)) {
            copyToStream(source, out);
        }
    }

    private static String safeFolderName(String label) {
        return label.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
    }
}
