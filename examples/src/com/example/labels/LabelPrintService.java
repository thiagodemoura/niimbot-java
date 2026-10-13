package com.example.labels;

import dev.niimbot.LabelSize;
import dev.niimbot.NiimbotException;
import dev.niimbot.NiimbotPrinter;
import dev.niimbot.PrinterModel;
import dev.niimbot.ble.SimpleBleTransport;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Application-facing wrapper around {@link NiimbotPrinter}.
 *
 * <ul>
 *   <li>One long-lived BLE connection, opened lazily on the first job and reused.</li>
 *   <li>Jobs are serialised on a single worker thread — the printer handles one job at a
 *       time, and {@code NiimbotPrinter} is not meant to be driven from several threads.</li>
 *   <li>If the link drops, the next job reconnects transparently.</li>
 *   <li>Failed jobs are <b>not</b> retried automatically: a job that failed mid-print may
 *       already have produced labels, so the caller decides whether to print again.</li>
 * </ul>
 *
 * Callers get a {@link CompletableFuture}, so it fits UI code (Swing/JavaFX), web
 * controllers and plain blocking code ({@code .join()}) alike.
 */
public final class LabelPrintService implements AutoCloseable {

    /** Connection settings. */
    public record Config(
            String printerName,     // BLE name prefix or address, e.g. "B1" or "B1-H1234"
            int scanTimeoutMs,
            PrinterModel model,     // null = auto-detect on connect (recommended)
            String labelSize,       // preset name, e.g. "T50x30_b1"; null = model default
            int density) {          // 1-5

        public static Config defaults(String printerName) {
            return new Config(printerName, 10_000, null, null, 3);
        }
    }

    private final Config config;
    private final Consumer<String> log;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "niimbot-print");
        t.setDaemon(true);
        return t;
    });

    // Only touched from the worker thread.
    private NiimbotPrinter printer;
    private SimpleBleTransport transport;
    private LabelSize size;

    public LabelPrintService(Config config, Consumer<String> log) {
        this.config = config;
        this.log = log == null ? s -> { } : log;
    }

    /** Connects now (optional — jobs connect on demand). Completes with the label size in use. */
    public CompletableFuture<LabelSize> connect() {
        return submit(() -> {
            ensureConnected();
            return size;
        });
    }

    /** Prints {@code copies} identical labels from one image. */
    public CompletableFuture<Void> print(BufferedImage image, int copies) {
        return submit(() -> {
            ensureConnected();
            run(p -> p.print(image, size, options().copies(copies)));
            return null;
        });
    }

    /** Prints different labels back to back as one continuous job. */
    public CompletableFuture<Void> printBatch(List<BufferedImage> images) {
        return submit(() -> {
            ensureConnected();
            run(p -> p.printBatchImages(images, size, options()));
            return null;
        });
    }

    /**
     * The label size jobs will use. Known before connecting only when {@code labelSize} is
     * configured; otherwise it comes from the detected model, so call {@link #connect()} first.
     */
    public CompletableFuture<LabelSize> labelSize() {
        return submit(() -> {
            if (size == null) ensureConnected();
            return size;
        });
    }

    @Override
    public void close() {
        worker.submit(this::disconnect);
        worker.shutdown();
    }

    // ── internals (worker thread) ─────────────────────────────────────────────

    private interface Job<T> { T call() throws IOException; }
    private interface PrinterAction { void accept(NiimbotPrinter p) throws IOException; }

    private <T> CompletableFuture<T> submit(Job<T> job) {
        CompletableFuture<T> f = new CompletableFuture<>();
        worker.execute(() -> {
            try {
                f.complete(job.call());
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    private NiimbotPrinter.PrintOptions options() {
        return NiimbotPrinter.PrintOptions.defaults()
                .density(config.density())
                .onProgress(log);
    }

    private void run(PrinterAction action) throws IOException {
        try {
            action.accept(printer);
        } catch (NiimbotException.PrintNotConfirmed e) {
            // The link is fine; the printer just did not confirm every page (paper out,
            // lid open, wrong label size...). Keep the connection, report to the caller.
            throw e;
        } catch (IOException | RuntimeException e) {
            // Anything else usually means the link is gone — reconnect on the next job.
            disconnect();
            throw e;
        }
    }

    private void ensureConnected() throws IOException {
        if (printer != null && transport.isConnected()) return;
        disconnect();

        log.accept("connecting to " + config.printerName() + "...");
        transport = SimpleBleTransport.connect(config.printerName(), config.scanTimeoutMs());
        printer = new NiimbotPrinter(transport, config.model()).setLogger(log);
        try {
            NiimbotPrinter.PrinterInfo info = printer.connect();
            if (printer.model() == null) {
                throw new NiimbotException("Printer not identified (" + info + "); set Config.model");
            }
            size = config.labelSize() != null
                    ? LabelSize.preset(config.labelSize())
                    : LabelSize.defaultFor(printer.model());
            log.accept("connected: " + info + ", label " + size);
        } catch (IOException | RuntimeException e) {
            disconnect();
            throw e;
        }
    }

    private void disconnect() {
        if (printer != null) {
            try { printer.close(); } catch (RuntimeException ignored) { }
        }
        printer = null;
        transport = null;
    }
}
