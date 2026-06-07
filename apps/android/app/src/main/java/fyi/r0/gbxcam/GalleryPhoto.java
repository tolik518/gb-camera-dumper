package fyi.r0.gbxcam;

final class GalleryPhoto {
    final String name;
    final String path;
    final int displayIndex;
    final Slot slot;
    final int width;
    final int height;
    final byte[] indexedPixels;
    final boolean blank;
    final boolean deleted;
    final int border;
    final boolean copy;
    final boolean metadataValid;
    final String ownerUserId;
    /** Merge descriptor when this photo is an RGB merge; {@code null} for ordinary photos. */
    final MergeInfo merge;

    private GalleryPhoto(Builder b) {
        this.name = b.name;
        this.path = b.path;
        this.displayIndex = b.displayIndex;
        this.slot = Slot.fromPhysicalIndex(b.physicalSlot);
        this.width = b.width;
        this.height = b.height;
        this.indexedPixels = b.indexedPixels;
        this.blank = b.blank;
        this.deleted = b.deleted;
        this.border = b.border;
        this.copy = b.copy;
        this.metadataValid = b.metadataValid;
        this.ownerUserId = b.ownerUserId;
        this.merge = b.mergedRgb
                ? new MergeInfo(b.mergedKind, b.mergedSourceCount,
                        b.mergedSourceStartDisplayIndex, b.mergedSourceSlots,
                        b.mergedAlgorithm, b.manualMerge)
                : null;
    }

    static Builder builder(String name, String path, int displayIndex,
            int physicalSlot, int width, int height) {
        return new Builder(name, path, displayIndex, physicalSlot, width, height);
    }

    /** A builder seeded with this photo's fields. */
    Builder toBuilder() {
        return new Builder(name, path, displayIndex, physicalSlot(), width, height)
                .indexedPixels(indexedPixels)
                .blank(blank)
                .deleted(deleted)
                .border(border)
                .copy(copy)
                .metadataValid(metadataValid)
                .ownerUserId(ownerUserId)
                .mergedRgb(isMerge())
                .mergedKind(mergedKind())
                .mergedSourceCount(mergedSourceCount())
                .mergedSourceStartDisplayIndex(mergedSourceStartDisplayIndex())
                .mergedSourceSlots(mergedSourceSlots())
                .mergedAlgorithm(mergedAlgorithm())
                .manualMerge(isManualMerge());
    }

    boolean isAlbumBacked() {
        return slot != null;
    }

    boolean isActiveAlbumPhoto() {
        return !deleted && isAlbumBacked();
    }

    boolean isDeletedAlbumPhoto() {
        return deleted && isAlbumBacked();
    }

    /** True when this photo is an RGB merge (replaces the former {@code mergedRgb} flag). */
    boolean isMerge() {
        return merge != null;
    }

    boolean isManualMerge() {
        return merge != null && merge.manual;
    }

    boolean isMergeableSource() {
        return !deleted && !isMerge() && isAlbumBacked();
    }

    int physicalSlot() {
        return slot != null ? slot.index() : -1;
    }

    // Null-safe accessors preserving the former field semantics (ordinary photos
    // return the old builder defaults).
    String mergedKind() {
        return merge != null ? merge.kind : "";
    }

    int mergedSourceCount() {
        return merge != null ? merge.sourceCount : 0;
    }

    int mergedSourceStartDisplayIndex() {
        return merge != null ? merge.sourceStartDisplayIndex : -1;
    }

    int[] mergedSourceSlots() {
        if (merge == null || merge.sourceSlots == null || merge.sourceSlots.length == 0) {
            return null;
        }
        int[] out = new int[merge.sourceSlots.length];
        System.arraycopy(merge.sourceSlots, 0, out, 0, merge.sourceSlots.length);
        return out;
    }

    String mergedAlgorithm() {
        return merge != null ? merge.algorithm : "";
    }

    String mergeIdentity() {
        return merge != null ? merge.identity() : MergeInfo.identity("", -1, 0);
    }

    GalleryPhoto withDeleted(boolean deleted) {
        return toBuilder().deleted(deleted).build();
    }

    static final class Builder {
        private final String name;
        private final String path;
        private final int displayIndex;
        private final int physicalSlot;
        private final int width;
        private final int height;
        private byte[] indexedPixels;
        private boolean blank;
        private boolean deleted;
        private int border;
        private boolean copy;
        private boolean metadataValid;
        private String ownerUserId = "";
        private boolean mergedRgb;
        private String mergedKind = "";
        private int mergedSourceCount;
        private int mergedSourceStartDisplayIndex = -1;
        private int[] mergedSourceSlots;
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
        Builder blank(boolean v) { this.blank = v; return this; }
        Builder deleted(boolean v) { this.deleted = v; return this; }
        Builder border(int v) { this.border = v; return this; }
        Builder copy(boolean v) { this.copy = v; return this; }
        Builder metadataValid(boolean v) { this.metadataValid = v; return this; }
        Builder ownerUserId(String v) { this.ownerUserId = v; return this; }
        Builder mergedRgb(boolean v) { this.mergedRgb = v; return this; }
        Builder mergedKind(String v) { this.mergedKind = v; return this; }
        Builder mergedSourceCount(int v) { this.mergedSourceCount = v; return this; }
        Builder mergedSourceStartDisplayIndex(int v) { this.mergedSourceStartDisplayIndex = v; return this; }
        Builder mergedSourceSlots(int[] v) { this.mergedSourceSlots = v; return this; }
        Builder mergedAlgorithm(String v) { this.mergedAlgorithm = v; return this; }
        Builder manualMerge(boolean v) { this.manualMerge = v; return this; }

        GalleryPhoto build() {
            return new GalleryPhoto(this);
        }
    }
}
