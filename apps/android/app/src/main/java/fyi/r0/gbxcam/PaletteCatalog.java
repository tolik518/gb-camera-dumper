package fyi.r0.gbxcam;

/**
 * Native palette labels/colors, loaded once, with bounds-checked lookups and
 * fallbacks. Shared by the screen (swatches/menu) and the controller (rendering).
 */
final class PaletteCatalog {
    private static final String DEFAULT_LABEL = "Monochrome - Grayscale";
    private static final int[] FALLBACK_ROW = {
            0xFFFFFFFF, 0xFFB0B0B0, 0xFF686868, 0xFF000000
    };

    final String[] labels;
    final int[][] colors;

    private PaletteCatalog(String[] labels, int[][] colors) {
        this.labels = labels;
        this.colors = colors;
    }

    /** Loads the catalog from the native palette tables. */
    static PaletteCatalog load() {
        String[] labels = loadLabels();
        return new PaletteCatalog(labels, loadColors(labels.length));
    }

    /** Clamped label lookup; never throws. */
    String labelFor(int index) {
        if (labels == null || labels.length == 0) {
            return DEFAULT_LABEL;
        }
        return labels[Math.max(0, Math.min(index, labels.length - 1))];
    }

    /** Colors for {@code index}, or a grayscale fallback row when out of range. */
    int[] colorsFor(int index) {
        if (colors != null && index >= 0 && index < colors.length
                && colors[index] != null && colors[index].length > 0) {
            return colors[index];
        }
        return fallbackRow();
    }

    private static String[] loadLabels() {
        String labels = NativeGbcam.paletteLabels();
        if (labels == null || labels.isEmpty()) {
            return new String[] { DEFAULT_LABEL };
        }
        return labels.split("\\n");
    }

    private static int[][] loadColors(int expectedCount) {
        String colors = NativeGbcam.paletteColors();
        if (colors == null || colors.isEmpty()) {
            return fallbackColors(expectedCount);
        }
        String[] rows = colors.split("\\n");
        int[][] parsed = new int[expectedCount][];
        for (int i = 0; i < expectedCount; i++) {
            parsed[i] = i < rows.length ? parseRow(rows[i]) : fallbackRow();
        }
        return parsed;
    }

    private static int[] parseRow(String row) {
        String[] parts = row.split(",");
        int[] colors = fallbackRow();
        for (int i = 0; i < Math.min(parts.length, colors.length); i++) {
            try {
                colors[i] = (int) (0xFF000000L | Long.parseLong(parts[i], 16));
            } catch (NumberFormatException ignored) {
            }
        }
        return colors;
    }

    private static int[][] fallbackColors(int count) {
        int[][] colors = new int[Math.max(1, count)][];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = fallbackRow();
        }
        return colors;
    }

    private static int[] fallbackRow() {
        return FALLBACK_ROW.clone();
    }
}
