package com.tolik518.gbcam;

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
import java.util.Date;
import java.util.Locale;

final class PhotoExporter {
    private PhotoExporter() {
    }

    static String exportSelected(Context context, GalleryState gallery) throws IOException {
        int selected = gallery.selectedCount();
        if (selected == 0) {
            throw new IOException("No photos selected.");
        }

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String album = "GBxCAM Viewer/" + stamp;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportImagesToMediaStore(context, gallery, album);
        } else {
            exportImagesToAppFolder(context, gallery, album);
        }

        File backupDir = appExportDir(context, stamp);
        copy(new File(gallery.savePath), new File(backupDir, "GAMEBOYCAMERA.sav"));
        return "Pictures/" + album + "\nBackup save: " + backupDir.getAbsolutePath();
    }

    private static void exportImagesToMediaStore(
            Context context,
            GalleryState gallery,
            String album) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        for (GalleryPhoto photo : gallery.photos) {
            if (!photo.selected) {
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

            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("Could not open MediaStore output: " + photo.name);
                }
                copyToStream(new File(photo.path), out);
            }

            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        }
    }

    private static void exportImagesToAppFolder(
            Context context,
            GalleryState gallery,
            String album) throws IOException {
        File out = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), album);
        if (!out.mkdirs() && !out.isDirectory()) {
            throw new IOException("Could not create export directory: " + out);
        }
        for (GalleryPhoto photo : gallery.photos) {
            if (photo.selected) {
                copy(new File(photo.path), new File(out, photo.name));
            }
        }
    }

    private static File appExportDir(Context context, String stamp) throws IOException {
        File root = new File(context.getExternalFilesDir(null), "exports");
        File out = new File(root, "gbxcam-" + stamp);
        if (!out.mkdirs() && !out.isDirectory()) {
            throw new IOException("Could not create backup directory: " + out);
        }
        return out;
    }

    private static void copy(File source, File target) throws IOException {
        try (FileOutputStream out = new FileOutputStream(target)) {
            copyToStream(source, out);
        }
    }

    private static void copyToStream(File source, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        try (FileInputStream in = new FileInputStream(source)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
