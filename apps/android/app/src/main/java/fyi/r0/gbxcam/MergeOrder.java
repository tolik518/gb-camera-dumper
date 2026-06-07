package fyi.r0.gbxcam;

/**
 * The RGB-merge channel order — a permutation of the colour channels with an
 * optional clear channel: {@code "RGB".."BGR"} for 3-source merges,
 * {@code "CRGB".."BGRC"} for 4-source. The single source of truth for the valid
 * permutation tables, the per-size defaults, and validation.
 *
 * <p>The order strings are persisted (the {@code rgb3-order}/{@code rgb4-order}
 * preferences and {@code mergedKind} in {@code manual-merges.json}), so they must
 * not change. Phase D gives this an instance/value form once {@code MergeInfo}
 * holds a {@code MergeOrder}; for now it is the static order authority.
 */
final class MergeOrder {
    /** Valid 3-channel orders, in display order. */
    static final String[] ORDERS_3 = {
            "RGB", "RBG", "GRB", "GBR", "BRG", "BGR"
    };
    /** Valid 4-channel (clear) orders, in display order. */
    static final String[] ORDERS_4 = {
            "CRGB", "CRBG", "CGBR", "CGRB", "CBRG", "CBGR",
            "RCGB", "RCBG", "RGBC", "RGCB", "RBGC", "RBCG",
            "GCRB", "GCBR", "GRCB", "GRBC", "GBCR", "GBRC",
            "BCRG", "BCGR", "BRCG", "BRGC", "BGCR", "BGRC"
    };
    static final String DEFAULT_3 = "RGB";
    static final String DEFAULT_4 = "CRGB";

    private MergeOrder() {}

    /** Valid orders for a merge of {@code sourceCount} sources (4 → clear, else 3). */
    static String[] optionsFor(int sourceCount) {
        return sourceCount == 4 ? ORDERS_4 : ORDERS_3;
    }

    /** Default order for a merge of {@code sourceCount} sources. */
    static String defaultFor(int sourceCount) {
        return sourceCount == 4 ? DEFAULT_4 : DEFAULT_3;
    }

    /** {@code order} if valid for {@code sourceCount} sources, else {@link #defaultFor}. */
    static String valid(String order, int sourceCount) {
        if (order != null) {
            for (String option : optionsFor(sourceCount)) {
                if (option.equals(order)) return order;
            }
        }
        return defaultFor(sourceCount);
    }
}
