package fyi.r0.gbxcam;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered set of physical album slots naming the targets of an album operation
 * (delete / recover / reorder). Built by projecting a {@link GalleryState}
 * selection and serialized to the comma-separated form the FFI's
 * {@code parse_physical_slots} expects.
 *
 * <p>These are <em>operation parameters</em>, not gallery state, so they live here
 * rather than on {@code GalleryState} (the read model).
 */
final class SlotSet {
    private final List<Slot> slots;

    private SlotSet(List<Slot> slots) {
        this.slots = slots;
    }

    /** Selected album photos matching {@code deleted}, in gallery order. */
    static SlotSet selected(GalleryState gallery, boolean deleted) {
        List<Slot> slots = new ArrayList<>();
        for (GalleryPhoto photo : gallery.photos) {
            if (gallery.isSelected(photo) && photo.deleted == deleted && photo.isAlbumBacked()) {
                slots.add(photo.slot);
            }
        }
        return new SlotSet(slots);
    }

    /** All active (non-deleted, album-backed) photos, in gallery order. */
    static SlotSet active(GalleryState gallery) {
        List<Slot> slots = new ArrayList<>();
        for (GalleryPhoto photo : gallery.photos) {
            if (photo.isActiveAlbumPhoto()) {
                slots.add(photo.slot);
            }
        }
        return new SlotSet(slots);
    }

    /** Active photos with selected ones first, preserving order within each group. */
    static SlotSet selectedActiveFirst(GalleryState gallery) {
        List<Slot> slots = new ArrayList<>();
        appendActive(slots, gallery, true);
        appendActive(slots, gallery, false);
        return new SlotSet(slots);
    }

    private static void appendActive(List<Slot> slots, GalleryState gallery, boolean selected) {
        for (GalleryPhoto photo : gallery.photos) {
            if (photo.isActiveAlbumPhoto() && gallery.isSelected(photo) == selected) {
                slots.add(photo.slot);
            }
        }
    }

    boolean isEmpty() {
        return slots.isEmpty();
    }

    /** Comma-separated slot numbers, e.g. {@code "3,7,12"} — the FFI wire form. */
    String toCsv() {
        StringBuilder csv = new StringBuilder();
        for (Slot slot : slots) {
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(slot.index());
        }
        return csv.toString();
    }
}
