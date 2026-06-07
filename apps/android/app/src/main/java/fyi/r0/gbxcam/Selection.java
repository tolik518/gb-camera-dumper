package fyi.r0.gbxcam;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The user's working photo selection, separate from the immutable gallery photos.
 * Album photos are selected by physical slot; merged photos by output path.
 */
final class Selection {
    private static final Selection EMPTY = new Selection(Collections.emptySet());

    private final Set<String> keys;

    private Selection(Set<String> keys) {
        this.keys = keys;
    }

    static Selection empty() {
        return EMPTY;
    }

    static Selection all(List<GalleryPhoto> photos) {
        Set<String> keys = new HashSet<>();
        for (GalleryPhoto photo : photos) {
            keys.add(key(photo));
        }
        return keys.isEmpty() ? EMPTY : new Selection(keys);
    }

    boolean contains(GalleryPhoto photo) {
        return keys.contains(key(photo));
    }

    Selection with(GalleryPhoto photo, boolean selected) {
        String key = key(photo);
        boolean alreadySelected = keys.contains(key);
        if (selected == alreadySelected) {
            return this;
        }
        Set<String> next = new HashSet<>(keys);
        if (selected) {
            next.add(key);
        } else {
            next.remove(key);
        }
        return next.isEmpty() ? EMPTY : new Selection(next);
    }

    Selection toggle(GalleryPhoto photo) {
        return with(photo, !contains(photo));
    }

    Selection retainFor(List<GalleryPhoto> photos) {
        if (keys.isEmpty()) {
            return this;
        }
        Set<String> visible = new HashSet<>();
        for (GalleryPhoto photo : photos) {
            String key = key(photo);
            if (keys.contains(key)) {
                visible.add(key);
            }
        }
        return visible.isEmpty() ? EMPTY : new Selection(visible);
    }

    int count(List<GalleryPhoto> photos) {
        int count = 0;
        for (GalleryPhoto photo : photos) {
            if (contains(photo)) {
                count++;
            }
        }
        return count;
    }

    int manualMergeCount(List<GalleryPhoto> photos) {
        int count = 0;
        for (GalleryPhoto photo : photos) {
            if (contains(photo) && photo.isManualMerge()) {
                count++;
            }
        }
        return count;
    }

    int mergeableCount(List<GalleryPhoto> photos) {
        int count = 0;
        for (GalleryPhoto photo : photos) {
            if (contains(photo) && photo.isMergeableSource()) {
                count++;
            }
        }
        return count;
    }

    int activeCount(List<GalleryPhoto> photos) {
        int count = 0;
        for (GalleryPhoto photo : photos) {
            if (contains(photo) && photo.isActiveAlbumPhoto()) {
                count++;
            }
        }
        return count;
    }

    int deletedCount(List<GalleryPhoto> photos) {
        int count = 0;
        for (GalleryPhoto photo : photos) {
            if (contains(photo) && photo.isDeletedAlbumPhoto()) {
                count++;
            }
        }
        return count;
    }

    private static String key(GalleryPhoto photo) {
        if (photo.isAlbumBacked()) {
            return "slot:" + photo.physicalSlot;
        }
        if (photo.isMerge()) {
            return "merge:" + photo.path;
        }
        return "photo:" + photo.displayIndex + ":" + photo.name + ":" + photo.path;
    }
}
