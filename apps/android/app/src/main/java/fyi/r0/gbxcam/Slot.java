package fyi.r0.gbxcam;

import java.util.Locale;

/** A physical Game Boy Camera album slot, zero-indexed on the wire. */
final class Slot {
    static final int COUNT = 30;

    private final int index;

    private Slot(int index) {
        this.index = index;
    }

    static Slot fromPhysicalIndex(int index) {
        return index >= 0 && index < COUNT ? new Slot(index) : null;
    }

    int index() {
        return index;
    }

    int displayNumber() {
        return index + 1;
    }

    String key() {
        return String.valueOf(index);
    }

    String twoDigitLabel() {
        return String.format(Locale.US, "%02d", displayNumber());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Slot && index == ((Slot) other).index;
    }

    @Override
    public int hashCode() {
        return index;
    }
}
