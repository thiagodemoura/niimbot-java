package dev.niimbot;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A label size in printer pixels.
 *
 * <p>{@code wPx} is along the printhead axis and should be the printhead's usable width
 * (the printer silently drops columns past it); {@code hPx} is along the feed axis.
 * {@code offsetYPx} shifts the image down (&gt;0) or up (&lt;0) to compensate for paper
 * registration — measured per model upstream.
 *
 * <p>The presets are ported from niimbot-web-bluetooth's {@code registry.json}.
 * For other stock, build one with {@link #custom}.
 */
public final class LabelSize {
    public final String name;
    public final String description;
    public final int wPx;
    public final int hPx;
    public final int dpi;
    public final int offsetYPx;

    public LabelSize(String name, String description, int wPx, int hPx, int dpi, int offsetYPx) {
        if (wPx <= 0 || hPx <= 0) throw new IllegalArgumentException("size must be positive");
        if (hPx > 0xFFFF || wPx > 0xFFFF) throw new IllegalArgumentException("size too large");
        this.name = name;
        this.description = description;
        this.wPx = wPx;
        this.hPx = hPx;
        this.dpi = dpi;
        this.offsetYPx = offsetYPx;
    }

    /** Row stride in bytes ({@code ceil(wPx / 8)}). */
    public int stride() {
        return (wPx + 7) >> 3;
    }

    /**
     * Custom size from millimetres. {@code printheadPx} caps the width (e.g. 384 on a B1,
     * 576 on a B1 Pro); pass 0 to use the label width as-is.
     */
    public static LabelSize custom(double wMm, double hMm, int dpi, int printheadPx) {
        double pxPerMm = dpi / 25.4;
        int w = (int) Math.round(wMm * pxPerMm);
        if (printheadPx > 0) w = Math.min(w, printheadPx);
        int h = (int) Math.round(hMm * pxPerMm);
        return new LabelSize(String.format(Locale.ROOT, "%gx%g@%d", wMm, hMm, dpi),
                String.format(Locale.ROOT, "%g × %g mm (custom)", wMm, hMm), w, h, dpi, 0);
    }

    private static final Map<String, LabelSize> PRESETS = new LinkedHashMap<>();

    private static void add(String n, String d, int w, int h, int dpi, int off) {
        PRESETS.put(n.toUpperCase(Locale.ROOT), new LabelSize(n, d, w, h, dpi, off));
    }

    static {
        // 300 dpi
        add("T50x30",       "50 × 30 mm (B1 Pro)",                 576, 354, 300, 0);
        add("T50x30_m2h",   "50 × 30 mm (M2-H, ribbon margin)",    567, 354, 300, 0);
        add("T50x30_b2pro", "50 × 30 mm (B2 Pro)",                 576, 354, 300, 0);
        add("T15x30",       "15 × 30 mm (D11_H)",                  144, 354, 300, 0);
        add("T12x22",       "12 × 22 mm (D11_H)",                  142, 260, 300, -6);
        add("T40x60",       "40 × 60 mm",                          472, 709, 300, 0);
        add("T30x45",       "30 × 45 mm (cable flag)",             354, 531, 300, 0);
        add("T25x38",       "25 × 38 mm (cable flag)",             295, 449, 300, 0);
        // 203 dpi
        add("T50x30_b1",    "50 × 30 mm (B1)",                     384, 240, 203, 4);
        add("T30x45_b1",    "30 × 45 mm (cable flag, B1)",         240, 360, 203, 0);
        add("T25x38_b1",    "25 × 38 mm (cable flag, B1)",         200, 304, 203, 0);
        add("T15x50",       "15 × 50 mm (D110)",                    96, 400, 203, -2);
        // T12*40-160: 12 × 40 mm, 160-label roll, fed standing. 12 mm × 8 px/mm = 96 = the D110 head.
        // offsetY -2 copies the D110's measured registration on T15x50 (same printer, same
        // paper path) — a starting point; calibrate with `print --test`.
        add("T12x40",       "12 × 40 mm (D110, T12*40-160)",        96, 320, 203, -2);
        add("T14x50",       "14 × 50 mm (N1)",                      96, 400, 203, -1);
        add("T15x50_d110m", "15 × 50 mm (D110_M, never printed)",   96, 400, 203, 0);
        add("T12x40_d110m", "12 × 40 mm (D110_M, never printed)",   96, 320, 203, 0);
    }

    public static LabelSize preset(String name) {
        LabelSize s = PRESETS.get(name.toUpperCase(Locale.ROOT));
        if (s == null) throw new IllegalArgumentException("Unknown size '" + name + "'. Known: " + PRESETS.keySet());
        return s;
    }

    /** The upstream-validated default size for a model. */
    public static LabelSize defaultFor(PrinterModel m) {
        switch (m) {
            case B1: case B1_SE: return preset("T50x30_b1");
            case B1_PRO: return preset("T50x30");
            case B2_PRO: return preset("T50x30_b2pro");
            case M2_H: return preset("T50x30_m2h");
            case D11_H: return preset("T15x30");
            case D110: return preset("T15x50");
            case D110_M: return preset("T15x50_d110m");
            case N1: return preset("T14x50");
            default: throw new IllegalArgumentException("no default size for " + m);
        }
    }

    public static Map<String, LabelSize> presets() {
        return java.util.Collections.unmodifiableMap(PRESETS);
    }

    @Override
    public String toString() {
        return name + " — " + description + " [" + wPx + "×" + hPx + " px, " + dpi + " dpi"
                + (offsetYPx != 0 ? ", offsetY " + offsetYPx : "") + "]";
    }
}
