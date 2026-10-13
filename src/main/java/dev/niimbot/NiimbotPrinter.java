package dev.niimbot;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Niimbot label-printer driver (protocol V4 framing, "v4" and "b1" print tasks).
 *
 * <p>A Java port of niimbot-web-bluetooth by iscarelli (MIT):
 * https://github.com/iscarelli/niimbot-web-bluetooth — same command sequences, flow
 * control, run-length row encoding, frame bundling and "confirm or throw" semantics.
 *
 * <pre>{@code
 * try (NiimbotPrinter p = new NiimbotPrinter(SimpleBleTransport.connect("B1", 10_000))) {
 *     p.connect();                                       // identifies the model
 *     LabelSize size = LabelSize.preset("T50x30_b1");
 *     p.print(ImageIO.read(new File("label.png")), size, PrintOptions.defaults());
 * }
 * }</pre>
 *
 * <p>Print methods return only once the printer confirmed the job (every page
 * acknowledged and the printed-page counter reached the total). Otherwise they send
 * PrintEnd — so the paper still feeds out — and throw {@link NiimbotException.PrintNotConfirmed}.
 *
 * <p>Not thread-safe: run one job at a time per instance.
 */
public final class NiimbotPrinter implements AutoCloseable {

    // ── Opcodes ───────────────────────────────────────────────────────────────
    static final int CMD_CONNECT = 0xC1;
    static final int CMD_STATUS_DATA = 0xA5, RSP_STATUS_DATA = 0xB5;
    static final int CMD_INFO = 0x40, RSP_MODEL_ID = 0x48;
    static final int CMD_HEARTBEAT = 0xDC, RSP_HEARTBEAT = 0xD9;
    static final int CMD_RFID = 0x1A, RSP_RFID = 0x1B;
    static final int CMD_DENSITY = 0x21, RSP_DENSITY = 0x31;
    static final int CMD_LABEL_TYPE = 0x23, RSP_LABEL_TYPE = 0x33;
    static final int CMD_PRINT_START = 0x01, RSP_PRINT_START = 0x02;
    static final int CMD_PAGE_START = 0x03, RSP_PAGE_START = 0x04;
    static final int CMD_PRINT_STATUS = 0xA3, RSP_PRINT_STATUS = 0xB3;
    static final int CMD_PAGE_SIZE = 0x13, RSP_PAGE_SIZE = 0x14;
    static final int CMD_PAGE_END = 0xE3, RSP_PAGE_END = 0xE4;
    static final int CMD_PRINT_END = 0xF3, RSP_PRINT_END = 0xF4;
    static final int RSP_ROWS_RECEIVED = 0xD3;

    /** Raw connection packet (note the 0x03 prefix), as niim.blue sends it. */
    static final byte[] CONNECT_PACKET = {0x03, 0x55, 0x55, (byte) 0xC1, 0x01, 0x01, (byte) 0xC1, (byte) 0xAA, (byte) 0xAA};

    public enum WriteMode {
        /** Unacknowledged writes, no gap. */
        FAST,
        /** Unacknowledged writes + {@link #setPaceMs pace} gap (rows are dropped on a burst otherwise). */
        PACED,
        /** Acknowledged writes (slow, ordered). */
        ACKED
    }

    /** What the printer reported about itself on {@link #connect()}. */
    public static final class PrinterInfo {
        public final Integer modelId;
        public final Integer protocolVersion;
        public final String deviceName;
        /** Registry entry for {@link #modelId}, or null if the id is unknown. */
        public final PrinterModel model;

        PrinterInfo(Integer modelId, Integer protocolVersion, String deviceName) {
            this.modelId = modelId;
            this.protocolVersion = protocolVersion;
            this.deviceName = deviceName;
            this.model = modelId == null ? null : PrinterModel.byId(modelId);
        }

        @Override
        public String toString() {
            String label = model != null ? model.label : (modelId != null ? "unknown (id " + modelId + ")" : "unknown");
            return label + " [id=" + modelId + ", protocol=" + protocolVersion + ", name=" + deviceName + "]";
        }
    }

    /** Reply to PrintStatus (0xA3 → 0xB3). {@code page} is the cumulative printed-page counter. */
    public static final class PrintStatus {
        public final int page, printPercent, feedPercent;

