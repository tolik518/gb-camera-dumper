package fyi.r0.gbxcam;

import java.util.ArrayList;
import java.util.List;

/**
 * The RGB-merge compositing algorithm — the single source of truth for the
 * persisted id strings, the display labels (full / short / compact), clear-channel
 * compatibility, and the no-clear fallback.
 *
 * <p>The {@link #id()} strings are persisted (the default-algorithm preference,
 * per-merge overrides, and {@code manual-merges.json}), so they must not change.
 */
enum MergeAlgorithm {
    BASIC          ("basic",          "Basic RGB",                        "Basic",      "Basic", false),
    CLEAR_LUM      ("clear_lum",      "RGB + Clear Luminance",            "Clear Lum",  "Clear", true),
    NORM           ("norm",           "Normalized RGB",                   "Norm RGB",   "Norm",  false),
    NORM_CLEAR_LUM ("norm_clear_lum", "Normalized RGB + Clear Luminance", "Norm+Clear", "N+Clr", true),
    SAT_BOOST      ("sat_boost",      "Saturation Boosted",               "Sat Boost",  "Sat",   false),
    ADAPTIVE       ("adaptive",       "Experimental Adaptive ★",          "Adaptive ★", "Adapt", false);

    /** Used when none/invalid is requested for a set that has a clear channel. */
    static final MergeAlgorithm DEFAULT = NORM_CLEAR_LUM;

    private final String id;
    private final String label;
    private final String shortLabel;
    private final String compactLabel;
    private final boolean requiresClear;

    MergeAlgorithm(String id, String label, String shortLabel, String compactLabel,
            boolean requiresClear) {
        this.id = id;
        this.label = label;
        this.shortLabel = shortLabel;
        this.compactLabel = compactLabel;
        this.requiresClear = requiresClear;
    }

    String id() { return id; }
    String label() { return label; }
    String shortLabel() { return shortLabel; }
    String compactLabel() { return compactLabel; }

    /** The algorithm with this persisted id, or {@code null} if unknown/blank. */
    static MergeAlgorithm fromId(String id) {
        if (id == null || id.isEmpty()) return null;
        for (MergeAlgorithm a : values()) {
            if (a.id.equals(id)) return a;
        }
        return null;
    }

    /**
     * Resolves {@code requested} for a set with/without a clear channel: an unknown
     * or blank request falls back to the normalized default; a clear-only algorithm
     * requested for a clear-less set downgrades to its no-clear equivalent.
     */
    static MergeAlgorithm resolve(String requested, boolean hasClear) {
        MergeAlgorithm a = fromId(requested);
        if (a == null) return hasClear ? NORM_CLEAR_LUM : NORM;
        return a.resolve(hasClear);
    }

    MergeAlgorithm resolve(boolean hasClear) {
        if (hasClear || !requiresClear) return this;
        switch (this) {
            case CLEAR_LUM:      return BASIC;
            case NORM_CLEAR_LUM: return NORM;
            default:             return this;
        }
    }

    /** Algorithms valid for a set with/without a clear channel, in display order. */
    static MergeAlgorithm[] compatible(boolean hasClear) {
        if (hasClear) return values();
        List<MergeAlgorithm> list = new ArrayList<>();
        for (MergeAlgorithm a : values()) {
            if (!a.requiresClear) list.add(a);
        }
        return list.toArray(new MergeAlgorithm[0]);
    }

    static String[] compatibleIds(boolean hasClear) {
        return idsOf(compatible(hasClear));
    }

    static String[] compatibleLabels(boolean hasClear) {
        return labelsOf(compatible(hasClear));
    }

    static String[] allIds() {
        return idsOf(values());
    }

    static String[] allLabels() {
        return labelsOf(values());
    }

    private static String[] idsOf(MergeAlgorithm[] algos) {
        String[] ids = new String[algos.length];
        for (int i = 0; i < algos.length; i++) ids[i] = algos[i].id;
        return ids;
    }

    private static String[] labelsOf(MergeAlgorithm[] algos) {
        String[] labels = new String[algos.length];
        for (int i = 0; i < algos.length; i++) labels[i] = algos[i].label;
        return labels;
    }
}
