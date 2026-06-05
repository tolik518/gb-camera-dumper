package fyi.r0.gbxcam;

final class GalleryPhoto {
    final String name;
    final String path;
    final int displayIndex;
    final int physicalSlot;
    final int width;
    final int height;
    final byte[] indexedPixels;
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
    final boolean manualMerge;
    boolean selected;

    static String mergeIdentity(String kind, int sourceStartDisplayIndex, int sourceCount) {
        return nullToEmpty(kind) + ":" + sourceStartDisplayIndex + ":" + sourceCount;
    }

    private GalleryPhoto(Builder b) {
        this.name = b.name;
        this.path = b.path;
        this.displayIndex = b.displayIndex;
        this.physicalSlot = b.physicalSlot;
        this.width = b.width;
        this.height = b.height;
        this.indexedPixels = b.indexedPixels;
        this.deleted = b.deleted;
        this.border = b.border;
        this.copy = b.copy;
        this.metadataValid = b.metadataValid;
        this.ownerUserId = b.ownerUserId;
        this.mergedRgb = b.mergedRgb;
        this.mergedKind = b.mergedKind;
        this.mergedSourceCount = b.mergedSourceCount;
        this.mergedSourceStartDisplayIndex = b.mergedSourceStartDisplayIndex;
        this.mergedAlgorithm = b.mergedAlgorithm;
        this.manualMerge = b.manualMerge;
    }

    static Builder builder(String name, String path, int displayIndex,
            int physicalSlot, int width, int height) {
        return new Builder(name, path, displayIndex, physicalSlot, width, height);
    }

    /** A builder seeded with this photo's fields (mutable {@code selected} is not copied). */
    Builder toBuilder() {
        return new Builder(name, path, displayIndex, physicalSlot, width, height)
                .indexedPixels(indexedPixels)
                .deleted(deleted)
                .border(border)
                .copy(copy)
                .metadataValid(metadataValid)
                .ownerUserId(ownerUserId)
                .mergedRgb(mergedRgb)
                .mergedKind(mergedKind)
                .mergedSourceCount(mergedSourceCount)
                .mergedSourceStartDisplayIndex(mergedSourceStartDisplayIndex)
                .mergedAlgorithm(mergedAlgorithm)
                .manualMerge(manualMerge);
    }

    boolean isAlbumBacked() {
        return physicalSlot >= 0;
    }

    boolean isActiveAlbumPhoto() {
        return !deleted && isAlbumBacked();
    }

    boolean isDeletedAlbumPhoto() {
        return deleted && isAlbumBacked();
    }

    boolean isManualMerge() {
        return manualMerge;
    }

    boolean isMergeableSource() {
        return !deleted && !mergedRgb && isAlbumBacked();
    }

    String mergeIdentity() {
        return mergeIdentity(mergedKind, mergedSourceStartDisplayIndex, mergedSourceCount);
    }

    GalleryPhoto withDeleted(boolean deleted) {
        return toBuilder().deleted(deleted).build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    static final class Builder {
        private final String name;
        private final String path;
        private final int displayIndex;
        private final int physicalSlot;
        private final int width;
        private final int height;
        private byte[] indexedPixels;
        private boolean deleted;
        private int border;
        private boolean copy;
        private boolean metadataValid;
        private String ownerUserId = "";
        private boolean mergedRgb;
        private String mergedKind = "";
        private int mergedSourceCount;
        private int mergedSourceStartDisplayIndex = -1;
        private String mergedAlgorithm = "";
        private boolean manualMerge;

        private Builder(String name, String path, int displayIndex,
                int physicalSlot, int width, int height) {
            this.name = name;
            this.path = path;
            this.displayIndex = displayIndex;
            this.physicalSlot = physicalSlot;
            this.width = width;
            this.height = height;
        }

        Builder indexedPixels(byte[] v) { this.indexedPixels = v; return this; }
        Builder deleted(boolean v) { this.deleted = v; return this; }
        Builder border(int v) { this.border = v; return this; }
        Builder copy(boolean v) { this.copy = v; return this; }
        Builder metadataValid(boolean v) { this.metadataValid = v; return this; }
        Builder ownerUserId(String v) { this.ownerUserId = v; return this; }
        Builder mergedRgb(boolean v) { this.mergedRgb = v; return this; }
        Builder mergedKind(String v) { this.mergedKind = v; return this; }
        Builder mergedSourceCount(int v) { this.mergedSourceCount = v; return this; }
        Builder mergedSourceStartDisplayIndex(int v) { this.mergedSourceStartDisplayIndex = v; return this; }
        Builder mergedAlgorithm(String v) { this.mergedAlgorithm = v; return this; }
        Builder manualMerge(boolean v) { this.manualMerge = v; return this; }

        GalleryPhoto build() {
            return new GalleryPhoto(this);
        }
    }
}
