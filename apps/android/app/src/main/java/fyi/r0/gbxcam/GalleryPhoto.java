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
    }
}
