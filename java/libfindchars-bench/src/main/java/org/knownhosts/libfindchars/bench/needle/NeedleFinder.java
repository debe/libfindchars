package org.knownhosts.libfindchars.bench.needle;

import java.lang.foreign.MemorySegment;

/**
 * Exact single-needle search that is sublinear on the average case and linear in the worst.
 *
 * <p>Dispatches on needle length, because no single algorithm wins across the range &mdash;
 * Faro and Lecroq's survey finds different winners per band, and a 2025 re-benchmark across
 * 107 variants found no overall winner:</p>
 *
 * <table border="1">
 * <caption>Strategy by needle length</caption>
 * <tr><th>m</th><th>strategy</th></tr>
 * <tr><td>0</td><td>trivial</td></tr>
 * <tr><td>&le; {@value #SHORT_NEEDLE}</td><td>{@link SimdScan} &mdash; nothing worth skipping</td></tr>
 * <tr><td>&gt; {@value #SHORT_NEEDLE}</td><td>{@link QGramFilter} &mdash; stride-sampled, skips unread</td></tr>
 * <tr><td>any</td><td>budget exceeded, or filter unsuitable &rarr; {@link TwoWay}</td></tr>
 * </table>
 *
 * <p>Not thread-safe: {@link #stats()} is mutated per call. Build once per needle and reuse
 * across haystacks &mdash; construction is O(m).</p>
 */
public final class NeedleFinder {

    /** Verified bytes allowed before abandoning the filter, as a multiple of haystack size. */
    private static final int VERIFY_BUDGET_FACTOR = 4;

    private final byte[] needle;
    private final QGramFilter filter;
    private final SimdScan simdScan;
    private final TwoWay twoWay;
    private final QGramFilter.Stats stats = new QGramFilter.Stats();

    private NeedleFinder(byte[] needle, QGramFilter filter, SimdScan simdScan, TwoWay twoWay) {
        this.needle = needle;
        this.filter = filter;
        this.simdScan = simdScan;
        this.twoWay = twoWay;
    }

    public static NeedleFinder of(byte[] needle) {
        byte[] copy = needle.clone();
        if (copy.length == 0) {
            return new NeedleFinder(copy, null, null, null);
        }
        return new NeedleFinder(copy, QGramFilter.build(copy), new SimdScan(copy), new TwoWay(copy));
    }

    /** Offset of the leftmost occurrence in {@code data}, or {@code -1}. */
    public long find(MemorySegment data) {
        long size = data.byteSize();
        stats.reset();
        if (needle.length == 0) {
            return 0;
        }
        if (needle.length > size) {
            return -1;
        }
        long budget = VERIFY_BUDGET_FACTOR * size;
        long hit = filter != null
                ? filter.find(data, size, stats, budget)
                : simdScan.find(data, size, stats, budget);
        if (hit >= 0) {
            return hit;
        }
        return stats.abortedAt < 0 ? -1 : twoWay.find(data, stats.abortedAt, size);
    }

    /** Which strategy {@link #find} will use, for benchmarks and tests. */
    public String strategy() {
        if (needle.length == 0) {
            return "trivial";
        }
        return filter != null ? "qgram-stride" : "simd-scan";
    }

    QGramFilter.Stats stats() {
        return stats;
    }

    // --- direct strategy access, so benchmarks can compare them on identical input ---

    static long findQGram(QGramFilter f, MemorySegment data, QGramFilter.Stats stats) {
        return f.find(data, data.byteSize(), stats, Long.MAX_VALUE);
    }

    static QGramFilter filterFor(byte[] needle) {
        return QGramFilter.build(needle);
    }

    static SimdScan simdScanFor(byte[] needle) {
        return new SimdScan(needle);
    }

    static TwoWay twoWayFor(byte[] needle) {
        return new TwoWay(needle);
    }
}
