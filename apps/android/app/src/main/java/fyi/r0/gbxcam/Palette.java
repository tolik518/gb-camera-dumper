package fyi.r0.gbxcam;

/**
 * The selected colour palette: its catalog index and display name. A value object
 * — the 4 colours are looked up via {@link PaletteCatalog} rather than carried
 * here, mirroring the core {@code PaletteId} (a thin id with derived lookups).
 *
 * <p>The {@code index}/{@code name} pair is persisted in the gallery JSON
 * (Rust bakes the palette it rendered with), so both are preserved as-is.
 */
final class Palette {
    final int index;
    final String name;

    Palette(int index, String name) {
        this.index = index;
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Palette)) return false;
        Palette other = (Palette) o;
        return index == other.index
                && (name == null ? other.name == null : name.equals(other.name));
    }

    @Override
    public int hashCode() {
        return 31 * index + (name == null ? 0 : name.hashCode());
    }

    @Override
    public String toString() {
        return name;
    }
}
