package fyi.r0.gbxcam;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The decode-side gallery transform chain shared by every load path (camera read,
 * cache, palette recolor): apply locally-deleted slots, drop empty-deleted photos,
 * and add auto RGB merges.
 *
 * <p>Manual merges are <em>not</em> injected here — the caller does that via
 * {@link ManualMergeStore}, because the camera/cache paths reload the store while
 * the recolor path must inject on the UI thread to see the latest in-memory state.
 * Everything in this class is safe to run on a worker thread.
 */
final class GalleryPipeline {
    private final AppSettings settings;
    private final AppCallback<String> logger;

    GalleryPipeline(AppSettings settings, AppCallback<String> logger) {
        this.settings = settings;
        this.logger = logger;
    }

    /**
     * Runs the decode-side transforms. {@code applyLocallyDeleted} is true for the
     * cache and recolor paths (which re-apply the locally-deleted slot set) and
     * false for a fresh camera read (which clears that set instead).
     */
    GalleryState process(GalleryState raw, boolean applyLocallyDeleted) {
        GalleryState gallery = raw;
        if (applyLocallyDeleted) {
            gallery = applyLocallyDeletedSlots(gallery);
        }
        return applyAutoRgbMerge(gallery);
    }

    private GalleryState applyLocallyDeletedSlots(GalleryState gallery) {
        Set<String> deleted = settings.locallyDeletedSlots();
        if (deleted.isEmpty()) return gallery;
        List<GalleryPhoto> photos = new ArrayList<>(gallery.photos);
        boolean changed = false;
        for (int i = 0; i < photos.size(); i++) {
            GalleryPhoto p = photos.get(i);
            if (!p.isActiveAlbumPhoto() || p.mergedRgb) continue;
            if (!deleted.contains(String.valueOf(p.physicalSlot))) continue;
            photos.set(i, p.withDeleted(true));
            changed = true;
        }
        if (!changed) return gallery;
        return gallery.withPhotos(photos);
    }

    private GalleryState applyAutoRgbMerge(GalleryState gallery) {
        gallery = filterEmptyDeletedPhotos(gallery);
        if (!settings.autoRgbMerge()) {
            return gallery;
        }
        List<GalleryPhoto> sourcePhotos = monoSourcePhotos(gallery);
        GalleryState merged = RgbMergeDetector.addAutoMergedPhotos(gallery, sourcePhotos, new File(gallery.outputDir),
                settings.rgb4Order(), settings.rgb3Order(), settings.mergeAlgorithm(), settings.mergeAlgorithmOverrides());
        int added = merged.photos.size() - gallery.photos.size();
        if (added > 0) {
            logger.accept("Auto-merged " + added + " RGB " + (added == 1 ? "set" : "sets") + ".");
        }
        return merged;
    }

    private GalleryState filterEmptyDeletedPhotos(GalleryState gallery) {
        List<GalleryPhoto> filtered = null;
        for (int i = 0; i < gallery.photos.size(); i++) {
            GalleryPhoto photo = gallery.photos.get(i);
            if (photo.deleted && photo.blank) {
                if (filtered == null) {
                    filtered = new ArrayList<>(gallery.photos.subList(0, i));
                }
            } else if (filtered != null) {
                filtered.add(photo);
            }
        }
        if (filtered == null) return gallery;
        return gallery.withPhotos(filtered);
    }

    /** The mono (grayscale) source photos used to compute RGB merges. */
    List<GalleryPhoto> monoSourcePhotos(GalleryState gallery) {
        int monoPaletteIndex = NativeGbcam.defaultPaletteIndex();
        if (gallery.paletteIndex == monoPaletteIndex) {
            return gallery.photos;
        }
        try {
            File monoDir = new File(gallery.outputDir, "rgb-merge-mono");
            GalleryState mono = GalleryState.fromJson(NativeGbcam.loadGalleryFromSave(
                    gallery.savePath,
                    monoDir.getAbsolutePath(),
                    monoPaletteIndex));
            // Apply the same empty-deleted filter so indices align with the main gallery.
            return filterEmptyDeletedPhotos(mono).photos;
        } catch (Exception e) {
            return gallery.photos;
        }
    }
}