        PrintStatus(int page, int printPercent, int feedPercent) {
            this.page = page;
            this.printPercent = printPercent;
            this.feedPercent = feedPercent;
        }

        @Override
        public String toString() {
            return "page " + page + " (print " + printPercent + "%, feed " + feedPercent + "%)";
        }
    }

    /** Per-job options. */
    public static final class PrintOptions {
        int copies = 1;
        Integer density;
        Consumer<String> progress = s -> { };

        public static PrintOptions defaults() {
            return new PrintOptions();
        }

        /** Identical copies of one image; uploaded once, repeated by the printer. */
        public PrintOptions copies(int n) {
            if (n < 1 || n > 0xFFFF) throw new IllegalArgumentException("copies must be 1..65535");
            this.copies = n;
            return this;
        }

        /** Print darkness 1–5 (the official app's scale); default from the model (3). */
        public PrintOptions density(int d) {
            this.density = d;
            return this;
        }

        public PrintOptions onProgress(Consumer<String> p) {
            this.progress = p == null ? s -> { } : p;
            return this;
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final Transport transport;
    private final PrinterModel selectedModel;     // caller's choice; may be null (auto)
    private PrinterInfo info;
    private PrinterModel model;                   // effective model for printing

    private final Packet.Parser parser = new Packet.Parser();
    private final List<Waiter> waiters = new ArrayList<>();
    private volatile Packet lastUnsolicited;
    private volatile Integer rowsSeenMax;         // 0xD3 max last-row index for the current page

    private WriteMode writeMode = WriteMode.FAST; // detected
    private WriteMode writeOverride;              // null = auto
    private boolean bundleAllowed;
    private Boolean bundleOverride;               // null = per-model default
    private final ByteArrayOutputStream bundle = new ByteArrayOutputStream();

    private long paceMs = 10;
    private int bundleMax = 240;
    private long pageAckMs = 10_000;
    private long pageWaitMs = 25_000;
    private int lookahead = 2;

    private boolean debug;
    private Consumer<String> logger = s -> System.err.println("[niimbot] " + s);
    private int imgRows;
    private Integer pageSeen;

    private static final class Waiter {
        final Integer cmd;  // null = any opcode
        final CompletableFuture<Packet> future = new CompletableFuture<>();

        Waiter(Integer cmd) {
            this.cmd = cmd;
        }
    }

    /** Model is detected on {@link #connect()}. */
    public NiimbotPrinter(Transport transport) {
        this(transport, null);
    }

    /**
     * @param model the expected model, or null to rely on detection. If given and the printer
     *              identifies as a model with a different task, printing is refused.
     */
    public NiimbotPrinter(Transport transport, PrinterModel model) {
        this.transport = transport;
        this.selectedModel = model;
        transport.setReceiver(this::onBytes);
        transport.setDisconnectListener(() -> log("printer disconnected (link dropped — reconnect to continue)"));
    }

    // ── Tunables ──────────────────────────────────────────────────────────────
    public NiimbotPrinter setDebug(boolean on) { this.debug = on; return this; }
    public NiimbotPrinter setLogger(Consumer<String> logger) { this.logger = logger == null ? s -> { } : logger; return this; }
    /** Force a write mode (null = auto, as detected on connect). A diagnostic, mostly. */
    public NiimbotPrinter setWriteMode(WriteMode m) { this.writeOverride = m; return this; }
    /** Force frame bundling on/off (null = per-model default). */
    public NiimbotPrinter setBundle(Boolean on) { this.bundleOverride = on; return this; }
    public NiimbotPrinter setPaceMs(long ms) { this.paceMs = ms; return this; }
    public NiimbotPrinter setBundleMax(int bytes) { this.bundleMax = bytes; return this; }
    /** How long to wait for one page's PageEnd ack (default 10 s). */
    public NiimbotPrinter setPageAckMs(long ms) { this.pageAckMs = ms; return this; }
    /** How long to wait for the printed-page counter to reach a target (default 25 s). */
    public NiimbotPrinter setPageWaitMs(long ms) { this.pageWaitMs = ms; return this; }
    /** Pages buffered ahead of what has printed during a batch (default 2). */
    public NiimbotPrinter setLookahead(int pages) { this.lookahead = Math.max(1, pages); return this; }

    public WriteMode effectiveWriteMode() {
        return writeOverride != null ? writeOverride : writeMode;
    }

    public boolean effectiveBundle() {
        return bundleOverride != null ? bundleOverride : bundleAllowed;
    }

    public PrinterInfo info() {
        return info;
    }

    /** The model that print jobs will use (detected, else the one passed in). */
    public PrinterModel model() {
        return model;
    }

    // ── Logging ───────────────────────────────────────────────────────────────
    private void flushImg() {
        if (imgRows > 0 && debug) logger.accept("→  (… " + imgRows + " image rows 0x84/0x85 …)");
        imgRows = 0;
    }

    private void logTx(int cmd, byte[] data) {
        if (!debug) return;
        if (cmd == 0x84 || cmd == 0x85) { imgRows++; return; }
        flushImg();
        logger.accept(String.format("→  %02x (%db) %s", cmd, data.length, Packet.hex(data)));
    }

    private void dbg(String m) {
        if (debug) { flushImg(); logger.accept("·  " + m); }
    }

    private void log(String m) {
        flushImg();
        logger.accept("·  " + m);
    }

    // ── Receive path ──────────────────────────────────────────────────────────
    private void onBytes(byte[] chunk) {
        List<Packet> pkts;
        synchronized (parser) {
            pkts = parser.feed(chunk);
        }
        for (Packet p : pkts) {
            if (debug) logger.accept("←  " + p);
            if (p.cmd == RSP_ROWS_RECEIVED && p.length() >= 2) {
                int idx = p.u16(0);
                Integer cur = rowsSeenMax;
                if (cur == null || idx > cur) rowsSeenMax = idx;
            }
            Waiter hit = null;
            synchronized (waiters) {
                for (Iterator<Waiter> it = waiters.iterator(); it.hasNext(); ) {
                    Waiter w = it.next();
                    if (w.cmd == null || w.cmd == p.cmd) { it.remove(); hit = w; break; }
                }
            }
            if (hit != null) hit.future.complete(p);
            else lastUnsolicited = p;
        }
    }

    private Waiter register(Integer cmd) {
        Waiter w = new Waiter(cmd);
        synchronized (waiters) { waiters.add(w); }
        return w;
    }

    private void unregister(Waiter w) {
        synchronized (waiters) { waiters.remove(w); }
    }

    private static Packet await(Waiter w, long timeoutMs) throws InterruptedIOException {
        try {
            return w.future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted waiting for printer");
        } catch (ExecutionException e) {
            return null;
        }
    }

    // ── Send path ─────────────────────────────────────────────────────────────
    private static void sleep(long ms) throws InterruptedIOException {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted");
        }
    }

