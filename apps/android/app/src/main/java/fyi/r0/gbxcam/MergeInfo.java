package fyi.r0.gbxcam;

/**
 * The RGB-merge descriptor for a merged {@link GalleryPhoto}: the channel order
 * and algorithm that produced it, the source span it covers, and whether the user
 * created it manually. A photo holds one of these only when it is a merge (null
 * otherwise) — this replaces the former {@code mergedRgb} discriminator plus five
 * parallel fields.
 *
 * <p>Types are kept as the raw persisted forms ({@code kind}/{@code algorithm}
 * strings) so the gallery JSON and {@code manual-merges.json} round-trip
 * byte-identically; typing them as {@code MergeOrder}/{@code MergeAlgorithm} is a
 * later step.
 */
final class MergeInfo {
    /** Channel order, e.g. {@code "CRGB"} or {@code "BGR"} (was {@code mergedKind}). */
    final String kind;
    final int sourceCount;
    final int sourceStartDisplayIndex;
    /** {@link MergeAlgorithm} id (was {@code mergedAlgorithm}). */
    final String algorithm;
    final boolean manual;

    MergeInfo(String kind, int sourceCount, int sourceStartDisplayIndex,
            String algorithm, boolean manual) {
        this.kind = kind;
        this.sourceCount = sourceCount;
        this.sourceStartDisplayIndex = sourceStartDisplayIndex;
        this.algorithm = algorithm;
        this.manual = manual;
    }

    /** Stable key {@code "kind:start:count"} used for per-merge algorithm overrides. */
    String identity() {
        return identity(kind, sourceStartDisplayIndex, sourceCount);
    }

    static String identity(String kind, int sourceStartDisplayIndex, int sourceCount) {
        return (kind == null ? "" : kind) + ":" + sourceStartDisplayIndex + ":" + sourceCount;
    }
}
