package fyi.r0.gbxcam;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Owns the dumps folder's backup saves: enumeration, thumbnail previews (with an
 * on-disk preview cache), the per-save palette memory, and import/export-save file
 * copies. The picker <em>dialog</em> lives in the UI layer; this is the data side.
 */
final class BackupRepository {
    private static final String TAG = "GbcamApp";
    private static final String WORKING_SAVE_NAME = "GAMEBOYCAMERA.sav";
    private static final String PREVIEW_MANIFEST = "preview-photos.txt";

    private final Context context;
    private final AppSettings settings;

    BackupRepository(Context context, AppSettings settings) {
        this.context = context;
        this.settings = settings;
    }

    /** All backup saves in the dumps folder, newest first. Empty array when none. */
    File[] listBackups() {
        File[] saves = AppFiles.dumpsDir(context).listFiles(
                (dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".sav"));
        if (saves == null) {
            return new File[0];
        }
        Arrays.sort(saves, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        return saves;
    }

    int paletteIndexFor(File save) {
        return settings.backupPaletteIndex(save, NativeGbcam.defaultPaletteIndex());
    }

    void rememberPalette(File save, int paletteIndex) {
        settings.rememberBackupPalette(save, paletteIndex);
    }

    /** Up to four representative photos for a backup save's thumbnail mosaic. */
    GalleryPhoto[] previewPhotos(File save) {
        GalleryPhoto[] preview = new GalleryPhoto[4];
        try {
            int backupPalette = paletteIndexFor(save);
            File output = new File(
                    AppFiles.appFilesDir(context, null),
                    "backup-previews/" + AppFiles.safeFilePart(save.getName()) + "-" + save.lastModified() + "-p" + backupPalette);

            // Fast path: if preview PNGs already exist on disk, use them without re-decoding.
            if (output.isDirectory()) {
                GalleryPhoto[] cached = loadCachedPreviews(output);
                if (cached != null) return cached;
            }

            if (!output.mkdirs() && !output.isDirectory()) {
                return preview;
            }
            GalleryState gallery = GalleryState.fromJson(NativeGbcam.loadGalleryFromSave(
                    save.getAbsolutePath(),
                    output.getAbsolutePath(),
                    backupPalette));
            List<GalleryPhoto> active = new ArrayList<>();
            for (GalleryPhoto p : gallery.photos) {
                if (!p.deleted && !p.blank) active.add(p);
            }
            writePreviewManifest(output, active);
            if (active.isEmpty()) {
                return preview;
            }

            int[] indices = previewIndices(active.size());
            for (int i = 0; i < indices.length; i++) {
                preview[i] = active.get(indices[i]);
            }
        } catch (Exception e) {
            Log.w(TAG, "Backup preview failed for " + save.getName(), e);
        }
        return preview;
    }

    private GalleryPhoto[] loadCachedPreviews(File dir) {
        File manifest = new File(dir, PREVIEW_MANIFEST);
        if (!manifest.isFile()) return null;

        List<File> found = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(manifest))) {
            String name;
            while ((name = reader.readLine()) != null) {
                File f = new File(dir, name);
                if (f.isFile()) {
                    found.add(f);
                }
            }
        } catch (Exception e) {
            return null;
        }
        if (found.isEmpty()) return null;

        int[] indices = previewIndices(found.size());
        GalleryPhoto[] preview = new GalleryPhoto[4];
        for (int i = 0; i < Math.min(indices.length, preview.length); i++) {
            File f = found.get(indices[i]);
            preview[i] = GalleryPhoto.builder(f.getName(), f.getAbsolutePath(),
                    indices[i], indices[i], 128, 112)
                    .metadataValid(true)
                    .build();
        }
        return preview;
    }

    private static void writePreviewManifest(File dir, List<GalleryPhoto> active) {
        File manifest = new File(dir, PREVIEW_MANIFEST);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(manifest))) {
            for (GalleryPhoto photo : active) {
                writer.write(photo.name);
                writer.newLine();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not write backup preview manifest: " + manifest, e);
        }
    }

    private static int[] previewIndices(int count) {
        if (count <= 4) {
            int[] indices = new int[count];
            for (int i = 0; i < count; i++) {
                indices[i] = i;
            }
            return indices;
        }
        return new int[] {
                0,
                count / 3,
                (count * 2) / 3,
                count - 1
        };
    }

    /**
     * Imports a save from {@code uri}: copies it to a scratch file, validates it by
     * decoding with {@code paletteIndex}, installs it as the working save, and returns
     * the installed file. Throws on any failure.
     */
    File importSave(Uri uri, int paletteIndex) throws Exception {
        File dumps = AppFiles.dumpsDir(context);
        File target = new File(dumps, WORKING_SAVE_NAME);
        File importCheck = new File(AppFiles.appFilesDir(context, null), "import-check.sav");
        if (!dumps.mkdirs() && !dumps.isDirectory()) {
            throw new Exception("Could not create dumps directory.");
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(importCheck)) {
            if (in == null) {
                throw new Exception("Could not open imported save.");
            }
            PhotoExporter.copyStream(in, out);
        }
        NativeGbcam.loadGalleryFromSave(
                importCheck.getAbsolutePath(),
                dumps.getAbsolutePath(),
                paletteIndex);
        PhotoExporter.copyFile(importCheck, target);
        if (!importCheck.delete()) {
            Log.w(TAG, "Could not delete import check file: " + importCheck);
        }
        return target;
    }

    /** Writes {@code source} to the document at {@code uri}. Throws on failure. */
    void exportSave(Uri uri, File source) throws Exception {
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                throw new Exception("Could not open export target.");
            }
            PhotoExporter.copyToStream(source, out);
        }
    }
}
