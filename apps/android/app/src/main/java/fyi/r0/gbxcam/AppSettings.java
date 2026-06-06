package fyi.r0.gbxcam;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class AppSettings {
    private static final String PREFS = "gbxcam-viewer";

    private static final String KEY_PALETTE_INDEX = "palette-index";
    private static final String KEY_PALETTE_FAVORITES = "palette-favorites";
    private static final String KEY_PALETTE_RECENT = "palette-recent";
    private static final String KEY_BACKUP_PALETTE_PREFIX = "backup-palette:";
    private static final String KEY_AUTO_LOAD_CAMERA = "auto-load-camera";
    private static final String KEY_LOAD_CACHED_GALLERY = "load-cached-gallery";
    private static final String KEY_SHOW_LOGS = "show-logs";
    private static final String KEY_CONFIRM_ALBUM_WRITES = "confirm-album-writes";
    private static final String KEY_EXPORT_DELETED_PHOTOS = "export-deleted-photos";
    private static final String KEY_AUTO_RGB_MERGE = "auto-rgb-merge";
    private static final String KEY_SHOW_STARTUP_POPUP = "show-startup-popup";
    private static final String KEY_RGB4_ORDER = "rgb4-order";
    private static final String KEY_RGB3_ORDER = "rgb3-order";
    private static final String KEY_DEFAULT_MERGE_ALGO = "default-merge-algo";
    private static final String KEY_MERGE_ALGO_OVERRIDE_PREFIX = "merge-algo-override:";
    private static final String KEY_LOCALLY_DELETED_SLOTS = "locally-deleted-slots";
    private static final String KEY_SHOW_PHOTO_META = "show-photo-meta";
    private static final String KEY_SHARE_MULTIPLIER = "share-multiplier";

    static final String DEFAULT_RGB4_ORDER = "CRGB";
    static final String DEFAULT_RGB3_ORDER = "RGB";

    static final String[] RGB4_ORDERS = {
            "CRGB", "CRBG", "CGBR", "CGRB", "CBRG", "CBGR",
            "RCGB", "RCBG", "RGBC", "RGCB", "RBGC", "RBCG",
            "GCRB", "GCBR", "GRCB", "GRBC", "GBCR", "GBRC",
            "BCRG", "BCGR", "BRCG", "BRGC", "BGCR", "BGRC"
    };
    static final String[] RGB3_ORDERS = {
            "RGB", "RBG", "GRB", "GBR", "BRG", "BGR"
    };

    private final SharedPreferences prefs;

    AppSettings(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    int paletteIndex(int fallback) {
        return prefs.getInt(KEY_PALETTE_INDEX, fallback);
    }

    void savePaletteIndex(int index) {
        prefs.edit().putInt(KEY_PALETTE_INDEX, index).apply();
    }

    boolean autoLoad() {
        return prefs.getBoolean(KEY_AUTO_LOAD_CAMERA, false);
    }

    boolean loadCache() {
        return prefs.getBoolean(KEY_LOAD_CACHED_GALLERY, true);
    }

    boolean showLogs() {
        return prefs.getBoolean(KEY_SHOW_LOGS, false);
    }

    boolean confirmAlbumWrites() {
        return prefs.getBoolean(KEY_CONFIRM_ALBUM_WRITES, true);
    }

    boolean exportDeleted() {
        return prefs.getBoolean(KEY_EXPORT_DELETED_PHOTOS, true);
    }

    boolean autoRgbMerge() {
        return prefs.getBoolean(KEY_AUTO_RGB_MERGE, true);
    }

    boolean showStartupPopup() {
        return prefs.getBoolean(KEY_SHOW_STARTUP_POPUP, true);
    }

    void saveShowStartupPopup(boolean show) {
        prefs.edit().putBoolean(KEY_SHOW_STARTUP_POPUP, show).apply();
    }

    boolean showPhotoMeta() {
        return prefs.getBoolean(KEY_SHOW_PHOTO_META, false);
    }

    void saveShowPhotoMeta(boolean show) {
        prefs.edit().putBoolean(KEY_SHOW_PHOTO_META, show).apply();
    }

    int shareMultiplier() {
        return prefs.getInt(KEY_SHARE_MULTIPLIER, 4);
    }

    void saveShareMultiplier(int multiplier) {
        prefs.edit().putInt(KEY_SHARE_MULTIPLIER, multiplier).apply();
    }

    String rgb4Order() {
        return validChoice(prefs.getString(KEY_RGB4_ORDER, DEFAULT_RGB4_ORDER),
                RGB4_ORDERS, DEFAULT_RGB4_ORDER);
    }

    String rgb3Order() {
        return validChoice(prefs.getString(KEY_RGB3_ORDER, DEFAULT_RGB3_ORDER),
                RGB3_ORDERS, DEFAULT_RGB3_ORDER);
    }

    String mergeAlgorithm() {
        return validAlgorithm(prefs.getString(KEY_DEFAULT_MERGE_ALGO, null), MergeAlgorithm.DEFAULT.id());
    }

    void saveSettings(boolean autoLoad, boolean loadCache, boolean showLogs,
                      boolean confirmAlbumWrites, boolean exportDeleted, boolean autoRgbMerge,
                      String rgb4Order, String rgb3Order, String mergeAlgorithm) {
        prefs.edit()
                .putBoolean(KEY_AUTO_LOAD_CAMERA, autoLoad)
                .putBoolean(KEY_LOAD_CACHED_GALLERY, loadCache)
                .putBoolean(KEY_SHOW_LOGS, showLogs)
                .putBoolean(KEY_CONFIRM_ALBUM_WRITES, confirmAlbumWrites)
                .putBoolean(KEY_EXPORT_DELETED_PHOTOS, exportDeleted)
                .putBoolean(KEY_AUTO_RGB_MERGE, autoRgbMerge)
                .putString(KEY_RGB4_ORDER, validChoice(rgb4Order, RGB4_ORDERS, DEFAULT_RGB4_ORDER))
                .putString(KEY_RGB3_ORDER, validChoice(rgb3Order, RGB3_ORDERS, DEFAULT_RGB3_ORDER))
                .putString(KEY_DEFAULT_MERGE_ALGO, validAlgorithm(mergeAlgorithm, MergeAlgorithm.DEFAULT.id()))
                .apply();
    }

    Set<String> locallyDeletedSlots() {
        return new HashSet<>(prefs.getStringSet(KEY_LOCALLY_DELETED_SLOTS, new HashSet<>()));
    }

    void addLocallyDeletedSlots(Set<String> slots) {
        Set<String> existing = new HashSet<>(locallyDeletedSlots());
        existing.addAll(slots);
        prefs.edit().putStringSet(KEY_LOCALLY_DELETED_SLOTS, existing).apply();
    }

    void removeLocallyDeletedSlots(Set<String> slots) {
        Set<String> existing = new HashSet<>(locallyDeletedSlots());
        existing.removeAll(slots);
        prefs.edit().putStringSet(KEY_LOCALLY_DELETED_SLOTS, existing).apply();
    }

    void clearLocallyDeletedSlots() {
        prefs.edit().remove(KEY_LOCALLY_DELETED_SLOTS).apply();
    }

    Map<String, String> mergeAlgorithmOverrides() {
        Map<String, String> overrides = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith(KEY_MERGE_ALGO_OVERRIDE_PREFIX) && entry.getValue() instanceof String) {
                overrides.put(entry.getKey().substring(KEY_MERGE_ALGO_OVERRIDE_PREFIX.length()),
                        (String) entry.getValue());
            }
        }
        return overrides;
    }

    void saveMergeAlgorithmOverride(GalleryPhoto photo, String algorithm) {
        prefs.edit().putString(KEY_MERGE_ALGO_OVERRIDE_PREFIX + photo.mergeIdentity(),
                validAlgorithm(algorithm, mergeAlgorithm())).apply();
    }

    boolean[] paletteFavorites(String[] labels) {
        Set<String> favorites = new HashSet<>(
                prefs.getStringSet(KEY_PALETTE_FAVORITES, new HashSet<>()));
        boolean[] result = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            result[i] = favorites.contains(labels[i]);
        }
        return result;
    }

    boolean togglePaletteFavorite(String[] labels, int index) {
        Set<String> favorites = new HashSet<>(prefs.getStringSet(KEY_PALETTE_FAVORITES, new HashSet<>()));
        boolean favorite;
        if (favorites.contains(labels[index])) {
            favorites.remove(labels[index]);
            favorite = false;
        } else {
            favorites.add(labels[index]);
            favorite = true;
        }
        prefs.edit().putStringSet(KEY_PALETTE_FAVORITES, new HashSet<>(favorites)).apply();
        return favorite;
    }

    int[] recentPalettes(String[] labels) {
        String stored = prefs.getString(KEY_PALETTE_RECENT, "");
        if (stored.isEmpty()) {
            return new int[0];
        }
        String[] names = stored.split("\\n");
        int[] indices = new int[names.length];
        int count = 0;
        for (String name : names) {
            int index = indexOfLabel(labels, name);
            if (index >= 0) {
                indices[count++] = index;
            }
        }
        return Arrays.copyOf(indices, count);
    }

    void rememberRecentPalette(String[] labels, int paletteIndex) {
        if (paletteIndex < 0 || paletteIndex >= labels.length) {
            return;
        }
        LinkedHashSet<String> recent = new LinkedHashSet<>();
        recent.add(labels[paletteIndex]);
        String stored = prefs.getString(KEY_PALETTE_RECENT, "");
        if (!stored.isEmpty()) {
            for (String label : stored.split("\\n")) {
                if (!label.isEmpty()) {
                    recent.add(label);
                }
                if (recent.size() >= 5) {
                    break;
                }
            }
        }
        prefs.edit().putString(KEY_PALETTE_RECENT, String.join("\n", recent)).apply();
    }

    int backupPaletteIndex(File save, int fallback) {
        return prefs.getInt(backupPaletteKey(save), fallback);
    }

    void rememberBackupPalette(File save, int paletteIndex) {
        prefs.edit().putInt(backupPaletteKey(save), paletteIndex).apply();
    }

    static int indexOfLabel(String[] labels, String label) {
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(label)) {
                return i;
            }
        }
        return -1;
    }

    private String backupPaletteKey(File save) {
        String path;
        try {
            path = save.getCanonicalPath();
        } catch (Exception e) {
            path = save.getAbsolutePath();
        }
        return KEY_BACKUP_PALETTE_PREFIX
                + path
                + ":"
                + save.lastModified()
                + ":"
                + save.length();
    }

    private static String validChoice(String value, String[] valid, String fallback) {
        if (value != null) {
            for (String option : valid) {
                if (option.equals(value)) {
                    return value;
                }
            }
        }
        return fallback;
    }

    /** The id of the known algorithm matching {@code id}, else {@code fallback}. */
    private static String validAlgorithm(String id, String fallback) {
        MergeAlgorithm a = MergeAlgorithm.fromId(id);
        return a != null ? a.id() : fallback;
    }
}
