package com.tolik518.gbcam;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class GalleryState {
    final String connected;
    final String savePath;
    final String outputDir;
    final List<GalleryPhoto> photos;

    GalleryState(String connected, String savePath, String outputDir, List<GalleryPhoto> photos) {
        this.connected = connected;
        this.savePath = savePath;
        this.outputDir = outputDir;
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
                    item.getInt("height")));
        }
        return new GalleryState(
                root.getString("connected"),
                root.getString("savePath"),
                root.getString("outputDir"),
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
}
