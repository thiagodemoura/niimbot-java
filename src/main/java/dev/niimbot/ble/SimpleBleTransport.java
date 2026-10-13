package dev.niimbot.ble;

import dev.niimbot.Transport;
import org.simplejavable.Adapter;
import org.simplejavable.BluetoothUUID;
import org.simplejavable.Characteristic;
import org.simplejavable.Peripheral;
import org.simplejavable.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * BLE transport over SimpleJavaBLE (Windows, macOS, Linux/BlueZ; native libraries
 * bundled in the jar). Niimbot printers expose one GATT characteristic with
 * NOTIFY + WRITE_NO_RESPONSE; commands go out as writes and replies come back as
 * notifications.
 *
 * <p>Unlike Web Bluetooth, no pairing chooser is needed: the transport scans and picks
 * the first device whose advertised name starts with the given prefix (e.g. "B1",
 * "D11", "M2") or whose address matches.
 */
public final class SimpleBleTransport implements Transport {

    public static final String SERVICE_UUID = "e7810a71-73ae-499d-8c15-faa9aef0c3f2";
    public static final String CHAR_UUID = "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f";

    private static final BluetoothUUID SVC = new BluetoothUUID(SERVICE_UUID);
    private static final BluetoothUUID CHR = new BluetoothUUID(CHAR_UUID);

    private final Peripheral peripheral;
    private final String name;
    private volatile Consumer<byte[]> receiver = b -> { };
    private volatile Runnable onDisconnect = () -> { };
    private volatile boolean closing;
    private boolean canWriteCommand = true;

    private SimpleBleTransport(Peripheral p) {
        this.peripheral = p;
        this.name = p.getIdentifier();
    }

    /** A device found by {@link #scan}. */
    public static final class Found {
        public final String name;
        public final String address;
        public final int rssi;
        final Peripheral peripheral;

        Found(Peripheral p) {
            this.peripheral = p;
            this.name = p.getIdentifier();
            this.address = String.valueOf(p.getAddress());
            this.rssi = p.getRssi();
        }

        @Override
        public String toString() {
            return (name == null || name.isEmpty() ? "(no name)" : name) + " [" + address + ", " + rssi + " dBm]";
        }
    }

    private static Adapter adapter() throws IOException {
        List<Adapter> adapters;
        try {
            if (!Adapter.isBluetoothEnabled()) throw new IOException("Bluetooth is disabled or unavailable");
            adapters = Adapter.getAdapters();
        } catch (LinkageError | RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            throw new IOException("Bluetooth stack unavailable (SimpleJavaBLE native library could not load on "
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch") + "): " + root.getMessage(), e);
        }
        if (adapters.isEmpty()) throw new IOException("No Bluetooth adapter found");
        return adapters.get(0);
    }

    /** Scans for {@code timeoutMs} and returns every device that advertised a name. */
    public static List<Found> scan(int timeoutMs) throws IOException {
        Adapter a = adapter();
        try {
            a.scanFor(timeoutMs);
        } catch (Exception e) {
            throw new IOException("BLE scan failed: " + e.getMessage(), e);
        }
        List<Found> out = new ArrayList<>();
        for (Peripheral p : a.scanGetResults()) {
            String n = p.getIdentifier();
            if (n != null && !n.isEmpty()) out.add(new Found(p));
        }
        return out;
    }

    /**
     * Scans until a device whose name starts with {@code nameOrAddress} (case-insensitive)
     * or whose address equals it is found, then connects.
     */
    public static SimpleBleTransport connect(String nameOrAddress, int scanTimeoutMs) throws IOException {
        return connectFirst(List.of(nameOrAddress), scanTimeoutMs);
    }