    private void writeRaw(byte[] bytes) throws IOException {
        if (!transport.isConnected()) throw new NiimbotException("Not connected (the printer dropped the link — reconnect)");
        WriteMode mode = effectiveWriteMode();
        if (mode == WriteMode.ACKED) { transport.write(bytes, true); return; }
        Exception last = null;
        for (int tries = 0; tries < 30; tries++) {
            try {
                transport.write(bytes, false);
                if (mode == WriteMode.PACED) sleep(paceMs);
                return;
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                last = e;   // BLE buffer full on a burst — brief retry
                sleep(4);
            }
        }
        throw new NiimbotException("Failed to write to the printer in " + mode
                + " mode (buffer full, or no unacknowledged write path)", last);
    }

    private void send(int cmd, int... data) throws IOException {
        byte[] d = toBytes(data);
        logTx(cmd, d);
        writeRaw(Packet.pack(cmd, d));
    }

    private static byte[] toBytes(int... data) {
        byte[] d = new byte[data.length];
        for (int i = 0; i < data.length; i++) d[i] = (byte) data[i];
        return d;
    }

    /** Sends a command and waits for a reply with opcode {@code wantResp} (null = any). Returns null on timeout. */
    private Packet sendWait(int cmd, byte[] data, Integer wantResp, long timeoutMs) throws IOException {
        Waiter w = register(wantResp);
        try {
            logTx(cmd, data);
            writeRaw(Packet.pack(cmd, data));
        } catch (IOException | RuntimeException e) {
            unregister(w);
            throw e;
        }
        Packet r = await(w, timeoutMs);
        if (r == null) {
            unregister(w);
            dbg(String.format("⚠ no response to %02x (wanted %s) after %d ms", cmd,
                    wantResp == null ? "any" : String.format("%02x", wantResp), timeoutMs));
        }
        return r;
    }

