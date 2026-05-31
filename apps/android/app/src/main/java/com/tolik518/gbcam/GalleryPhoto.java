package com.tolik518.gbcam;

final class GalleryPhoto {
    final String name;
    final String path;
    final int displayIndex;
    final int physicalSlot;
    final int width;
    final int height;
    boolean selected;

    GalleryPhoto(String name, String path, int displayIndex, int physicalSlot, int width, int height) {
        this.name = name;
        this.path = path;
        this.displayIndex = displayIndex;
        this.physicalSlot = physicalSlot;
        this.width = width;
        this.height = height;
    }
}
