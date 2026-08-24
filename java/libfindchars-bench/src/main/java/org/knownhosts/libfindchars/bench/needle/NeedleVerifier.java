package org.knownhosts.libfindchars.bench.needle;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Vectorized needle/haystack comparison, shared by every strategy in this package.
 *
 * <p>Two-Way uses {@link #firstMismatch} and {@link #lastMismatch} as its comparison
 * primitive rather than byte-at-a-time loops: its outer skip loop is an inherently serial
 * recurrence, but the comparisons inside one alignment vectorize freely. That is exactly the
 * split Ben-Kiki et al. exploit to reach O(n/&alpha; + m/&alpha;) in
 * <em>Towards optimal packed string matching</em> (TCS 525, 2014).</p>
 */
final class NeedleVerifier {

    private static final VectorSpecies<Byte> SP = ByteVector.SPECIES_PREFERRED;

    private NeedleVerifier() {
    }

    /** True if {@code data[start, start+needle.length)} equals {@code needle}. */
    static boolean matches(MemorySegment data, long start, byte[] needle) {
        return firstMismatch(data, start, needle, 0, needle.length) == needle.length;
    }

    /**
     * Index of the first mismatch in {@code [from, to)} between {@code needle} and
     * {@code data[base+from, base+to)}, or {@code to} when the whole range is equal.
     */
    static int firstMismatch(MemorySegment data, long base, byte[] needle, int from, int to) {
        int lanes = SP.length();
        int k = from;
        for (; k + lanes <= to; k += lanes) {
            var a = ByteVector.fromMemorySegment(SP, data, base + k, ByteOrder.nativeOrder());
            var b = ByteVector.fromArray(SP, needle, k);
            var ne = a.compare(VectorOperators.NE, b);
            if (ne.anyTrue()) {
                return k + ne.firstTrue();
            }
        }
        while (k < to && needle[k] == data.get(ValueLayout.JAVA_BYTE, base + k)) {
            k++;
        }
        return k;
    }

    /**
     * Index of the last mismatch in {@code [from, to)}, scanning backwards, or
     * {@code from - 1} when the whole range is equal.
     */
    static int lastMismatch(MemorySegment data, long base, byte[] needle, int from, int to) {
        int lanes = SP.length();
        int k = to;
        for (; k - lanes >= from; k -= lanes) {
            var a = ByteVector.fromMemorySegment(SP, data, base + k - lanes, ByteOrder.nativeOrder());
            var b = ByteVector.fromArray(SP, needle, k - lanes);
            var ne = a.compare(VectorOperators.NE, b);
            if (ne.anyTrue()) {
                return k - lanes + ne.lastTrue();
            }
        }
        while (k > from && needle[k - 1] == data.get(ValueLayout.JAVA_BYTE, base + k - 1)) {
            k--;
        }
        return k - 1;
    }
}