    private Packet sendWait(int cmd, int[] data, Integer wantResp, long timeoutMs) throws IOException {
        return sendWait(cmd, toBytes(data), wantResp, timeoutMs);
    }

    /**
     * Sends any command and returns the first reply (or null after {@code timeoutMs}).
     * For exploration; do not call during a print job.
     */
    public Packet probe(int cmd, byte[] data, long timeoutMs) throws IOException {
        return sendWait(cmd, data, null, timeoutMs);
    }

    // Frame bundling: several [55 55 … aa aa] row frames in one write, within the MTU.
    private void sendBundled(Packet p) throws IOException {
        logTx(p.cmd, p.data);
        byte[] frame = Packet.pack(p.cmd, p.data);
        int max = effectiveBundle() ? bundleMax : 0;
        if (bundle.size() > 0 && bundle.size() + frame.length > Math.max(max, frame.length)) flushBundle();
        bundle.write(frame, 0, frame.length);
    }

    private void flushBundle() throws IOException {
        if (bundle.size() == 0) return;
        byte[] out = bundle.toByteArray();
        bundle.reset();
        writeRaw(out);
    }

    // ── Connect & identify ────────────────────────────────────────────────────

    /**
     * Opens the session: connection packet, model detection, flow-control choice and,
     * for "b1"-task printers, the post-connect handshake that arms them for printing.
     */
    public PrinterInfo connect() throws IOException {
        // Small detection packets go out fast (or acked if that is all there is).
        writeMode = transport.supportsWriteWithoutResponse() ? WriteMode.FAST : WriteMode.ACKED;
        int max = transport.maxWriteSize();
        if (max > 0) bundleMax = Math.min(bundleMax, max);

        logTx(CMD_CONNECT, new byte[]{1});
        writeRaw(CONNECT_PACKET);
        sleep(200);
        info = detect();

        PrinterModel detected = info.model;
        if (detected != null && selectedModel != null && detected.task != selectedModel.task) {
            throw new NiimbotException("Connected printer is " + detected.label + " (task " + detected.task + ", "
                    + detected.dpi + " dpi), but the selected model " + selectedModel.label + " uses task "
                    + selectedModel.task + ". Select " + detected.name() + " (or leave the model on auto).");
        }
        model = detected != null ? detected : selectedModel;

        boolean needsPacing = model != null ? model.paced : false;
        if (!transport.supportsWriteWithoutResponse()) writeMode = WriteMode.ACKED;
        else writeMode = needsPacing ? WriteMode.PACED : WriteMode.FAST;
        bundleAllowed = model != null && model.bundle && detected != null;   // only where validated
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        if (mac && writeMode == WriteMode.FAST) writeMode = WriteMode.PACED;  // CoreBluetooth drops unacked bursts

        log("connected: " + info + " → model=" + (model != null ? model.name() : "?")
                + " writeMode=" + writeMode + " effective=" + effectiveWriteMode()
                + " bundle=" + effectiveBundle() + " (max " + bundleMax + " B) pace=" + paceMs + "ms");
        if (writeOverride == WriteMode.FAST && model != null && model.paced) {
            log("⚠ write mode FAST overrides " + model.label + ", which needs pacing — expect blank or short labels.");
        }
        if (model == null) {
            log("⚠ printer not identified and no model given — pass a PrinterModel to the constructor to print.");
        } else if (model.task == PrinterModel.Task.B1) {
            b1Handshake();
        }
        return info;
    }

    private PrinterInfo detect() throws IOException {
        Integer modelId = null, protocol = null;
        Packet s = sendWait(CMD_STATUS_DATA, new int[]{1}, RSP_STATUS_DATA, 1000);
        if (s != null && s.length() >= 13) {
            int n = s.u8(11) * 100 + s.u8(12);
            protocol = (n >= 204 && n < 300) ? 3 : (n >= 302 ? 5 : (n >= 300 ? 4 : 0));
        }
        Packet r = sendWait(CMD_INFO, new int[]{0x08}, RSP_MODEL_ID, 1000);
        if (r != null && r.length() >= 1) {
            modelId = r.length() >= 2 ? r.u16(0) : (r.u8(0) << 8);
        }
        return new PrinterInfo(modelId, protocol, transport.deviceName());
    }

