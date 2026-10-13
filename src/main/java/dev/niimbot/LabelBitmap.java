package dev.niimbot;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * A 1-bit label image, rows packed MSB-first (bit 0x80 = leftmost pixel, 1 = black),
 * {@code stride = ceil(width / 8)} bytes per row.
 */
public final class LabelBitmap {
    public final int width;
    public final int height;
    public final int stride;
    final byte[] bits;

    public LabelBitmap(int width, int height, byte[] bits) {
        this.width = width;
        this.height = height;
        this.stride = (width + 7) >> 3;
        if (bits.length != stride * height) {
            throw new IllegalArgumentException("expected " + (stride * height) + " bytes, got " + bits.length);
        }
        this.bits = bits;
    }

    public boolean isBlack(int x, int y) {
        return (bits[y * stride + (x >> 3)] & (0x80 >> (x & 7))) != 0;
    }

    /**
     * Rasterises an image for a label: scales it to fill {@code size} (like the JS driver's
     * {@code drawImage(bmp, 0, dy, w, h)}), composites on white, then thresholds luminance
     * at 128 with no dithering. {@code size.offsetYPx} shifts the image vertically.
     */
    public static LabelBitmap fromImage(BufferedImage src, LabelSize size) {
        return fromImage(src, size.wPx, size.hPx, size.offsetYPx);
    }

