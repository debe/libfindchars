package org.knownhosts.libfindchars.bench.needle;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * Crochemore-Perrin two-way string matching: O(n) worst case, O(1) extra space.
 *
 * <p>Port of musl's {@code twoway_memmem}, including its Boyer-Moore-Horspool style outer
 * skip table, with the two half-comparisons vectorized via {@link NeedleVerifier}.</p>
 *
 * <p>This is the worst-case backstop for {@link NeedleFinder}. It reads (close to) every
 * byte of the haystack, which is why it is the fallback and not the primary strategy for
 * long needles &mdash; see the class javadoc on {@link QGramFilter}.</p>
 */
final class TwoWay {

    private final byte[] n;
    private final int l;
    private final int[] last = new int[256];
    private final int ms;
    private final int period;
    private final int mem0;

    TwoWay(byte[] needle) {
        this.n = needle;
        this.l = needle.length;
        Arrays.fill(last, -1);
        for (int i = 0; i < l; i++) {
            last[n[i] & 0xFF] = i;
        }

        int msLe = maximalSuffix(false);
        int perLe = lastPeriod;
        int msGe = maximalSuffix(true);
        int perGe = lastPeriod;

        int msSel;
        int perSel;
        if (msGe + 1 > msLe + 1) {
            msSel = msGe;
            perSel = perGe;
        } else {
            msSel = msLe;
            perSel = perLe;
        }
        this.ms = msSel;

        // Periodic needle? n[0..ms] == n[per..per+ms]
        boolean periodic = perSel + msSel < l;
        if (periodic) {
            for (int i = 0; i <= msSel; i++) {
                if (n[i] != n[i + perSel]) {
                    periodic = false;
                    break;
                }
            }
        }
        if (periodic) {
            this.period = perSel;
            this.mem0 = l - perSel;
        } else {
            this.period = Math.max(msSel, l - msSel - 1) + 1;
            this.mem0 = 0;
        }
    }

    private int lastPeriod;

    /** Maximal suffix of the needle under {@code <=} (or {@code >=} when reversed). */
    private int maximalSuffix(boolean reverse) {
        int ip = -1;
        int jp = 0;
        int k = 1;
        int p = 1;
        while (jp + k < l) {
            int a = n[ip + k] & 0xFF;
            int b = n[jp + k] & 0xFF;
            if (a == b) {
                if (k == p) {
                    jp += p;
                    k = 1;
                } else {
                    k++;
                }
            } else if (reverse ? a < b : a > b) {
                jp += k;
                k = 1;
                p = jp - ip;
            } else {
                ip = jp++;
                k = 1;
                p = 1;
            }
        }
        lastPeriod = p;
        return ip;
    }

    /** Leftmost occurrence at or after {@code from}, or {@code -1}. */
    long find(MemorySegment data, long from, long size) {
        if (l == 0) {
            return from;
        }
        long h = from;
        int mem = 0;
        while (size - h >= l) {
            int c = data.get(ValueLayout.JAVA_BYTE, h + l - 1) & 0xFF;
            int shift = l - 1 - last[c];
            if (shift != 0) {
                h += Math.max(shift, mem);
                mem = 0;
                continue;
            }

            int k = NeedleVerifier.firstMismatch(data, h, n, Math.max(ms + 1, mem), l);
            if (k < l) {
                h += k - ms;
                mem = 0;
                continue;
            }

            k = NeedleVerifier.lastMismatch(data, h, n, mem, ms + 1) + 1;
            if (k <= mem) {
                return h;
            }
            h += period;
            mem = mem0;
        }
        return -1;
    }
}
