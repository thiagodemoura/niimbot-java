package dev.niimbot;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-for-byte equivalence with the original JavaScript driver: every BLE write the
 * Java driver makes (connect, identify, handshake, job setup, row frames incl. bundling,
 * status polls, PrintEnd) must equal what niimbot.js wrote for the same scenario.
 * Golden data: src/test/resources/golden-js.txt (made by record-golden.js).
 */
class GoldenTest {

    /** Same pattern as record-golden.js. */
    static boolean black(int x, int y, int seed) {
        if (y < 10 || (y >= 50 && y < 70)) return false;
        return ((x * 7 + (y >> 2) * 13 + seed * 5) % 11) < 4;
    }

    static LabelBitmap pattern(int w, int h, int seed) {
        int stride = (w + 7) >> 3;
        byte[] bits = new byte[stride * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (black(x, y, seed)) bits[y * stride + (x >> 3)] |= (byte) (0x80 >> (x & 7));
        return new LabelBitmap(w, h, bits);
    }

    static final class Scenario {
        String name, model;
        int w, h, copies, density;
        List<Integer> seeds = new ArrayList<>();
        List<String> writes = new ArrayList<>();
    }

    static List<Scenario> load() throws Exception {
        List<Scenario> out = new ArrayList<>();
        var in = GoldenTest.class.getResourceAsStream("/golden-js.txt");
        assertNotNull(in, "golden-js.txt missing");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Scenario cur = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isEmpty() || line.startsWith("%")) continue;
                if (line.startsWith("# ")) {
                    String[] f = line.substring(2).split(" ");
                    cur = new Scenario();
                    cur.name = f[0]; cur.model = f[1];
                    cur.w = Integer.parseInt(f[2]); cur.h = Integer.parseInt(f[3]);
                    cur.copies = Integer.parseInt(f[4]); cur.density = Integer.parseInt(f[5]);
                    for (String s : f[6].split(",")) cur.seeds.add(Integer.parseInt(s));
                    out.add(cur);
                } else {
                    cur.writes.add(line);
                }
            }
        }
        return out;
    }

    @Test
    void matchesJavaScriptDriverByteForByte() throws Exception {
        List<Scenario> all = load();
        assertTrue(all.size() >= 9, "expected the recorded scenarios");
        for (Scenario s : all) {
            PrinterModel m = PrinterModel.parse(s.model);
            FakePrinter fake = new FakePrinter(m.id);
            NiimbotPrinter p = new NiimbotPrinter(fake).setPaceMs(0).setPageWaitMs(2000).setLogger(x -> { });
            p.connect();
            assertEquals(m, p.model(), s.name + ": detected model");
            var opts = NiimbotPrinter.PrintOptions.defaults().copies(s.copies).density(s.density);
            if (s.seeds.size() == 1) {
                p.print(pattern(s.w, s.h, s.seeds.get(0)), opts);
            } else {
                List<LabelBitmap> pages = new ArrayList<>();
                for (int seed : s.seeds) pages.add(pattern(s.w, s.h, seed));
                p.printBatch(pages, opts);
            }
            assertEquals(s.writes.size(), fake.writes.size(), s.name + ": number of BLE writes");
            for (int i = 0; i < s.writes.size(); i++) {
                assertEquals(s.writes.get(i), fake.writes.get(i), s.name + ": write #" + i);
            }
        }
    }
}
