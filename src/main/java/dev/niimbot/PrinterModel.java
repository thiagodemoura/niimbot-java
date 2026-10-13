package dev.niimbot;

import java.util.List;
import java.util.Locale;

/**
 * Known Niimbot models, ported from {@code registry.json} and the {@code MODEL_IDS}
 * table of niimbot-web-bluetooth (iscarelli, MIT).
 *
 * <ul>
 *   <li>{@code task} — which print-command sequence the printer speaks ({@link Task#V4} or {@link Task#B1}).
 *       It is independent of dpi.</li>
 *   <li>{@code paced} — needs a ~10 ms gap between unacknowledged row writes (it drops rows on a burst).</li>
 *   <li>{@code bundle} — accepts several row frames concatenated in one BLE write.</li>
 *   <li>{@code pagesPerJob} — 1 means the printer acks multi-page jobs but only prints the first page,
 *       so the driver splits copies/batches into separate jobs.</li>
 * </ul>
 *
 * Validated on real hardware upstream: B1, B1 Pro, M2-H, D11_H, D110, N1, B2 Pro.
 * Not printed upstream: B1 SE, D110_M.
 */
public enum PrinterModel {
    B1     (4096, "Niimbot B1",     Task.B1, 203, true,  true,  0, List.of("B1")),
    B1_PRO (4097, "Niimbot B1 Pro", Task.V4, 300, false, false, 0, List.of("B1")),
    B1_SE  (4098, "Niimbot B1 SE",  Task.B1, 203, true,  false, 0, List.of("B1")),
    B2_PRO (6912, "Niimbot B2 Pro", Task.V4, 300, false, false, 0, List.of("B2")),
    M2_H   (4608, "Niimbot M2-H",   Task.B1, 300, false, true,  0, List.of("M2")),
    D11_H  (528,  "Niimbot D11_H",  Task.V4, 300, false, true,  0, List.of("D11")),
    D110   (2304, "Niimbot D110",   Task.B1, 203, true,  false, 1, List.of("D110")),
    D110_M (2320, "Niimbot D110_M", Task.V4, 203, true,  false, 1, List.of("D110")),
    N1     (3586, "Niimbot N1",     Task.B1, 203, true,  false, 1, List.of("N1"));

    /** Print-task variant: the command sequence used inside a job. */
    public enum Task { V4, B1 }

    public final int id;
    public final String label;
    public final Task task;
    public final int dpi;
    public final boolean paced;
    public final boolean bundle;
    /** 0 = unlimited; 1 = one page per job. */
    public final int pagesPerJob;
    /** Advertised BLE name prefixes. */
    public final List<String> namePrefixes;
    /** Registry defaults (same for every model upstream). */
    public final int defaultDensity = 3;
    public final int labelType = 1;
    public final int speed = 1;

    PrinterModel(int id, String label, Task task, int dpi, boolean paced, boolean bundle,
                 int pagesPerJob, List<String> namePrefixes) {
        this.id = id;
        this.label = label;
        this.task = task;
        this.dpi = dpi;
        this.paced = paced;
        this.bundle = bundle;
        this.pagesPerJob = pagesPerJob;
        this.namePrefixes = namePrefixes;
    }

    public static PrinterModel byId(int id) {
        for (PrinterModel m : values()) if (m.id == id) return m;
        return null;
    }

    /** Accepts enum names and loose spellings: "b1pro", "B1_PRO", "d11h", "m2-h"… */
    public static PrinterModel parse(String s) {
        String k = s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        for (PrinterModel m : values()) {
            if (m.name().replace("_", "").equals(k)) return m;
        }
        throw new IllegalArgumentException("Unknown model '" + s + "'. Known: " + java.util.Arrays.toString(values()));
    }

    @Override
    public String toString() {
        return name() + " (" + label + ", id " + id + ", task " + task + ", " + dpi + " dpi)";
    }
}
