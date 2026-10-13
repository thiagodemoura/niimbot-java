package dev.niimbot;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Niimbot V4 frame: {@code [0x55, 0x55, cmd, len, ...data, crc, 0xAA, 0xAA]},
 * where {@code crc = cmd ^ len ^ data[0] ^ ... ^ data[n-1]}.
 * Responses arrive in the same frame format.
 */
public final class Packet {
    public final int cmd;
    public final byte[] data;

    public Packet(int cmd, byte[] data) {
        this.cmd = cmd & 0xFF;
        this.data = data == null ? new byte[0] : data;
    }

    /** Payload byte {@code i} as an unsigned int. */
    public int u8(int i) {
        return data[i] & 0xFF;
    }

    /** Big-endian unsigned 16-bit value at {@code i}. */
    public int u16(int i) {
        return (u8(i) << 8) | u8(i + 1);
    }

    public int length() {
        return data.length;
    }

    /** Encodes a frame. {@code data} values are taken modulo 256. */
    public static byte[] pack(int cmd, int... data) {
        byte[] d = new byte[data.length];
        for (int i = 0; i < data.length; i++) d[i] = (byte) data[i];
        return pack(cmd, d);
    }

    public static byte[] pack(int cmd, byte[] data) {
        if (data.length > 255) throw new IllegalArgumentException("payload too long: " + data.length);
        byte[] pkt = new byte[7 + data.length];
        pkt[0] = 0x55;
        pkt[1] = 0x55;
        pkt[2] = (byte) cmd;
        pkt[3] = (byte) data.length;
        int crc = (cmd ^ data.length) & 0xFF;
        for (int i = 0; i < data.length; i++) {
            pkt[4 + i] = data[i];
            crc ^= data[i] & 0xFF;
        }
        pkt[4 + data.length] = (byte) crc;
        pkt[5 + data.length] = (byte) 0xAA;
        pkt[6 + data.length] = (byte) 0xAA;
        return pkt;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("%02x (%db) %s", cmd, data.length, hex(data));
    }

    /**
     * Reassembles frames from a byte stream. BLE usually delivers one whole frame
     * per notification, but a serial link (or a large MTU) can split or merge them,
     * so this parser buffers and resynchronises on the {@code 55 55} header.
     * Not thread-safe; feed it from one thread (the notification callback).
     */
    public static final class Parser {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        public List<Packet> feed(byte[] chunk) {
            buf.write(chunk, 0, chunk.length);
            byte[] b = buf.toByteArray();
            List<Packet> out = new ArrayList<>();
            int p = 0;
            while (true) {
                // find header
                while (p + 1 < b.length && !((b[p] & 0xFF) == 0x55 && (b[p + 1] & 0xFF) == 0x55)) p++;
                if (p + 4 > b.length) break;                 // need header + cmd + len
                int len = b[p + 3] & 0xFF;
                int total = 7 + len;
                if (p + total > b.length) break;             // wait for the rest
                if ((b[p + total - 2] & 0xFF) != 0xAA || (b[p + total - 1] & 0xFF) != 0xAA) {
                    p++;                                     // bad tail: resync one byte later
                    continue;
                }
                out.add(new Packet(b[p + 2], Arrays.copyOfRange(b, p + 4, p + 4 + len)));
                p += total;
            }
            buf.reset();
            if (p < b.length) {
                // cap: a never-completing junk prefix must not grow forever
                int keepFrom = Math.max(p, b.length - 1024);
                buf.write(b, keepFrom, b.length - keepFrom);
            }
            return out;
        }

        public void reset() {
            buf.reset();
        }
    }
}
