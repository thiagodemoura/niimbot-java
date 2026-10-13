package com.example.labels;

import dev.niimbot.LabelSize;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Example of drawing your own label layout with Java2D.
 *
 * Draw at the label's exact pixel size ({@code size.wPx × size.hPx}) in pure black on
 * white with antialiasing off — the driver thresholds at 50% grey, so grey edges and
 * antialiased text come out ragged.
 */
public final class ProductLabel {

    private ProductLabel() { }

    public static BufferedImage render(LabelSize size, String name, String sku, String price) {
        int w = size.wPx, h = size.hPx;
        double pxPerMm = size.dpi / 25.4;
        int margin = (int) Math.round(1.5 * pxPerMm);

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.BLACK);

            // Top: product name, shrunk until it fits the width.
            int nameH = h * 35 / 100;
            Font nameFont = fit(g, name, new Font("SansSerif", Font.BOLD, nameH), w - 2 * margin);
            g.setFont(nameFont);
            g.drawString(name, margin, margin + g.getFontMetrics().getAscent());

            // Divider.
            int lineY = margin + nameH;
            g.setStroke(new BasicStroke(Math.max(2, (int) Math.round(0.3 * pxPerMm))));
            g.drawLine(margin, lineY, w - margin, lineY);

            // Bottom-left: SKU. Bottom-right: price, large.
            int bottom = h - margin;
            g.setFont(new Font("SansSerif", Font.PLAIN, Math.max(10, h / 8)));
            String skuText = "SKU " + sku;
            g.drawString(skuText, margin, bottom);
            int skuW = g.getFontMetrics().stringWidth(skuText);

            int priceMaxW = w - 3 * margin - skuW;   // space to the right of the SKU
            Font priceFont = fit(g, price, new Font("SansSerif", Font.BOLD, h * 40 / 100), priceMaxW);
            g.setFont(priceFont);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(price, w - margin - fm.stringWidth(price), bottom);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static Font fit(Graphics2D g, String text, Font font, int maxWidth) {
        Font f = font;
        while (f.getSize() > 8 && g.getFontMetrics(f).stringWidth(text) > maxWidth) {
            f = f.deriveFont((float) f.getSize() - 1);
        }
        return f;
    }
}
