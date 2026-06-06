package fyi.r0.gbxcam;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.util.Base64;

import java.util.ArrayList;
import java.util.List;

final class GalleryState {
    final String connected;
    final String savePath;
    final String outputDir;
    final Palette palette;
    final int validationErrors;
    final int validationWarnings;
    final List<GalleryPhoto> photos;

    GalleryState(
            String connected,
            String savePath,
            String outputDir,
            Palette palette,
            int validationErrors,
            int validationWarnings,
            List<GalleryPhoto> photos) {
        this.connected = connected;
        this.savePath = savePath;
        this.outputDir = outputDir;
        this.palette = palette;
        this.validationErrors = validationErrors;
        this.validationWarnings = validationWarnings;
        this.photos = photos;
    }

    static GalleryState fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray items = root.getJSONArray("photos");
        List<GalleryPhoto> photos = new ArrayList<>(items.length());
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            photos.add(GalleryPhoto.builder(
                            item.getString("name"),
                            item.getString("path"),
                            item.getInt("displayIndex"),
                            item.getInt("physicalSlot"),
                            item.getInt("width"),
                            item.getInt("height"))
                    .indexedPixels(decodeIndexedPixels(item.optString("indexedPixels", "")))
                    .blank(item.optBoolean("blank", false))
                    .deleted(item.optBoolean("deleted", false))
                    .border(item.optInt("border", 0))
                    .copy(item.optBoolean("copy", false))
                    .metadataValid(item.optBoolean("metadataValid", false))
                    .ownerUserId(item.optString("ownerUserId", ""))
                    .mergedRgb(item.optBoolean("mergedRgb", false))
                    .mergedKind(item.optString("mergedKind", ""))
                    .mergedSourceCount(item.optInt("mergedSourceCount", 0))
                    .mergedSourceStartDisplayIndex(item.optInt("mergedSourceStartDisplayIndex", -1))
                    .mergedAlgorithm(item.optString("mergedAlgorithm", ""))
                    .build());
        }
        return new GalleryState(
                root.getString("connected"),
                root.getString("savePath"),
                root.getString("outputDir"),
                new Palette(root.optInt("paletteIndex", 0),
                        root.optString("paletteName", "Monochrome - Grayscale")),
                root.optInt("validationErrors", 0),
                root.optInt("validationWarnings", 0),
                photos);
    }

    GalleryState withPalette(Palette palette) {
        return new GalleryState(
                connected,
                savePath,
                outputDir,
                palette,
                validationErrors,
                validationWarnings,
                photos);
    }

    /** A copy of this state with a different photos list; all other fields preserved. */
    GalleryState withPhotos(List<GalleryPhoto> photos) {
        return new GalleryState(
                connected,
                savePath,
                outputDir,
                palette,
                validationErrors,
                validationWarnings,
                photos);
    }

    int selectedCount() {
        int count = 0;
        for (GalleryPhoto photo : photos) {
            if (photo.selected) {
                count++;
            }
        }
        return count;
    }

    int selectedManualMergeCount() {
        int count = 0;
        for (GalleryPhoto photo : photos) {
            if (photo.selected && photo.isManualMerge()) {
                count++;
            }
        }
        return count;
    }

    int selectedMergeableCount() {
        int count = 0;
        for (GalleryPhoto photo : photos) {
            if (photo.selected && photo.isMergeableSource()) {
                count++;
            }
        }
        return count;
    }

    int selectedActiveCount() {
        int count = 0;
        for (GalleryPhoto photo : photos) {
            if (photo.selected && photo.isActiveAlbumPhoto()) {
                count++;
            }
        }
        return count;
    }

    int selectedDeletedCount() {
        int count = 0;
        for (GalleryPhoto photo : photos) {
            if (photo.selected && photo.isDeletedAlbumPhoto()) {
                count++;
            }
        }
        return count;
    }

    void copySelectionFrom(GalleryState previous) {
        for (GalleryPhoto photo : photos) {
            for (GalleryPhoto old : previous.photos) {
                if (sameSelectionIdentity(photo, old)) {
                    photo.selected = old.selected;
                    break;
                }
            }
        }
    }

    private static boolean sameSelectionIdentity(GalleryPhoto photo, GalleryPhoto old) {
        if (photo.isAlbumBacked() && old.isAlbumBacked()) {
            return photo.physicalSlot == old.physicalSlot;
        }
        return photo.mergedRgb && old.mergedRgb && photo.path.equals(old.path);
    }

    private static byte[] decodeIndexedPixels(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        try {
            return Base64.decode(encoded, Base64.DEFAULT);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
