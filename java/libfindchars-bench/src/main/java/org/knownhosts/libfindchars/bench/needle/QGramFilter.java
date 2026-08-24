package org.knownhosts.libfindchars.bench.needle;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * Stride-sampled q-gram filter &mdash; sublinear exact search for long needles.
 *
 * <h2>Why this exists</h2>
 * Yao's bound says exact matching examines only &Omega;(n&nbsp;&middot;&nbsp;log<sub>&sigma;</sub>(m)&nbsp;/&nbsp;m)
 * characters on average. For a 1 MB haystack, a 4096-byte needle and a 256-byte alphabet
 * that is roughly <b>384 bytes out of 1,048,576</b>. Every full-scan design &mdash;
 * {@code String.indexOf}, Two-Way, KMP, a SIMD rare-byte prefilter &mdash; is about three
 * orders of magnitude above that floor. SIMD makes reading everything fast; it does not make
 * reading everything necessary.
 *
 * <h2>The coverage argument (this is the correctness proof)</h2>
 * An occurrence starting at {@code s} spans {@code [s, s+m)}, so the q-grams entirely inside
 * it start at the {@code m-q+1} positions {@code [s, s+m-q]}. Probing at a stride of exactly
 * {@code S = m-q+1} therefore hits <b>every occurrence exactly once</b>:
 * <pre>
 *   probe k sits at  p_k = (m-q) + k*S
 *   probe k covers   s in [k*S, (k+1)*S - 1]
 * </pre>
 * Those ranges tile the haystack: disjoint, contiguous, increasing. So if the q-gram at a
 * probe point is not one of the needle's, no occurrence can cover it and {@code S} bytes are
 * skipped unread; and the first probe that yields a match yields the leftmost one.
 *
 * <h2>Fixed stride, not a skip loop</h2>
 * Boyer-Moore-Horspool and HASHq derive the <em>next</em> probe address from the
 * <em>current</em> lookup &mdash; a serial load&rarr;hash&rarr;load chain, and at a
 * 4096-byte stride every probe lands on a fresh page, so a TLB miss per link. A fixed stride
 * makes every probe address known in advance, so the loads are independent and can overlap in
 * the out-of-order window.
 *
 * <p>Structurally this is the SSEF family (K&uuml;lekci, PSC 2009), which Faro and Lecroq
 * found best for very long patterns. SSEF filters on a 16-bit signature built from the top
 * bit of each of 16 bytes; that signature is identically zero on ASCII text, so this
 * implementation uses a 64-bit q-gram instead.</p>
 */
final class QGramFilter {

    /** Bytes per probe. One unaligned 64-bit load. */
    static final int Q = 8;

    /**
     * Reject the filter when any single q-gram repeats more than this often in the needle.
     * A hit on a q-gram occurring at {@code r} needle offsets costs {@code r} alignment
     * trials, so this directly bounds per-hit work. It also subsumes a periodicity check:
     * {@code "a".repeat(4096)} has one q-gram at 4089 offsets and is rejected here, landing
     * on Two-Way, which handles periodic needles optimally anyway.
     */
    private static final int MAX_GRAM_REPEAT = 8;

    /** Below this stride the filter reads too much of the haystack to be worth it. */
    private static final int MIN_STRIDE = 16;

    private final byte[] needle;
    private final int m;
    private final int stride;
    private final int firstProbe;
    private final long[] keys;
    private final int[] offs;
    private final int mask;

    private QGramFilter(byte[] needle, int firstProbe, int stride, long[] keys, int[] offs, int mask) {
        this.needle = needle;
        this.m = needle.length;
        this.firstProbe = firstProbe;
        this.stride = stride;
        this.keys = keys;
        this.offs = offs;
        this.mask = mask;
    }