    public static LabelBitmap fromImage(BufferedImage src, int w, int h, int offsetY) {
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, offsetY, w, h, null);
        } finally {
            g.dispose();
        }
        return threshold(canvas);
    }

    /** Thresholds an image that is already exactly label-sized (no scaling). */
    public static LabelBitmap threshold(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int stride = (w + 7) >> 3;
        byte[] buf = new byte[stride * h];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int argb = row[x];
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF, gg = (argb >> 8) & 0xFF, b = argb & 0xFF;
                // composite over white when the source has alpha
                if (a < 255 && img.getColorModel().hasAlpha()) {
                    r = (r * a + 255 * (255 - a)) / 255;
                    gg = (gg * a + 255 * (255 - a)) / 255;
                    b = (b * a + 255 * (255 - a)) / 255;
                }
                double lum = 0.299 * r + 0.587 * gg + 0.114 * b;
                if (lum < 128) buf[y * stride + (x >> 3)] |= (byte) (0x80 >> (x & 7));
            }
        }
        return new LabelBitmap(w, h, buf);
    }

    /** Rotates a source image 90° clockwise (for labels fed "standing", e.g. 15×50). */
    public static BufferedImage rotate90(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getHeight(), src.getWidth(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.translate(src.getHeight(), 0);
            g.rotate(Math.PI / 2);
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * Renders centred text (one or more lines separated by {@code \n}) into a
     * label-sized image — handy for quick labels without designing a PNG.
     */
    public static BufferedImage renderText(String text, int w, int h, String fontName, boolean bold) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.BLACK);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            String[] lines = text.split("\\R|\\\\n");
            int margin = Math.max(2, Math.min(w, h) / 20);
            int style = bold ? Font.BOLD : Font.PLAIN;
            // largest font size that fits all lines
            int size = h;
            FontMetrics fm;
            while (true) {
                g.setFont(new Font(fontName, style, size));
                fm = g.getFontMetrics();
                int maxW = 0;
                for (String l : lines) maxW = Math.max(maxW, fm.stringWidth(l));
                int totalH = fm.getHeight() * lines.length;
                if ((maxW <= w - 2 * margin && totalH <= h - 2 * margin) || size <= 6) break;
                size--;
            }
            int totalH = fm.getHeight() * lines.length;
            int y = (h - totalH) / 2 + fm.getAscent();
            for (String l : lines) {
                g.drawString(l, (w - fm.stringWidth(l)) / 2, y);
                y += fm.getHeight();
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * Calibration label: 1 px border, millimetre ruler on both long edges (tick every mm,
     * longer every 5 and 10 mm), top marker, centre crosshair, the size in text, and a
     * solid block plus a 1 px checkerboard to judge density. Drawn at the label's pixel size,
     * portrait (width = printhead axis).
     */
    public static BufferedImage testPattern(LabelSize size) {
        int w = size.wPx, h = size.hPx;
        double pxPerMm = size.dpi / 25.4;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.BLACK);
            g.drawRect(0, 0, w - 1, h - 1);                                  // border: shows clipping

            Font small = new Font("SansSerif", Font.PLAIN, Math.max(7, (int) Math.round(1.5 * pxPerMm)));
            Font big = new Font("SansSerif", Font.BOLD, Math.max(9, (int) Math.round(2.2 * pxPerMm)));
            int mmTotal = (int) Math.floor(h / pxPerMm);
            for (int mm = 1; mm < mmTotal; mm++) {                           // rulers on both edges
                int y = (int) Math.round(mm * pxPerMm);
                double l = mm % 10 == 0 ? 2.5 : mm % 5 == 0 ? 1.6 : 0.8;   // 1 / 5 / 10 mm ticks
                int len = (int) Math.round(l * pxPerMm);
                g.drawLine(0, y, len, y);
                g.drawLine(w - 1 - len, y, w - 1, y);
            }
            int cx = w / 2;
            int triY = (int) Math.round(1.2 * pxPerMm);                       // ▲ marks the top
            g.fillPolygon(new int[]{cx - 5, cx + 5, cx}, new int[]{triY + 6, triY + 6, triY}, 3);

            // text placed between the 10 mm ticks (baselines in mm)
            centred(g, big, fmtMm(w / pxPerMm) + "x" + fmtMm(h / pxPerMm), w, (int) Math.round(7 * pxPerMm));
            centred(g, small, w + "x" + h + "px", w, (int) Math.round(13.5 * pxPerMm));
            centred(g, small, size.dpi + " dpi", w, (int) Math.round(16.5 * pxPerMm));

            int cy = h / 2, arm = (int) Math.round(2 * pxPerMm);              // centre crosshair
            g.drawLine(cx - arm, cy, cx + arm, cy);
            g.drawLine(cx, cy - arm, cx, cy + arm);
            g.drawOval(cx - arm / 2, cy - arm / 2, arm, arm);

            int margin = (int) Math.round(3 * pxPerMm);
            int band = Math.max(4, (int) Math.round(Math.min(4, (h / pxPerMm - 30) / 3) * pxPerMm));
            int by = h - margin - 2 * band - 3;
            g.fillRect(margin, by, w - 2 * margin, band);                       // solid: density
            for (int yy = by + band + 3; yy < by + 2 * band + 3; yy++)          // checker: dot gain
                for (int xx = margin; xx < w - margin; xx++)
                    if (((xx + yy) & 1) == 0) img.setRGB(xx, yy, 0x000000);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void centred(Graphics2D g, Font f, String s, int w, int baseline) {
        g.setFont(f);
        g.drawString(s, (w - g.getFontMetrics().stringWidth(s)) / 2, baseline);
    }

    private static String fmtMm(double mm) {
        double r = Math.round(mm * 2) / 2.0;   // nearest 0.5 mm
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    /** Debug view of the bitmap as an image (black on white). */
    public BufferedImage toImage() {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                img.setRGB(x, y, isBlack(x, y) ? 0xFF000000 : 0xFFFFFFFF);
        return img;
    }

    // ── Row encoding ──────────────────────────────────────────────────────────

    /** Maximum identical rows grouped in one packet. */
    public static final int MAX_RUN = 200;

    /**
     * Encodes the image as row packets, byte-identical to the JS driver (and niim.blue):
     * identical consecutive rows are run-length grouped (max 200); blank rows use
     * {@code 0x84 [row_hi, row_lo, run]}; others use
     * {@code 0x85 [row_hi, row_lo, 0, total_lo, total_hi, run, ...stride bytes]}
     * where {@code total} is the row's black-pixel count ("total mode").
     */
    public List<Packet> encodeRows() {
        List<Packet> out = new ArrayList<>();
        int r = 0;
        while (r < height) {
            int off = r * stride;
            int run = 1;
            while (r + run < height && run < MAX_RUN && sameRow(off, (r + run) * stride)) run++;
            if (rowEmpty(off)) {
                out.add(new Packet(0x84, new byte[]{(byte) (r >> 8), (byte) r, (byte) run}));
            } else {
                int total = popcount(off);
                byte[] d = new byte[6 + stride];
                d[0] = (byte) (r >> 8);
                d[1] = (byte) r;
                d[2] = 0;
                d[3] = (byte) total;
                d[4] = (byte) (total >> 8);
                d[5] = (byte) run;
                System.arraycopy(bits, off, d, 6, stride);
                out.add(new Packet(0x85, d));
            }
            r += run;
        }
        return out;
    }

    private boolean rowEmpty(int off) {
        for (int b = 0; b < stride; b++) if (bits[off + b] != 0) return false;
        return true;
    }

    private boolean sameRow(int a, int b) {
        for (int i = 0; i < stride; i++) if (bits[a + i] != bits[b + i]) return false;
        return true;
    }

    private int popcount(int off) {
        int n = 0;
        for (int b = 0; b < stride; b++) n += Integer.bitCount(bits[off + b] & 0xFF);
        return n;
    }
}
