package fyi.r0.gbxcam;

import android.content.Context;

import java.io.File;

/** App-private file locations and filesystem-safe name helpers. */
final class AppFiles {
    private AppFiles() {
    }

    /** The dumps folder holding the working save and derived data. */
    static File dumpsDir(Context context) {
        return new File(appFilesDir(context, null), "dumps");
    }

    /** External files dir for {@code type} (null for the root), falling back to internal storage. */
    static File appFilesDir(Context context, String type) {
        File dir = context.getExternalFilesDir(type);
        if (dir != null) {
            return dir;
        }
        if (type == null) {
            return context.getFilesDir();
        }
        return new File(context.getFilesDir(), type);
    }

    /** Sanitizes a label for use as a single file-name part. */
    static String safeFilePart(String label) {
        String safe = label.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() ? "save" : safe;
    }

    /** Sanitizes a label for use as a folder name (allows spaces). */
    static String safeFolderName(String label) {
        String safe = label == null ? "" : label.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return safe.isEmpty() ? "Palette" : safe;
    }
}
