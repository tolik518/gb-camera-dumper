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
    final int paletteIndex;
    final String paletteName;
    final int validationErrors;
    final int validationWarnings;
    final List<GalleryPhoto> photos;

    GalleryState(
            String connected,
            String savePath,
            String outputDir,
            int paletteIndex,
            String paletteName,
            int validationErrors,
            int validationWarnings,
            List<GalleryPhoto> photos) {
        this.connected = connected;
        this.savePath = savePath;
        this.outputDir = outputDir;
        this.paletteIndex = paletteIndex;
        this.paletteName = paletteName;
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
            photos.add(new GalleryPhoto(
                    item.getString("name"),
                    item.getString("path"),
                    item.getInt("displayIndex"),
                    item.getInt("physicalSlot"),
                    item.getInt("width"),
                    item.getInt("height"),
                    decodeIndexedPixels(item.optString("indexedPixels", "")),
                    item.optBoolean("deleted", false),
                    item.optInt("border", 0),
                    item.optBoolean("copy", false),
                    item.optBoolean("metadataValid", false),
                    item.optString("ownerUserId", ""),
                    item.optBoolean("mergedRgb", false),
                    item.optString("mergedKind", ""),
                    item.optInt("mergedSourceCount", 0),
                    item.optInt("mergedSourceStartDisplayIndex", -1),
                    item.optString("mergedAlgorithm", "")));
        }
        return new GalleryState(
                root.getString("connected"),
                root.getString("savePath"),
                root.getString("outputDir"),
                root.optInt("paletteIndex", 0),
                root.optString("paletteName", "Monochrome - Grayscale"),
                root.optInt("validationErrors", 0),
                root.optInt("validationWarnings", 0),
                photos);
    }

    GalleryState withPalette(int paletteIndex, String paletteName) {
        return new GalleryState(
                connected,
                savePath,
                outputDir,
                paletteIndex,
                paletteName,
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

    int selectedActiveCount() {
        int count = 0;
        for (GalleryPhoto photo : photos) {
            if (photo.selected && isAlbumBackedActive(photo)) {
                count++;
            }
        }
        return count;
    }

    int selectedDeletedCount() {
        int count = 0;
        for (GalleryPhoto photo : photos) {
            if (photo.selected && photo.deleted) {
                count++;
            }
        }
        return count;
    }

    String selectedPhysicalSlotsCsv() {
        StringBuilder csv = new StringBuilder();
        for (GalleryPhoto photo : photos) {
            if (!photo.selected) {
                continue;
            }
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(photo.physicalSlot);
        }
        return csv.toString();
    }

    String selectedPhysicalSlotsCsv(boolean deleted) {
        StringBuilder csv = new StringBuilder();
        for (GalleryPhoto photo : photos) {
            if (!photo.selected || photo.deleted != deleted || photo.physicalSlot < 0) {
                continue;
            }
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(photo.physicalSlot);
        }
        return csv.toString();
    }

    String activePhysicalSlotsCsv() {
        StringBuilder csv = new StringBuilder();
        for (GalleryPhoto photo : photos) {
            if (photo.deleted || photo.physicalSlot < 0) {
                continue;
            }
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(photo.physicalSlot);
        }
        return csv.toString();
    }

    String selectedActiveFirstPhysicalSlotsCsv() {
        StringBuilder csv = new StringBuilder();
        appendActiveSlots(csv, true);
        appendActiveSlots(csv, false);
        return csv.toString();
    }

    private void appendActiveSlots(StringBuilder csv, boolean selected) {
        for (GalleryPhoto photo : photos) {
            if (photo.deleted || photo.physicalSlot < 0 || photo.selected != selected) {
                continue;
            }
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(photo.physicalSlot);
        }
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

    private static boolean isAlbumBackedActive(GalleryPhoto photo) {
        return !photo.deleted && photo.physicalSlot >= 0;
    }

    private static boolean sameSelectionIdentity(GalleryPhoto photo, GalleryPhoto old) {
        if (photo.physicalSlot >= 0 && old.physicalSlot >= 0) {
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
