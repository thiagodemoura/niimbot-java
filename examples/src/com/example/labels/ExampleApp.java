package com.example.labels;

import dev.niimbot.LabelBitmap;
import dev.niimbot.LabelSize;
import dev.niimbot.NiimbotException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * Demo of integrating the driver into an application.
 *
 * <pre>
 *   ExampleApp preview            # no printer: writes preview-*.png
 *   ExampleApp print B1           # prints to the first printer whose name starts with "B1"
 * </pre>
 */
public final class ExampleApp {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "preview";

        if (mode.equals("preview")) {
            preview();
            return;
        }

        String printerName = args.length > 1 ? args[1] : "B1";
        var config = LabelPrintService.Config.defaults(printerName);

        try (var service = new LabelPrintService(config, msg -> System.out.println("[printer] " + msg))) {
            // Connect up front so the label size (from the detected model) is known.
            LabelSize size = service.connect().join();

            // 1) Two copies of one label (the image is uploaded once).
            BufferedImage coffee = ProductLabel.render(size, "Coffee Beans 1kg", "CB-1000", "$18.90");
            service.print(coffee, 2).join();

            // 2) Several different labels in one continuous job.
            List<BufferedImage> batch = List.of(
                    ProductLabel.render(size, "Green Tea", "GT-250", "$6.50"),
                    ProductLabel.render(size, "Oat Milk", "OM-1L", "$3.20"),
                    LabelBitmap.renderText("Thank you!\nSee you soon",size.wPx, size.hPx, "SansSerif", true));
            service.printBatch(batch).join();

            // 3) Non-blocking style, e.g. from a UI button handler.
            service.print(coffee, 1)
                    .thenRun(() -> System.out.println("done"))
                    .exceptionally(err -> {
                        System.err.println("failed: " + describe(err));
                        return null;
                    })
                    .join();   // join only so the demo doesn't exit before the job finishes

        } catch (CompletionException e) {
            System.err.println("Print failed: " + describe(e));
            System.exit(1);
        }
    }

    /** Renders labels to PNG exactly as the printer would see them (1-bit), no hardware needed. */
    private static void preview() throws Exception {
        LabelSize size = LabelSize.preset("T50x30_b1");   // B1, 384×240 px
        BufferedImage label = ProductLabel.render(size, "Coffee Beans 1kg", "CB-1000", "$18.90");
        LabelBitmap bmp = LabelBitmap.fromImage(label, size);
        File out = new File("preview-product.png");
        ImageIO.write(bmp.toImage(), "png", out);
        System.out.println("wrote " + out.getAbsolutePath() + " (" + size + ")");
    }

    private static String describe(Throwable e) {
        Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        if (cause instanceof NiimbotException.PrintNotConfirmed) {
            return "printer did not confirm every label — check paper / lid / label size ("
                    + cause.getMessage() + ")";
        }
        return cause.getMessage();
    }
}
