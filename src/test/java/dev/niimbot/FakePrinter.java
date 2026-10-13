package dev.niimbot;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * In-memory printer: answers the job lifecycle the way a real Niimbot does (same
 * responder as the JS golden recorder, src/test/resources/record-golden.js) and records
 * every write. Answers synchronously from inside write(), like the JS fake.
 */
final class FakePrinter implements Transport {
    final List<String> writes = new ArrayList<>();
    private Consumer<byte[]> rx = b -> { };
    private final int modelId;
    private int pageCounter;
    private int copiesThisPage = 1;

    // fault injection
    boolean dropPageEndAck;
    Integer rowsReceivedReport;   // if set, send 0xD3 with this last-row index before 0xE4
    Integer counterCap;           // printed-page counter never exceeds this
    boolean writeNoResponse = true;

    FakePrinter(int modelId) {
        this.modelId = modelId;
    }

    private void answer(int cmd, int... data) {
        rx.accept(Packet.pack(cmd, data));
    }

    @Override
    public void write(byte[] b, boolean withResponse) {
        writes.add((withResponse ? "r:" : "n:") + HexFormat.of().formatHex(b));
        if ((b[0] & 0xFF) == 0x03) return;   // raw connect packet
        int cmd = b[2] & 0xFF, sub = b.length > 4 ? b[4] & 0xFF : 0;
        switch (cmd) {
            case 0xA5: { int[] d = new int[13]; d[11] = 3; d[12] = 1; answer(0xB5, d); break; }
            case 0x40:
                if (sub == 0x08) answer(0x48, modelId >> 8, modelId & 0xFF);
                else answer(0x40 + sub, 0x00);
                break;
            case 0xDC: answer(0xD9, 0x1f, 0x50, 0x50, 0x48, 0, 0, 1, 0, 0, 0, 0, 0, 0); break;
            case 0x21: answer(0x31, 1); break;
            case 0x23: answer(0x33, 1); break;
            case 0x01: pageCounter = 0; answer(0x02, 1); break;
            case 0x03: answer(0x04, 1); break;
            case 0x13: copiesThisPage = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF); answer(0x14, 1); break;
            case 0xE3:
                if (rowsReceivedReport != null) answer(0xD3, rowsReceivedReport >> 8, rowsReceivedReport & 0xFF, 0);
                if (dropPageEndAck) break;
                pageCounter += copiesThisPage;
                answer(0xE4, 1);
                break;
            case 0xF3: answer(0xF4, 1); break;
            case 0xA3: {
                int p = counterCap != null ? Math.min(pageCounter, counterCap) : pageCounter;
                answer(0xB3, p >> 8, p & 0xFF, 100, 100, 0, 0, 0, 0, 0, 0);
                break;
            }
            default: break;
        }
    }

    /** Opcodes of every framed write (first frame of each write), for flow assertions. */
    List<Integer> opcodes() {
        List<Integer> out = new ArrayList<>();
        for (String w : writes) {
            byte[] b = HexFormat.of().parseHex(w.substring(2));
            if ((b[0] & 0xFF) == 0x03) out.add(0xC1);
            else out.add(b[2] & 0xFF);
        }
        return out;
    }

    long count(int opcode) {
        return opcodes().stream().filter(c -> c == opcode).count();
    }

    @Override public void setReceiver(Consumer<byte[]> receiver) { this.rx = receiver; }
    @Override public boolean supportsWriteWithoutResponse() { return writeNoResponse; }
    @Override public String deviceName() { return "FAKE"; }
    @Override public boolean isConnected() { return true; }
    @Override public void close() { }
}