    /**
     * Builds the filter, or returns {@code null} when this needle cannot support a useful
     * stride.
     *
     * <p>The stride cannot simply be {@code m-q+1}: a repetitive needle has q-grams occurring
     * at hundreds of offsets, and a hit on one of those costs an alignment trial per offset.
     * Dropping those offsets is not allowed either &mdash; a probe can land at any phase, so
     * every offset the grid can produce must be resolvable.</p>
     *
     * <p>The resolution is to keep the longest <em>run</em> of consecutive low-multiplicity
     * offsets, {@code [a, a+L)}, and use {@code L} as the stride. A run of {@code L}
     * consecutive integers hits every residue class mod {@code L}, so probing at stride
     * {@code L} from {@code p0 = a} still covers every occurrence exactly once, while every
     * table hit stays bounded by {@link #MAX_GRAM_REPEAT} alignment trials.</p>
     *
     * <p>Needles with no such run fall out here and are handled by a full scan instead.
     * {@code "a".repeat(4095) + "b"} is exactly that case: its only informative q-gram is the
     * single one containing {@code 'b'}, so {@code L = 1} and strided sampling cannot work
     * &mdash; but a rare-byte scan for {@code 'b'} rules out the whole haystack in one pass.</p>
     */
    static QGramFilter build(byte[] needle) {
        int m = needle.length;
        if (m < Q + MIN_STRIDE) {
            return null;
        }
        int count = m - Q + 1;
        MemorySegment ns = MemorySegment.ofArray(needle);

        // Pass 1: multiplicity of each distinct q-gram.
        int cap = 16;
        while (cap < count * 2) {
            cap <<= 1;
        }
        int cmask = cap - 1;
        long[] ck = new long[cap];
        int[] cv = new int[cap];
        for (int j = 0; j < count; j++) {
            long g = ns.get(ValueLayout.JAVA_LONG_UNALIGNED, j);
            int i = (int) (mix(g) & cmask);
            while (cv[i] != 0 && ck[i] != g) {
                i = (i + 1) & cmask;
            }
            ck[i] = g;
            cv[i]++;
        }

        // Pass 2: longest run of consecutive low-multiplicity offsets.
        int bestStart = -1;
        int bestLen = 0;
        int runStart = 0;
        int runLen = 0;
        for (int j = 0; j < count; j++) {
            long g = ns.get(ValueLayout.JAVA_LONG_UNALIGNED, j);
            int i = (int) (mix(g) & cmask);
            while (ck[i] != g) {
                i = (i + 1) & cmask;
            }
            if (cv[i] <= MAX_GRAM_REPEAT) {
                if (runLen == 0) {
                    runStart = j;
                }
                runLen++;
                if (runLen > bestLen) {
                    bestLen = runLen;
                    bestStart = runStart;
                }
            } else {
                runLen = 0;
            }
        }
        if (bestLen < MIN_STRIDE) {
            return null;
        }

        // Table holds only the chosen run.
        int tcap = 16;
        while (tcap < bestLen * 2) {
            tcap <<= 1;
        }
        int tmask = tcap - 1;
        long[] keys = new long[tcap];
        int[] offs = new int[tcap];
        Arrays.fill(offs, -1);
        for (int j = bestStart; j < bestStart + bestLen; j++) {
            long g = ns.get(ValueLayout.JAVA_LONG_UNALIGNED, j);
            int i = (int) (mix(g) & tmask);
            while (offs[i] != -1) {
                i = (i + 1) & tmask;
            }
            keys[i] = g;
            offs[i] = j;
        }
        return new QGramFilter(needle, bestStart, bestLen, keys, offs, tmask);
    }

    /**
     * Leftmost occurrence, or {@code -1}. When the verification budget is exhausted the scan
     * stops and records in {@code stats.abortedAt} the first haystack offset it did
     * <em>not</em> rule out, so the caller can finish with Two-Way from there.
     */
    long find(MemorySegment data, long size, Stats stats, long verifyBudget) {
        long p = firstProbe;
        while (p + Q <= size) {
            long windowStart = Math.max(0, p - (firstProbe + stride - 1));
            if (stats.verifiedBytes > verifyBudget) {
                stats.abortedAt = windowStart;
                return -1;
            }
            stats.probes++;

            long g = data.get(ValueLayout.JAVA_LONG_UNALIGNED, p);
            int idx = (int) (mix(g) & mask);
            long best = -1;
            while (offs[idx] != -1) {
                if (keys[idx] == g) {
                    long s = p - offs[idx];
                    if (s >= 0 && s + m <= size && (best < 0 || s < best)) {
                        stats.verifies++;
                        // Charge only the bytes actually compared: the verifier exits on the
                        // first mismatch, and the budget must bound real work, not worst case.
                        int mismatch = NeedleVerifier.firstMismatch(data, s, needle, 0, m);
                        stats.verifiedBytes += Math.min(mismatch + 1, m);
                        if (mismatch == m) {
                            best = s;
                        }
                    }
                }
                idx = (idx + 1) & mask;
            }
            if (best >= 0) {
                return best;
            }
            p += stride;
        }
        return -1;
    }

    int stride() {
        return stride;
    }

    int firstProbe() {
        return firstProbe;
    }

    /** splitmix64 finalizer &mdash; the q-gram is raw text, so it needs a real mix. */
    private static long mix(long z) {
        z ^= z >>> 33;
        z *= 0xff51afd7ed558ccdL;
        z ^= z >>> 33;
        z *= 0xc4ceb9fe1a85ec53L;
        z ^= z >>> 33;
        return z;
    }

    /** Instrumentation: the point of this design is bytes <em>not</em> read. */
    static final class Stats {
        long probes;
        long verifies;
        long verifiedBytes;
        long scannedBytes;
        long abortedAt = -1;

        void reset() {
            probes = 0;
            verifies = 0;
            verifiedBytes = 0;
            scannedBytes = 0;
            abortedAt = -1;
        }

        /** Haystack bytes actually touched by the probe loop. */
        long bytesProbed() {
            return probes * Q;
        }

        /** Every haystack byte this search looked at, however it looked at it. */
        long bytesRead() {
            return bytesProbed() + scannedBytes + verifiedBytes;
        }
    }
}