    /**
     * The protocol-3 B1 accepts every setup command but never starts printing unless it
     * first sees niim.blue's post-connect reads: status, printer info, heartbeat.
     */
    private void b1Handshake() throws IOException {
        dbg("b1 handshake (status + info + heartbeat)");
        sendWait(CMD_STATUS_DATA, new int[]{1}, RSP_STATUS_DATA, 1000);
        for (int sub : new int[]{0x08, 0x0b, 0x0d, 0x0a, 0x07, 0x03, 0x0c, 0x09}) {
            sendWait(CMD_INFO, new int[]{sub}, null, 600);
        }
        sendWait(CMD_HEARTBEAT, new int[]{0x04}, RSP_HEARTBEAT, 1000);
    }

    /**
     * Raw heartbeat (0xDC[04]). Reply opcode 0xD9 on most models. Upstream decoded, on a
     * B1 Pro only: byte 4 = lid (0 closed), byte 5 = paper (0 inserted), byte 6 = RFID read ok.
     * Returned raw because offsets vary per model.
     */
    public Packet heartbeat() throws IOException {
        return sendWait(CMD_HEARTBEAT, new int[]{0x04}, null, 1000);
    }

    /** Raw RFID consumable info (0x1A → 0x1B), or null — many models/rolls never answer. */
    public Packet rfidInfo() throws IOException {
        return sendWait(CMD_RFID, new int[]{0x01}, RSP_RFID, 600);
    }

    /** PrintStatus (0xA3 → 0xB3), or null if no reply within {@code timeoutMs}. */
    public PrintStatus printStatus(long timeoutMs) throws IOException {
        lastUnsolicited = null;
        Waiter w = register(RSP_PRINT_STATUS);
        try {
            send(CMD_PRINT_STATUS, 1);
        } catch (IOException e) {
            unregister(w);
            throw e;
        }
        Packet r = await(w, timeoutMs);
        if (r == null) {
            unregister(w);
            Packet u = lastUnsolicited;
            if (u != null && u.cmd == RSP_PRINT_STATUS) r = u;
        }
        if (r == null || r.length() < 4) return null;
        return new PrintStatus(r.u16(0), r.u8(2), r.u8(3));
    }

    // ── Printing ──────────────────────────────────────────────────────────────

    /** Rasterises {@code image} to {@code size} and prints it. */
    public void print(BufferedImage image, LabelSize size, PrintOptions opts) throws IOException {
        checkSize(size);
        print(LabelBitmap.fromImage(image, size), opts);
    }

    /**
     * Prints one bitmap, {@code opts.copies} times. Copies are declared once and the image
     * crosses the link once; the printer repeats it (except on one-page-per-job models).
     */
    public void print(LabelBitmap bmp, PrintOptions opts) throws IOException {
        if (opts == null) opts = PrintOptions.defaults();
        PrinterModel m = requireModel();
        int density = densityFor(m, opts);
        int copies = opts.copies;
        Consumer<String> progress = opts.progress;
        long t0 = System.nanoTime();

        if (m.pagesPerJob == 1 && copies > 1) {
            log("⚠ " + m.label + " only prints page 1 of a multi-page job — printing " + copies
                    + " copies as " + copies + " separate jobs.");
            for (int i = 0; i < copies; i++) {
                String tag = "copy " + (i + 1) + "/" + copies;
                singleJob(m, bmp, 1, density, tagged(progress, tag), tag);
            }
        } else {
            singleJob(m, bmp, copies, density, progress, null);
        }
        dbg(String.format("done in %d ms", (System.nanoTime() - t0) / 1_000_000));
        progress.accept("ok");
    }

