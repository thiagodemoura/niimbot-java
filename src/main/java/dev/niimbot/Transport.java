package dev.niimbot;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * The byte pipe to the printer. {@link dev.niimbot.ble.SimpleBleTransport} implements it
 * over BLE GATT; tests use an in-memory fake. Anything that can move bytes both ways
 * (for example a Bluetooth-classic serial port) can implement it too.
 */
public interface Transport extends AutoCloseable {

    /**
     * Writes raw bytes. {@code withResponse = true} asks for an acknowledged write
     * (GATT Write Request); false is an unacknowledged write (Write Command).
     * Implementations without the distinction can ignore the flag.
     */
    void write(byte[] data, boolean withResponse) throws IOException;

    /** Sets the receiver for incoming bytes (BLE notifications). Called from any thread. */
    void setReceiver(Consumer<byte[]> receiver);

    /** Whether unacknowledged writes are available. */
    default boolean supportsWriteWithoutResponse() {
        return true;
    }

    /** Max bytes in one write, or 0 if unknown. Used to size frame bundles. */
    default int maxWriteSize() {
        return 0;
    }

    /** Advertised device name, if known. */
    default String deviceName() {
        return null;
    }

    boolean isConnected();

    /** Called when the link drops on its own (printer powered down, out of range…). */
    default void setDisconnectListener(Runnable r) {
    }

    @Override
    void close();
}
