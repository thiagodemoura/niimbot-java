package dev.niimbot;

import java.io.IOException;

/** Any failure talking to the printer. */
public class NiimbotException extends IOException {
    private static final long serialVersionUID = 1L;

    public NiimbotException(String message) {
        super(message);
    }

    public NiimbotException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The job ran but the printer never confirmed it (a PageEnd went unacknowledged,
     * rows were lost, or the printed-page counter stalled). PrintEnd has already been
     * sent, so the paper is fed out — check the labels: some may have printed, blank or short.
     */
    public static class PrintNotConfirmed extends NiimbotException {
        private static final long serialVersionUID = 1L;

        public PrintNotConfirmed(String reason) {
            super("print not confirmed: " + reason + ". PrintEnd was sent, so the paper has been fed out"
                    + " — check the labels, they may be blank, short or repeated.");
        }
    }
}
