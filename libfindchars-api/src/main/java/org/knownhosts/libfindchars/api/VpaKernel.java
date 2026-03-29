package org.knownhosts.libfindchars.api;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD visibly pushdown automaton primitives using vector intrinsics.
 *
 * <p>Provides the two core operations for bounded VPA processing in the vector domain:
 * <ul>
 *   <li>{@link #prefixXor} — 1-bit stack (toggle state): CSV quotes, string literals</li>
 *   <li>{@link #prefixSum} — 8-bit depth stack: bracket nesting, XML depth</li>
 * </ul>
 *
 * <p>Both use the Hillis-Steele parallel prefix pattern:
 * {@code rearrange(shiftR(n), zero)} + {@code lanewise(op)} in log₂(vectorByteSize) steps.
 * Everything stays in {@link ByteVector} — no scalar bitmask escapes.
 *
 * <p>Composing both primitives handles visibly pushdown languages:
 * "filter by string state (prefixXor), then compute brace depth (prefixSum) on remaining."
 */
public final class VpaKernel {

    private VpaKernel() {}

    /**
     * Parallel prefix XOR — toggle state at each lane.
     *
     * <p>{@code result[i] = v[0] ^ v[1] ^ ... ^ v[i]}
     *
     * <p>Use for quote toggle: input 0xFF at quote positions, 0x00 elsewhere.
     * Result: 0xFF where inside quotes, 0x00 where outside.
     *
     * @param v    ByteVector with 0xFF at toggle positions, 0x00 elsewhere
     * @param zero broadcast(0) vector for zero-filling shifted lanes
     * @param sp   vector species
     * @return prefix XOR scan result
     */
    @Inline
    public static ByteVector prefixXor(ByteVector v, ByteVector zero, VectorSpecies<Byte> sp) {
        var r = v;
        r = r.lanewise(VectorOperators.XOR, r.rearrange(shiftR(sp, 1), zero));
        r = r.lanewise(VectorOperators.XOR, r.rearrange(shiftR(sp, 2), zero));
        r = r.lanewise(VectorOperators.XOR, r.rearrange(shiftR(sp, 4), zero));
        r = r.lanewise(VectorOperators.XOR, r.rearrange(shiftR(sp, 8), zero));
        r = r.lanewise(VectorOperators.XOR, r.rearrange(shiftR(sp, 16), zero));
        if (sp.vectorByteSize() > 32)
            r = r.lanewise(VectorOperators.XOR, r.rearrange(shiftR(sp, 32), zero));
        return r;
    }

    /**
     * Parallel prefix SUM — cumulative depth at each lane.
     *
     * <p>{@code result[i] = v[0] + v[1] + ... + v[i]}
     *
     * <p>Use for bracket nesting: input +1 at '{' positions, -1 at '}' positions, 0 elsewhere.
     * Result: nesting depth at each position (signed byte, range -128 to +127).
     *
     * @param v    ByteVector with depth deltas (+1, -1, 0)
     * @param zero broadcast(0) vector for zero-filling shifted lanes
     * @param sp   vector species
     * @return prefix sum scan result
     */
    @Inline
    public static ByteVector prefixSum(ByteVector v, ByteVector zero, VectorSpecies<Byte> sp) {
        var r = v;
        r = r.add(r.rearrange(shiftR(sp, 1), zero));
        r = r.add(r.rearrange(shiftR(sp, 2), zero));
        r = r.add(r.rearrange(shiftR(sp, 4), zero));
        r = r.add(r.rearrange(shiftR(sp, 8), zero));
        r = r.add(r.rearrange(shiftR(sp, 16), zero));
        if (sp.vectorByteSize() > 32)
            r = r.add(r.rearrange(shiftR(sp, 32), zero));
        return r;
    }

    /**
     * Shift-right shuffle: lane i reads from lane i-n, first n lanes zero-filled.
     *
     * <p>Out-of-range indices (i &lt; n) pick from the fallback vector (zero)
     * when used with {@link ByteVector#rearrange(VectorShuffle, ByteVector)}.
     *
     * <p>Uses {@link VectorShuffle#iota} instead of {@code fromOp} to avoid
     * lambda synthetic methods that cause {@code IllegalAccessError} when
     * inlined into hidden classes by {@code BytecodeInliner}.
     *
     * @param sp vector species
     * @param n  number of lanes to shift right
     * @return shuffle mapping lane i to source lane i-n
     */
    @Inline
    public static VectorShuffle<Byte> shiftR(VectorSpecies<Byte> sp, int n) {
        // iota(species, start=-n, step=1, wrap=false): maps lane i → source lane i-n;
        // out-of-range lanes (i < n) become exceptional indices, filled from fallback vector
        return VectorShuffle.iota(sp, -n, 1, false);
    }
}