    /** Rasterises each image to {@code size} and prints them as one continuous job. */
    public void printBatchImages(List<BufferedImage> images, LabelSize size, PrintOptions opts) throws IOException {
        checkSize(size);
        List<LabelBitmap> bmps = new ArrayList<>(images.size());
        for (BufferedImage img : images) bmps.add(LabelBitmap.fromImage(img, size));
        printBatch(bmps, opts);
    }

    /**
     * Prints different labels back to back in one job (no paper retract between them).
     * Pages are pipelined: up to {@link #setLookahead lookahead} pages are queued ahead of
     * the printed-page counter. {@code opts.copies} is ignored here.
     */
    public void printBatch(List<LabelBitmap> pages, PrintOptions opts) throws IOException {
        if (opts == null) opts = PrintOptions.defaults();
        if (pages.isEmpty()) return;
        PrinterModel m = requireModel();
        int density = densityFor(m, opts);
        Consumer<String> progress = opts.progress;
        int n = pages.size();

        if (m.pagesPerJob == 1 && n > 1) {
            log("⚠ " + m.label + " only prints page 1 of a multi-page job — sending " + n + " labels as " + n + " separate jobs.");
            for (int i = 0; i < n; i++) {
                String tag = "label " + (i + 1) + "/" + n;
                singleJob(m, pages.get(i), 1, density, tagged(progress, tag), tag);
            }
            progress.accept("ok");
            return;
        }

        beginJob(m, n, density, progress);
        String problem = null;
        for (int i = 0; i < n && problem == null; i++) {
            String tag = "label " + (i + 1) + "/" + n;
            Consumer<String> pp = tagged(progress, tag);
            pp.accept("sending…");
            boolean acked = sendPage(m, pages.get(i), 1, pp, tag);
            if (!acked) {
                problem = "page " + (i + 1) + " of " + n + " was never acknowledged after " + pageAckMs + " ms (no PageEnd ack)";
                break;
            }
            if (i - lookahead >= 0) {
                int want = i - lookahead + 1;
                if (!waitPage(want, pp)) {
                    problem = "printer counter stalled at page " + seen() + " of " + want + " while streaming (" + pageWaitMs + " ms)";
                }
            }
        }
        if (problem == null && !waitPage(n, progress)) {
            problem = "printer counter stopped at page " + seen() + " of " + n + " after " + pageWaitMs + " ms";
        }
        endJob();   // always: PrintEnd feeds the paper out
        if (problem != null) throw new NiimbotException.PrintNotConfirmed(problem);
        progress.accept("ok");
    }

    private void singleJob(PrinterModel m, LabelBitmap bmp, int copies, int density,
                           Consumer<String> progress, String tag) throws IOException {
        beginJob(m, copies, density, progress);
        boolean acked = sendPage(m, bmp, copies, progress, tag);
        if (!acked) {
            endJob();
            throw new NiimbotException.PrintNotConfirmed("the printer never acknowledged PageEnd"
                    + (tag != null ? " for " + tag : "") + " after " + pageAckMs + " ms");
        }
        finishJob(copies, progress);
    }

    private void beginJob(PrinterModel m, int totalPages, int density, Consumer<String> progress) throws IOException {
        progress.accept("configuring…");
        sendWait(CMD_DENSITY, new int[]{density}, RSP_DENSITY, 1000);
        sendWait(CMD_LABEL_TYPE, new int[]{m.labelType}, RSP_LABEL_TYPE, 1000);
        int n = Math.max(1, totalPages);
        int[] start = m.task == PrinterModel.Task.B1
                ? new int[]{n >> 8, n, 0, 0, 0, 0, 0}                    // 7 bytes
                : new int[]{n >> 8, n, 0, 0, 0, 0, 0, m.speed, 0};       // 9 bytes (speed)
        if (sendWait(CMD_PRINT_START, start, RSP_PRINT_START, 2000) == null) {
            dbg("⚠ PrintStart not acknowledged — continuing");
        }
    }

