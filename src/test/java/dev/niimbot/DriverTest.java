package dev.niimbot;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverTest {

    private static NiimbotPrinter quiet(Transport t, PrinterModel m) {
        return new NiimbotPrinter(t, m).setPaceMs(0).setPageWaitMs(400).setPageAckMs(200).setLogger(s -> { });
    }

    private static LabelBitmap solid(int w, int h) {
        return GoldenTest.pattern(w, h, 1);
    }

    @Test
    void packMatchesDocumentedConnectFrame() {
        byte[] p = Packet.pack(0xC1, 1);
        assertEquals("5555c10101c1aaaa", HexFormat.of().formatHex(p));
        // crc = cmd ^ len ^ data
        assertEquals("5555210103" + "23" + "aaaa", HexFormat.of().formatHex(Packet.pack(0x21, 3)));
    }

    @Test
    void parserHandlesSplitMergedAndJunk() {
        Packet.Parser parser = new Packet.Parser();
        byte[] a = Packet.pack(0xB3, 0, 1, 100, 100);
        byte[] b = Packet.pack(0xE4, 1);
        byte[] all = new byte[3 + a.length + b.length];
        all[0] = 0x12; all[1] = 0x55; all[2] = 0x00;                 // junk incl. a lone 0x55
        System.arraycopy(a, 0, all, 3, a.length);
        System.arraycopy(b, 0, all, 3 + a.length, b.length);
        assertEquals(0, parser.feed(java.util.Arrays.copyOfRange(all, 0, 8)).size());
        List<Packet> got = parser.feed(java.util.Arrays.copyOfRange(all, 8, all.length));
        assertEquals(2, got.size());
        assertEquals(0xB3, got.get(0).cmd);
        assertEquals(1, got.get(0).u16(0));
        assertEquals(0xE4, got.get(1).cmd);
    }

    @Test
    void rowEncodingRunLengthAndBlankRows() {
        LabelBitmap bmp = new LabelBitmap(16, 450, new byte[2 * 450]);   // all white, 450 rows
        List<Packet> rows = bmp.encodeRows();
        assertEquals(3, rows.size());                                    // runs capped at 200
        assertEquals(0x84, rows.get(0).cmd);
        assertArrayEquals(new byte[]{0, 0, (byte) 200}, rows.get(0).data);
        assertArrayEquals(new byte[]{0, (byte) 200, (byte) 200}, rows.get(1).data);
        assertArrayEquals(new byte[]{1, (byte) 144, 50}, rows.get(2).data);

        byte[] bits = new byte[2];
        bits[0] = (byte) 0xF0; bits[1] = 0x01;                           // 5 black pixels
        Packet r = new LabelBitmap(16, 1, bits).encodeRows().get(0);
        assertEquals(0x85, r.cmd);
        assertArrayEquals(new byte[]{0, 0, 0, 5, 0, 1, (byte) 0xF0, 0x01}, r.data);
    }

    @Test
    void imageThresholdScaleAndOffset() {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);   // transparent
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 5, 10);                                          // left half black
        g.dispose();
        LabelBitmap b = LabelBitmap.fromImage(img, 20, 20, 0);
        assertTrue(b.isBlack(0, 0));
        assertTrue(b.isBlack(8, 10));
        assertFalse(b.isBlack(12, 10));                                   // transparent → white
        LabelBitmap shifted = LabelBitmap.fromImage(img, 20, 20, 4);
        assertFalse(shifted.isBlack(0, 2));                               // top rows pushed down → white
        assertTrue(shifted.isBlack(0, 6));
    }

    @Test
    void b1TaskRunsHandshakeAndShortCommands() throws Exception {
        FakePrinter f = new FakePrinter(PrinterModel.B1.id);
        NiimbotPrinter p = quiet(f, null);
        p.connect();
        assertEquals(PrinterModel.B1, p.model());
        assertEquals(NiimbotPrinter.WriteMode.PACED, p.effectiveWriteMode());
        assertTrue(p.effectiveBundle());
        assertEquals(1, f.count(0xDC));                                   // handshake heartbeat
        p.print(solid(384, 240), NiimbotPrinter.PrintOptions.defaults());
        assertEquals(1, f.count(0x03));                                   // PageStart
        assertEquals(1, f.count(0xF3));                                   // one PrintEnd
    }

    @Test
    void v4TaskSkipsHandshake() throws Exception {
        FakePrinter f = new FakePrinter(PrinterModel.B1_PRO.id);
        NiimbotPrinter p = quiet(f, null);
        p.connect();
        assertEquals(0, f.count(0xDC));
        assertFalse(p.effectiveBundle());
        p.print(solid(576, 354), NiimbotPrinter.PrintOptions.defaults().copies(2));
        assertEquals(0, f.count(0x03));
        assertEquals(1, f.count(0x01));                                   // one job for both copies
    }

    @Test
    void onePagePerJobModelSplitsCopies() throws Exception {
        FakePrinter f = new FakePrinter(PrinterModel.D110.id);
        NiimbotPrinter p = quiet(f, null);
        p.connect();
        p.print(solid(96, 400), NiimbotPrinter.PrintOptions.defaults().copies(3));
        assertEquals(3, f.count(0x01));
        assertEquals(3, f.count(0xF3));
    }

    @Test
    void unackedPageEndStillFeedsPaperOutThenThrows() throws Exception {
        FakePrinter f = new FakePrinter(PrinterModel.B1_PRO.id);
        f.dropPageEndAck = true;
        NiimbotPrinter p = quiet(f, null);
        p.connect();
        var e = assertThrows(NiimbotException.PrintNotConfirmed.class,
                () -> p.print(solid(120, 80), NiimbotPrinter.PrintOptions.defaults()));
        assertTrue(e.getMessage().contains("PageEnd"));
        List<Integer> ops = f.opcodes();
        assertEquals(0xF3, (int) ops.get(ops.size() - 1));               // PrintEnd last
    }

    @Test
    void droppedRowsDetectedVia0xD3() throws Exception {
        FakePrinter f = new FakePrinter(PrinterModel.D11_H.id);
        f.rowsReceivedReport = 100;                                       // page needs row 353
        NiimbotPrinter p = quiet(f, null);
        p.connect();
        var e = assertThrows(NiimbotException.PrintNotConfirmed.class,
                () -> p.print(solid(144, 354), NiimbotPrinter.PrintOptions.defaults()));
        assertTrue(e.getMessage().contains("row 100"));
        assertEquals(1, f.count(0xF3));
    }

    @Test
    void stalledCounterInBatchIsReported() throws Exception {
        FakePrinter f = new FakePrinter(PrinterModel.B1_PRO.id);
        f.counterCap = 1;
        NiimbotPrinter p = quiet(f, null);
        p.connect();
        var pages = List.of(solid(120, 80), solid(120, 80), solid(120, 80), solid(120, 80));
        var e = assertThrows(NiimbotException.PrintNotConfirmed.class,
                () -> p.printBatch(pages, NiimbotPrinter.PrintOptions.defaults()));
        assertTrue(e.getMessage().contains("page 1 of"));
        assertEquals(1, f.count(0xF3));
    }

    @Test
    void refusesMismatchedModelAndDpi() throws Exception {
        NiimbotPrinter p = quiet(new FakePrinter(PrinterModel.B1.id), PrinterModel.B1_PRO);
        assertThrows(NiimbotException.class, p::connect);                // b1 task vs v4 task

        NiimbotPrinter q = quiet(new FakePrinter(PrinterModel.B1.id), null);
        q.connect();
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        assertThrows(NiimbotException.class,
                () -> q.print(img, LabelSize.preset("T50x30"), NiimbotPrinter.PrintOptions.defaults())); // 300 dpi size
        assertThrows(IllegalArgumentException.class,
                () -> q.print(solid(8, 8), NiimbotPrinter.PrintOptions.defaults().density(9)));
    }

    @Test
    void ackedWritesWhenNoWriteWithoutResponse() throws Exception {
        FakePrinter f = new FakePrinter(PrinterModel.B1.id);
        f.writeNoResponse = false;
        NiimbotPrinter p = quiet(f, null);
        p.connect();
        assertEquals(NiimbotPrinter.WriteMode.ACKED, p.effectiveWriteMode());
        p.print(solid(64, 32), NiimbotPrinter.PrintOptions.defaults());
        assertTrue(f.writes.stream().skip(1).allMatch(w -> w.startsWith("r:")));
    }

    @Test
    void bundlesRespectMaxWriteSize() throws Exception {
        FakePrinter f = new FakePrinter(PrinterModel.M2_H.id);
        NiimbotPrinter p = quiet(f, null);
        p.connect();
        p.print(GoldenTest.pattern(567, 354, 3), NiimbotPrinter.PrintOptions.defaults());
        int maxRowWrite = f.writes.stream().map(w -> (w.length() - 2) / 2).max(Integer::compare).orElse(0);
        int frame = 7 + 6 + (567 + 7) / 8;                                // one 0x85 frame = 84 B
        assertTrue(maxRowWrite <= Math.max(240, frame), "bundle too big: " + maxRowWrite);
        assertTrue(maxRowWrite > frame, "expected bundling on M2-H");
    }
}
