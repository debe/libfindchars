package org.knownhosts.libfindchars.bench.needle;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Rare-byte-pair SIMD full scan &mdash; the memchr/ripgrep strategy, and the baseline the
 * q-gram filter is measured against.
 *
 * <p>Reads every byte of the haystack at vector width, keeping positions where two chosen
 * needle bytes both line up, then verifies. This is the right strategy for <em>short</em>
 * needles, where there is nothing to skip. For a 4 KB needle it is roughly three orders of
 * magnitude above Yao's bound, which is the entire point of {@link QGramFilter}.</p>
 */
final class SimdScan {

    private static final VectorSpecies<Byte> SP = ByteVector.SPECIES_PREFERRED;

    /**
     * Rough background byte frequency (higher = more common), used to pick anchors that are
     * unlikely to occur in the haystack. Mirrors memchr's {@code HeuristicFrequencyRank}
     * idea: what matters is being rare in the <em>text</em>, not in the needle.
     */
    private static final int[] RANK = new int[256];

    static {
        java.util.Arrays.fill(RANK, 1);
        // Printable ASCII is common in the workloads this targets.
        for (int i = 0x20; i < 0x7F; i++) {
            RANK[i] = 30;
        }
        // Letters ranked by actual English frequency, most common first. This ordering is
        // what lets the scan pick 'b' over 'a' in Lemire's needle.
        String byFrequency = "etaoinshrdlucmfwypvbgkjqxz";
        for (int i = 0; i < byFrequency.length(); i++) {
            int score = 250 - i * 8;
            char lower = byFrequency.charAt(i);
            RANK[lower] = score;
            RANK[Character.toUpperCase(lower)] = Math.max(2, score / 2);
        }
        RANK[' '] = 255;
        RANK['\n'] = 90;
        RANK['\r'] = 45;
        RANK['\t'] = 45;
        for (int i = '0'; i <= '9'; i++) {
            RANK[i] = 120;
        }
    }

    private final byte[] needle;
    private final int m;
    private final int i0;
    private final int i1;
    private final byte b0;
    private final byte b1;
    private final int delta;

    SimdScan(byte[] needle) {
        this.needle = needle;
        this.m = needle.length;
        int a = 0;
        int b = m > 1 ? 1 : 0;
        if (m > 1) {
            // Rarest byte, then the rarest other position.
            a = 0;
            for (int i = 1; i < m; i++) {
                if (RANK[needle[i] & 0xFF] < RANK[needle[a] & 0xFF]) {
                    a = i;
                }
            }
            b = -1;
            for (int i = 0; i < m; i++) {
                if (i != a && (b < 0 || RANK[needle[i] & 0xFF] < RANK[needle[b] & 0xFF])) {
                    b = i;
                }
            }
            if (a > b) {
                int t = a;
                a = b;
                b = t;
            }
        }
        this.i0 = a;
        this.i1 = b;
        this.b0 = needle[a];
        this.b1 = needle[b];
        this.delta = b - a;
    }

    /** Leftmost occurrence, or {@code -1}. Unbudgeted; for tests. */
    long find(MemorySegment data, long size) {
        return find(data, size, new QGramFilter.Stats(), Long.MAX_VALUE);
    }

    /**
     * Leftmost occurrence, or {@code -1}. This scan is O(n&middot;m) in the worst case just
     * like {@code String.indexOf}, so it carries the same budget escape hatch as the q-gram
     * filter: on exhaustion it records the first offset it did not rule out and the caller
     * finishes with Two-Way.
     */
    long find(MemorySegment data, long size, QGramFilter.Stats stats, long verifyBudget) {
        if (m == 0) {
            return 0;
        }
        if (m > size) {
            return -1;
        }
        int lanes = SP.length();
        long lastAnchor = size - m + i1;
        long pos = i1;
        while (pos + lanes <= size && pos <= lastAnchor) {
            var v1 = ByteVector.fromMemorySegment(SP, data, pos, ByteOrder.nativeOrder());
            var v0 = ByteVector.fromMemorySegment(SP, data, pos - delta, ByteOrder.nativeOrder());
            long bits = v1.compare(VectorOperators.EQ, b1)
                    .and(v0.compare(VectorOperators.EQ, b0))
                    .toLong();
            while (bits != 0) {
                long anchor = pos + Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                if (anchor > lastAnchor) {
                    break;
                }
                long s = anchor - i1;
                stats.verifies++;
                int mismatch = NeedleVerifier.firstMismatch(data, s, needle, 0, m);
                stats.verifiedBytes += Math.min(mismatch + 1, m);
                if (mismatch == m) {
                    stats.scannedBytes = pos - i1 + lanes;
                    return s;
                }
            }
            pos += lanes;
            if (stats.verifiedBytes > verifyBudget) {
                stats.scannedBytes = pos;
                stats.abortedAt = Math.max(0, pos - i1);
                return -1;
            }
        }
        for (; pos <= lastAnchor; pos++) {
            if (data.get(ValueLayout.JAVA_BYTE, pos) == b1
                    && data.get(ValueLayout.JAVA_BYTE, pos - delta) == b0) {
                long s = pos - i1;
                stats.verifies++;
                int mismatch = NeedleVerifier.firstMismatch(data, s, needle, 0, m);
                stats.verifiedBytes += Math.min(mismatch + 1, m);
                if (mismatch == m) {
                    stats.scannedBytes = pos;
                    return s;
                }
            }
        }
        stats.scannedBytes = size;
        return -1;
    }
}