    /** Like {@link #connect(String, int)}, matching any of several name prefixes / addresses. */
    public static SimpleBleTransport connectFirst(List<String> namesOrAddresses, int scanTimeoutMs) throws IOException {
        Adapter a = adapter();
        List<String> wants = new ArrayList<>();
        for (String w : namesOrAddresses) wants.add(w.toUpperCase(Locale.ROOT));
        AtomicReference<Peripheral> hit = new AtomicReference<>();
        CountDownLatch found = new CountDownLatch(1);
        a.setEventListener(new Adapter.EventListener() {
            @Override
            public void onScanFound(Peripheral p) {
                if (matchesAny(p, wants) && hit.compareAndSet(null, p)) found.countDown();
            }

            @Override
            public void onScanUpdated(Peripheral p) {
                onScanFound(p);
            }
        });
        try {
            a.scanStart();
            if (!found.await(scanTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("No Niimbot printer matching " + namesOrAddresses + " found in "
                        + scanTimeoutMs + " ms (is it on, and not connected to the phone app?)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while scanning", e);
        } finally {
            try { a.scanStop(); } catch (RuntimeException ignored) { }
            a.setEventListener(new Adapter.EventListener() { });
        }
        return open(hit.get());
    }

    /** Connects to a device returned by {@link #scan}. */
    public static SimpleBleTransport connect(Found f) throws IOException {
        return open(f.peripheral);
    }

    private static boolean matchesAny(Peripheral p, List<String> wants) {
        for (String w : wants) if (matches(p, w)) return true;
        return false;
    }

    private static boolean matches(Peripheral p, String want) {
        String n = p.getIdentifier();
        if (n != null && n.toUpperCase(Locale.ROOT).startsWith(want)) return true;
        String addr = String.valueOf(p.getAddress());
        return addr.equalsIgnoreCase(want);
    }

    private static SimpleBleTransport open(Peripheral p) throws IOException {
        SimpleBleTransport t = new SimpleBleTransport(p);
        p.setEventListener(new Peripheral.EventListener() {
            @Override
            public void onDisconnected() {
                if (!t.closing) t.onDisconnect.run();
            }
        });
        try {
            p.connect();
        } catch (RuntimeException e) {
            throw new IOException("BLE connect to " + t.name + " failed: " + e.getMessage(), e);
        }
        boolean hasChar = false;
        for (Service s : p.services()) {
            if (!s.uuid().equalsIgnoreCase(SERVICE_UUID)) continue;
            for (Characteristic c : s.characteristics()) {
                if (c.uuid().equalsIgnoreCase(CHAR_UUID)) {
                    hasChar = true;
                    t.canWriteCommand = c.canWriteCommand();
                }
            }
        }
        if (!hasChar) {
            t.close();
            throw new IOException(t.name + " does not expose the Niimbot service " + SERVICE_UUID);
        }
        try {
            p.notify(SVC, CHR, data -> t.receiver.accept(data));
        } catch (RuntimeException e) {
            t.close();
            throw new IOException("Could not subscribe to notifications: " + e.getMessage(), e);
        }
        return t;
    }

    @Override
    public void write(byte[] data, boolean withResponse) throws IOException {
        try {
            if (withResponse || !canWriteCommand) peripheral.writeRequest(SVC, CHR, data);
            else peripheral.writeCommand(SVC, CHR, data);
        } catch (RuntimeException e) {
            throw new IOException("BLE write failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void setReceiver(Consumer<byte[]> receiver) {
        this.receiver = receiver == null ? b -> { } : receiver;
    }

    @Override
    public boolean supportsWriteWithoutResponse() {
        return canWriteCommand;
    }

    /** Usable bytes per write: ATT MTU − 3, or 0 (unknown) if no larger MTU was negotiated. */
    @Override
    public int maxWriteSize() {
        try {
            int mtu = peripheral.getMtu();
            return mtu > 23 ? mtu - 3 : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public String deviceName() {
        return name;
    }

    @Override
    public boolean isConnected() {
        try {
            return peripheral.isConnected();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void setDisconnectListener(Runnable r) {
        this.onDisconnect = r == null ? () -> { } : r;
    }

    @Override
    public void close() {
        closing = true;
        try { peripheral.unsubscribe(SVC, CHR); } catch (RuntimeException ignored) { }
        try { if (peripheral.isConnected()) peripheral.disconnect(); } catch (RuntimeException ignored) { }
    }
}
