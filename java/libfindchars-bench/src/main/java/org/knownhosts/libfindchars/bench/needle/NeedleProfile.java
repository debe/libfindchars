package org.knownhosts.libfindchars.bench.needle;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * Prints bytes-read alongside wall time. Wall time alone hides the entire point of the
 * stride filter, which is the haystack bytes it never touches.
 *
 * <p>Timings here are a coarse min-of-N with a fixed warmup, not a JMH measurement &mdash;
 * see {@code NeedleBenchmark} for numbers to actually quote.</p>
 */
public final class NeedleProfile {

    private static final int WARMUP = 20;
    private static final int REPS = 50;

    public static void main(String[] args) {
        System.out.printf("%-8s %-7s %-14s %12s %12s %10s %10s%n",
                "data", "m", "strategy", "time_us", "bytes_read", "probes", "verifies");
        System.out.println("-".repeat(84));
        for (String kind : new String[]{"lemire", "random", "text"}) {
            for (int m : new int[]{16, 256, 4096, 65536}) {
                run(kind, m);
            }
        }
        System.out.println();
        System.out.println("indexOf comparison (same inputs, String domain):");
        System.out.printf("%-8s %-7s %12s%n", "data", "m", "time_us");
        System.out.println("-".repeat(30));
        for (String kind : new String[]{"lemire", "random", "text"}) {
            for (int m : new int[]{16, 256, 4096}) {
                runIndexOf(kind, m);
            }
        }
    }

    private static void run(String kind, int m) {
        var c = Datasets.of(kind, m);
        var data = MemorySegment.ofArray(c.hay());
        var finder = NeedleFinder.of(c.needle());

        for (int i = 0; i < WARMUP; i++) {
            check(finder.find(data), c);
        }
        long best = Long.MAX_VALUE;
        for (int i = 0; i < REPS; i++) {
            long t0 = System.nanoTime();
            long r = finder.find(data);
            long dt = System.nanoTime() - t0;
            check(r, c);
            best = Math.min(best, dt);
        }
        var s = finder.stats();
        System.out.printf("%-8s %-7d %-14s %12.1f %12d %10d %10d%n",
                kind, m, finder.strategy(), best / 1000.0,
                s.bytesRead(), s.probes, s.verifies);
    }

    private static void runIndexOf(String kind, int m) {
        var c = Datasets.of(kind, m);
        String hay = new String(c.hay(), StandardCharsets.US_ASCII);
        String needle = new String(c.needle(), StandardCharsets.US_ASCII);

        int warm = m >= 4096 && kind.equals("lemire") ? 1 : WARMUP;
        int reps = m >= 4096 && kind.equals("lemire") ? 3 : REPS;
        for (int i = 0; i < warm; i++) {
            hay.indexOf(needle);
        }
        long best = Long.MAX_VALUE;
        for (int i = 0; i < reps; i++) {
            long t0 = System.nanoTime();
            long r = hay.indexOf(needle);
            long dt = System.nanoTime() - t0;
            if (r != c.expected()) {
                throw new AssertionError("indexOf disagreed: " + r + " != " + c.expected());
            }
            best = Math.min(best, dt);
        }
        System.out.printf("%-8s %-7d %12.1f%n", kind, m, best / 1000.0);
    }

    private static void check(long got, Datasets.Case c) {
        if (got != c.expected()) {
            throw new AssertionError(c.name() + " m=" + c.needle().length
                    + ": got " + got + " expected " + c.expected());
        }
    }
}
