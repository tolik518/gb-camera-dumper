package fyi.r0.gbxcam;

final class GalleryPhoto {
    final String name;
    final String path;
    final int displayIndex;
    final int physicalSlot;
    final int width;
    final int height;
    final boolean deleted;
    final int border;
    final boolean copy;
    final boolean metadataValid;
    final String ownerUserId;
    final boolean mergedRgb;
    final String mergedKind;
    final int mergedSourceCount;
    final int mergedSourceStartDisplayIndex;
    final String mergedAlgorithm;
    boolean selected;

    GalleryPhoto(
            String name,
            String path,
            int displayIndex,
            int physicalSlot,
            int width,
            int height,
            boolean deleted,
            int border,
            boolean copy,
            boolean metadataValid,
            String ownerUserId) {
        this(name, path, displayIndex, physicalSlot, width, height,
                deleted, border, copy, metadataValid, ownerUserId,
                false, "", 0, -1, "");
    }

    GalleryPhoto(
            String name,
            String path,
            int displayIndex,
            int physicalSlot,
            int width,
            int height,
            boolean deleted,
            int border,
            boolean copy,
            boolean metadataValid,
            String ownerUserId,
            boolean mergedRgb,
            String mergedKind,
            int mergedSourceCount,
            int mergedSourceStartDisplayIndex) {
        this(name, path, displayIndex, physicalSlot, width, height,
                deleted, border, copy, metadataValid, ownerUserId,
                mergedRgb, mergedKind, mergedSourceCount, mergedSourceStartDisplayIndex, "");
    }

    GalleryPhoto(
            String name,
            String path,
            int displayIndex,
            int physicalSlot,
            int width,
            int height,
            boolean deleted,
            int border,
            boolean copy,
            boolean metadataValid,
            String ownerUserId,
            boolean mergedRgb,
            String mergedKind,
            int mergedSourceCount,
            int mergedSourceStartDisplayIndex,
            String mergedAlgorithm) {
        this.name = name;
        this.path = path;
        this.displayIndex = displayIndex;
        this.physicalSlot = physicalSlot;
        this.width = width;
        this.height = height;
        this.deleted = deleted;
        this.border = border;
        this.copy = copy;
        this.metadataValid = metadataValid;
        this.ownerUserId = ownerUserId;
        this.mergedRgb = mergedRgb;
        this.mergedKind = mergedKind;
        this.mergedSourceCount = mergedSourceCount;
        this.mergedSourceStartDisplayIndex = mergedSourceStartDisplayIndex;
        this.mergedAlgorithm = mergedAlgorithm;
    }
}
