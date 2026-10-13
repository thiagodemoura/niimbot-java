package dev.niimbot.cli;

import dev.niimbot.LabelBitmap;
import dev.niimbot.LabelSize;
import dev.niimbot.NiimbotException;
import dev.niimbot.NiimbotPrinter;
import dev.niimbot.Packet;
import dev.niimbot.PrinterModel;
import dev.niimbot.ble.SimpleBleTransport;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command line:
 * <pre>
 *   niimbot scan [--seconds 5]
 *   niimbot sizes
 *   niimbot info  [--printer B1-xxxx] [--debug]
 *   niimbot print label.png [more.png …] [options]
 *   niimbot print --text "Hello\nWorld" [options]
 *   niimbot print --test [options]       calibration label (border, mm rulers, density bands)
 *
 * options:
 *   --printer NAME|ADDR   BLE name prefix or address (default: any known Niimbot prefix)
 *   --model   b1|b1pro|b2pro|m2h|d11h|d110|d110m|n1|b1se   (default: detected)
 *   --size    T50x30_b1 | … (see "sizes")                    (default: per model)
 *   --copies N            identical copies (one upload)
 *   --density 1..5        darkness (default 3)
 *   --rotate              rotate images 90° clockwise before fitting
 *   --font NAME --bold    font for --text
 *   --preview out.png     write the 1-bit bitmap and exit (no printer needed; needs --size)
 *   --debug               packet log
 * </pre>
 */
public final class NiimbotCli {

    public static void main(String[] argv) {
        try {
            System.exit(run(argv));
        } catch (NiimbotException.PrintNotConfirmed e) {
            System.err.println("✗ " + e.getMessage());
            System.exit(3);
        } catch (Exception e) {
            System.err.println("✗ " + e.getMessage());
            if (System.getenv("NIIMBOT_TRACE") != null) e.printStackTrace();
            System.exit(1);
        }
    }

    static int run(String[] argv) throws Exception {
        if (argv.length == 0 || argv[0].equals("-h") || argv[0].equals("--help")) {
            usage();
            return argv.length == 0 ? 2 : 0;
        }
        Args a = Args.parse(argv);
        switch (a.command) {
            case "scan": return scan(a);
            case "sizes": return sizes();
            case "info": return info(a);
            case "print": return print(a);
            default:
                System.err.println("unknown command: " + a.command);
                usage();
                return 2;
        }
    }

    private static int sizes() {
        for (Map.Entry<String, LabelSize> e : LabelSize.presets().entrySet()) System.out.println(e.getValue());
        System.out.println();
        for (PrinterModel m : PrinterModel.values())
            System.out.println(m + "  default size: " + LabelSize.defaultFor(m).name);
        return 0;
    }

    private static int scan(Args a) throws IOException {
        int secs = Integer.parseInt(a.opt("seconds", "5"));
        System.out.println("Scanning " + secs + " s…");
        for (SimpleBleTransport.Found f : SimpleBleTransport.scan(secs * 1000)) {
            boolean niim = knownPrefixes().stream().anyMatch(p -> f.name.toUpperCase().startsWith(p));
            System.out.println((niim ? "* " : "  ") + f);
        }
        System.out.println("(* = name matches a known Niimbot prefix)");
        return 0;
    }

    private static int info(Args a) throws IOException {
        try (NiimbotPrinter p = open(a)) {
            System.out.println("Printer: " + p.info());
            System.out.println("Model:   " + p.model());
            Packet hb = p.heartbeat();
            System.out.println("Heartbeat: " + (hb == null ? "no answer" : hb));
            Packet rf = p.rfidInfo();
            System.out.println("RFID:      " + (rf == null ? "no answer (normal on many models)" : rf));
        }
        return 0;
    }

