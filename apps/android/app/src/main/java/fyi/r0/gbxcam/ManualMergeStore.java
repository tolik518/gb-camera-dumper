package fyi.r0.gbxcam;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the manual RGB-merge variants and their persistence in
 * {@code manual-merges.json}: the in-memory list, load/save, add/replace/remove,
 * the insert-position math, and injecting known merges back into a gallery.
 */
final class ManualMergeStore {
    private static final String TAG = "GbcamApp";
    private static final String FILE_NAME = "manual-merges.json";

    private final Context context;
    private final List<GalleryPhoto> manualMerges = new ArrayList<>();

    ManualMergeStore(Context context) {
        this.context = context;
    }

    /** Adds {@code m}, or replaces an existing variant with the same output path; persists. */
    void addVariant(GalleryPhoto m) {
        for (int i = 0; i < manualMerges.size(); i++) {
            if (manualMerges.get(i).path.equals(m.path)) {
                manualMerges.set(i, m);
                save();
                return;
            }
        }
        manualMerges.add(m);
        save();
    }

    /** Replaces the variant at {@code oldPath} with {@code updated}; returns true when found. */
    boolean replaceVariant(String oldPath, GalleryPhoto updated) {
        for (int i = 0; i < manualMerges.size(); i++) {
            GalleryPhoto e = manualMerges.get(i);
            if (!e.path.equals(oldPath)) continue;
            if (!oldPath.equals(updated.path)) new File(oldPath).delete();
            manualMerges.set(i, updated);
            save();
            return true;
        }
        return false;
    }

    /** Removes the first variant whose path equals {@code path}. Does not persist; call {@link #save()}. */
    boolean removeByPath(String path) {
        for (int i = 0; i < manualMerges.size(); i++) {
            if (manualMerges.get(i).path.equals(path)) {
                manualMerges.remove(i);
                return true;
            }
        }
        return false;
    }

    void save() {
        try {
            JSONArray arr = new JSONArray();
            for (GalleryPhoto m : manualMerges) {
                JSONObject obj = new JSONObject();
                obj.put("name", m.name);
                obj.put("path", m.path);
                obj.put("displayIndex", m.displayIndex);
                obj.put("mergedKind", m.mergedKind != null ? m.mergedKind : "");
                obj.put("mergedSourceCount", m.mergedSourceCount);
                obj.put("mergedSourceStartDisplayIndex", m.mergedSourceStartDisplayIndex);
                obj.put("mergedAlgorithm", m.mergedAlgorithm != null ? m.mergedAlgorithm : "");
                obj.put("deleted", m.deleted);
                obj.put("manualMerge", m.manualMerge);
                arr.put(obj);
            }
            File dir = AppFiles.dumpsDir(context);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(new File(dir, FILE_NAME))) {
                out.write(arr.toString().getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }

    void load() {
        manualMerges.clear();
        File f = new File(AppFiles.dumpsDir(context), FILE_NAME);
        if (!f.isFile()) return;
        try {
            String json;
            try (FileInputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
                json = buf.toString("UTF-8");
            }
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String path = obj.getString("path");
                if (!new File(path).exists()) continue;
                manualMerges.add(GalleryPhoto.builder(
                                obj.getString("name"),
                                path,
                                obj.getInt("displayIndex"),
                                -1, 128, 112)
                        .deleted(obj.optBoolean("deleted", false))
                        .metadataValid(true)
                        .mergedRgb(true)
                        .mergedKind(obj.optString("mergedKind", ""))
                        .mergedSourceCount(obj.optInt("mergedSourceCount", 0))
                        .mergedSourceStartDisplayIndex(obj.optInt("mergedSourceStartDisplayIndex", -1))
                        .mergedAlgorithm(obj.optString("mergedAlgorithm", ""))
                        // Fall back to the old path heuristic for files written before
                        // the explicit manualMerge field existed.
                        .manualMerge(obj.optBoolean("manualMerge", path.contains("rgb-merged-manual")))
                        .build());
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load manual merges", e);
        }
    }

    /** Inserts known manual merges into {@code gallery} at their computed positions. */
    GalleryState inject(GalleryState gallery) {
        List<GalleryPhoto> merges = new ArrayList<>(manualMerges);
        if (merges.isEmpty()) return gallery;
        List<GalleryPhoto> photos = null;
        for (GalleryPhoto m : merges) {
            if (!new File(m.path).exists()) continue;
            boolean alreadyPresent = false;
            List<GalleryPhoto> list = photos != null ? photos : gallery.photos;
            for (int i = 0; i < list.size(); i++) {
                GalleryPhoto p = list.get(i);
                if (p.mergedRgb && p.path.equals(m.path)) { alreadyPresent = true; break; }
            }
            if (alreadyPresent) continue;
            if (photos == null) photos = new ArrayList<>(gallery.photos);
            int insertAt = insertIndex(photos, m);
            if (insertAt < 0) continue;
            photos.add(insertAt, m);
        }
        if (photos == null) return gallery;
        return gallery.withPhotos(photos);
    }

    /** Index at which {@code merge} should be inserted into {@code photos}, or -1 if its sources are absent. */
    static int insertIndex(List<GalleryPhoto> photos, GalleryPhoto merge) {
        int endIdx = merge.mergedSourceStartDisplayIndex + merge.mergedSourceCount - 1;
        int insertAt = -1;
        for (int i = 0; i < photos.size(); i++) {
            GalleryPhoto p = photos.get(i);
            if (!p.mergedRgb && !p.deleted && p.displayIndex == endIdx) {
                insertAt = i + 1;
                break;
            }
        }
        if (insertAt < 0) return -1;
        while (insertAt < photos.size()) {
            GalleryPhoto p = photos.get(insertAt);
            if (!p.mergedRgb
                    || p.mergedSourceStartDisplayIndex != merge.mergedSourceStartDisplayIndex
                    || p.mergedSourceCount != merge.mergedSourceCount) {
                break;
            }
            insertAt++;
        }
        return insertAt;
    }
}