    /** Sends one page; returns whether PageEnd was acknowledged. */
    private boolean sendPage(PrinterModel m, LabelBitmap bmp, int copies, Consumer<String> progress, String tag) throws IOException {
        int h = bmp.height, w = bmp.width, c = Math.max(1, copies);
        rowsSeenMax = null;
        if (m.task == PrinterModel.Task.B1) {
            sendWait(CMD_PAGE_START, new int[]{1}, RSP_PAGE_START, 1000);
            sendWait(CMD_PAGE_SIZE, new int[]{h >> 8, h, w >> 8, w, c >> 8, c}, RSP_PAGE_SIZE, 2000);
        } else {
            send(CMD_PRINT_STATUS, 1);   // one-way (B21 Pro workaround)
            sleep(30);
            sendWait(CMD_PAGE_SIZE, new int[]{h >> 8, h, w >> 8, w, c >> 8, c, 0, 0, 0, 0, 0, 0, 0}, RSP_PAGE_SIZE, 2000);
        }

        progress.accept("sending image…");
        for (Packet row : bmp.encodeRows()) sendBundled(row);
        flushBundle();

        long t = System.nanoTime();
        Packet end = sendWait(CMD_PAGE_END, new int[]{1}, RSP_PAGE_END, pageAckMs);
        String prefix = tag != null ? tag + ": " : "";
        dbg(prefix + "PageEnd " + (end != null ? "acked" : "UNACKED") + " in " + (System.nanoTime() - t) / 1_000_000 + " ms");
        if (end != null) {
            // 0xD3 row-received counter: only some models send it. Absent = not verifiable, not a failure.
            Integer seen = rowsSeenMax;
            if (seen == null) {
                dbg(prefix + "no 0xD3 seen; upload not verified");
            } else if (seen < h - 1) {
                endJob();
                throw new NiimbotException.PrintNotConfirmed(prefix + "printer's 0xD3 counter last reached row " + seen
                        + ", but the page needed row " + (h - 1) + " — the label may have come out short");
            } else {
                dbg(prefix + "rows confirmed: " + seen + "/" + (h - 1));
            }
        }
        return end != null;
    }

    /** Polls the printed-page counter until it reaches {@code target}. */
    private boolean waitPage(int target, Consumer<String> progress) throws IOException {
        progress.accept("printing…");
        long deadline = System.nanoTime() + pageWaitMs * 1_000_000L;
        pageSeen = null;
        while (System.nanoTime() < deadline) {
            PrintStatus st = printStatus(900);
            if (st != null) {
                pageSeen = st.page;
                progress.accept("printing… " + st.printPercent + "%");
                if (st.page >= target) return true;
            }
            sleep(150);
        }
        return false;
    }

    /** Waits for every page (so PrintEnd doesn't cut a label), then PrintEnd — even on failure. */
    private void finishJob(int target, Consumer<String> progress) throws IOException {
        int want = Math.max(1, target);
        boolean reached = waitPage(want, progress);
        endJob();
        if (!reached) {
            throw new NiimbotException.PrintNotConfirmed("printer counter stopped at page " + seen() + " of " + want
                    + " after " + pageWaitMs + " ms");
        }
    }

    private void endJob() throws IOException {
        sendWait(CMD_PRINT_END, new int[]{1}, RSP_PRINT_END, 2500);
    }

    private String seen() {
        return pageSeen == null ? "?" : String.valueOf(pageSeen);
    }

    private PrinterModel requireModel() throws NiimbotException {
        if (info == null) throw new NiimbotException("call connect() first");
        if (model == null) {
            throw new NiimbotException("Printer not identified (" + info + ") and no model given — "
                    + "construct NiimbotPrinter with a PrinterModel.");
        }
        return model;
    }

    private void checkSize(LabelSize size) throws NiimbotException {
        PrinterModel m = requireModel();
        if (size.dpi > 0 && size.dpi != m.dpi) {
            throw new NiimbotException("Label size " + size.name + " is " + size.dpi + " dpi but " + m.label
                    + " prints at " + m.dpi + " dpi. Pick a " + m.dpi + " dpi size.");
        }
    }

    private static int densityFor(PrinterModel m, PrintOptions opts) {
        int d = opts.density != null ? opts.density : m.defaultDensity;
        if (d < 1 || d > 5) throw new IllegalArgumentException("density must be 1–5 (got " + d + ")");
        return d;
    }

    private static Consumer<String> tagged(Consumer<String> p, String tag) {
        return s -> p.accept(tag + ": " + s);
    }

    /** Closes the transport. */
    @Override
    public void close() {
        synchronized (waiters) { waiters.clear(); }
        transport.close();
    }
}