    private static int print(Args a) throws Exception {
        List<BufferedImage> images = new ArrayList<>();
        String text = a.opt("text", null);
        LabelSize forcedSize = a.has("size") ? LabelSize.preset(a.opt("size", null)) : null;

        if (a.has("preview")) {
            LabelSize size = forcedSize != null ? forcedSize
                    : a.has("model") ? LabelSize.defaultFor(PrinterModel.parse(a.opt("model", null))) : null;
            if (size == null) throw new IllegalArgumentException("--preview needs --size or --model");
            BufferedImage src = a.has("test") ? LabelBitmap.testPattern(size)
                    : text != null ? textImage(a, text, size) : load(a, a.positional.get(0));
            LabelBitmap bmp = LabelBitmap.fromImage(src, size);
            File out = new File(a.opt("preview", "preview.png"));
            ImageIO.write(bmp.toImage(), "png", out);
            System.out.println("Wrote " + out + " (" + bmp.width + "×" + bmp.height + ", " + bmp.encodeRows().size() + " row packets)");
            return 0;
        }

        if (text == null && a.positional.isEmpty() && !a.has("test")) throw new IllegalArgumentException("print needs image files, --text or --test");

        List<BufferedImage> files = new ArrayList<>();
        for (String f : a.positional) files.add(load(a, f));   // fail on a bad path before touching Bluetooth

        try (NiimbotPrinter p = open(a)) {
            LabelSize size = forcedSize != null ? forcedSize : LabelSize.defaultFor(p.model());
            System.out.println("Printing on " + p.model().label + " — " + size);
            if (a.has("test")) images.add(LabelBitmap.testPattern(size));
            if (text != null) images.add(textImage(a, text, size));
            images.addAll(files);

            NiimbotPrinter.PrintOptions o = NiimbotPrinter.PrintOptions.defaults()
                    .copies(Integer.parseInt(a.opt("copies", "1")))
                    .onProgress(s -> System.out.println("  " + s));
            if (a.has("density")) o.density(Integer.parseInt(a.opt("density", null)));

            long t0 = System.currentTimeMillis();
            if (images.size() == 1) p.print(images.get(0), size, o);
            else p.printBatchImages(images, size, o);
            System.out.println("✓ printed in " + (System.currentTimeMillis() - t0) + " ms");
        }
        return 0;
    }

    private static BufferedImage textImage(Args a, String text, LabelSize size) {
        return LabelBitmap.renderText(text, size.wPx, size.hPx, a.opt("font", "SansSerif"), a.has("bold"));
    }

    private static BufferedImage load(Args a, String path) throws IOException {
        File file = new File(path);
        if (!file.isFile()) throw new IOException("image not found: " + path);
        BufferedImage img = ImageIO.read(file);
        if (img == null) throw new IOException("cannot read image " + path);
        return a.has("rotate") ? LabelBitmap.rotate90(img) : img;
    }

    private static Set<String> knownPrefixes() {
        Set<String> s = new LinkedHashSet<>();
        for (PrinterModel m : PrinterModel.values()) s.addAll(m.namePrefixes);
        return s;
    }

    private static NiimbotPrinter open(Args a) throws IOException {
        PrinterModel model = a.has("model") ? PrinterModel.parse(a.opt("model", null)) : null;
        List<String> targets;
        if (a.has("printer")) targets = List.of(a.opt("printer", null));
        else if (model != null) targets = model.namePrefixes;
        else targets = new ArrayList<>(knownPrefixes());
        int timeout = Integer.parseInt(a.opt("scan-timeout", "10")) * 1000;
        System.out.println("Looking for " + targets + "…");
        SimpleBleTransport t = SimpleBleTransport.connectFirst(targets, timeout);
        System.out.println("Connected to " + t.deviceName());
        NiimbotPrinter p = new NiimbotPrinter(t, model).setDebug(a.has("debug"));
        try {
            p.connect();
            if (p.model() == null) throw new NiimbotException("Unknown printer " + p.info() + " — pass --model");
        } catch (IOException | RuntimeException e) {
            p.close();
            throw e;
        }
        return p;
    }

    private static void usage() {
        System.err.println(String.join("\n",
                "Niimbot label printer (BLE)",
                "  niimbot scan [--seconds 5]",
                "  niimbot sizes",
                "  niimbot info  [--printer NAME] [--debug]",
                "  niimbot print FILE.png [FILE2.png …] [options]",
                "  niimbot print --text \"Line 1\\nLine 2\" [options]",
                "  niimbot print --test [options]   (calibration label)",
                "options: --printer NAME|ADDR  --model b1|b1pro|b2pro|m2h|d11h|d110|d110m|n1|b1se",
                "         --size NAME  --copies N  --density 1-5  --rotate  --font NAME --bold",
                "         --preview out.png  --scan-timeout SECONDS  --debug"));
    }

    /** Minimal argument parser: first token = command, --flag [value], rest positional. */
    static final class Args {
        String command;
        final List<String> positional = new ArrayList<>();
        final java.util.Map<String, String> opts = new java.util.HashMap<>();
        private static final Set<String> FLAGS = Set.of("rotate", "bold", "debug", "test");

        static Args parse(String[] argv) {
            Args a = new Args();
            a.command = argv[0];
            for (int i = 1; i < argv.length; i++) {
                String s = argv[i];
                if (s.startsWith("--")) {
                    String k = s.substring(2);
                    if (FLAGS.contains(k)) a.opts.put(k, "true");
                    else if (i + 1 < argv.length) a.opts.put(k, argv[++i]);
                    else throw new IllegalArgumentException("missing value for " + s);
                } else {
                    a.positional.add(s);
                }
            }
            return a;
        }

        boolean has(String k) { return opts.containsKey(k); }

        String opt(String k, String def) { return opts.getOrDefault(k, def); }
    }
}
