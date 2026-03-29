package org.knownhosts.libfindchars.api;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;


/**
 * Static helper methods containing the actual SIMD logic for UTF-8 engines.
 * Each method is small enough for C2 to inline from the calling hot loop.
 *
 * <p>Uses {@code .add(v, mask)} instead of {@code .blend(v, mask)} for conditional
 * accumulation. Both are semantically equivalent here because Z3-solved literals
 * guarantee non-overlapping positions (accumulator is always zero where mask is true).
 * On AVX2, {@code add(v, mask)} avoids the scalar {@code bOpTemplate} fallback that
 * {@code blend} triggers in some code paths.
 */
public final class Utf8Kernel {

    private Utf8Kernel() {}

    // --- Core shuffle/cleanLit primitives (public for template use) ---

    /**
     * Apply low/high nibble shuffle mask lookup to produce the raw match vector.
     * This is the common first step of every group application.
     */
    @Inline
    public static ByteVector shuffle(ByteVector input, ByteVector lowLUT, ByteVector highLUT, ByteVector lowMask) {
        var lo = input.and(lowMask).selectFrom(lowLUT);
        var hi = input.lanewise(VectorOperators.LSHR, 4).and(lowMask).selectFrom(highLUT);
        return lo.and(hi);
    }

    /**
     * Compare raw against a literal vector and accumulate matches.
     * Called once per literal in a group.
     */
    @Inline
    public static ByteVector cleanLit(ByteVector raw, ByteVector cleaned, ByteVector litVec) {
        return cleaned.add(raw, raw.compare(VectorOperators.EQ, litVec));
    }

    // --- Non-ASCII detection ---

    /**
     * Check if any byte in the chunk has the high bit set (>= 0x80).
     * Uses signed comparison: bytes >= 0x80 are negative in Java's signed byte.
     * Cheaper than {@code classify() + hasMultiByte()} — avoids the classify shuffle entirely.
     */
    public static boolean hasNonAscii(ByteVector chunk) {
        return chunk.compare(VectorOperators.LT, 0).anyTrue();
    }

    @Inline
    public static ByteVector classify(ByteVector chunk, ByteVector classifyVec, ByteVector lowMask) {
        return chunk.lanewise(VectorOperators.LSHR, 4).and(lowMask).selectFrom(classifyVec);
    }

    // --- Combine results from multiple shuffle groups ---

    @Inline
    public static ByteVector combineRounds(ByteVector a, ByteVector b) {
        return a.or(b);
    }

    // --- ASCII gate ---

    @Inline
    public static ByteVector gateAscii(ByteVector accumulator, ByteVector r0, ByteVector classify) {
        return accumulator.add(r0, classify.compare(VectorOperators.EQ, 1).and(r0.compare(VectorOperators.NE, 0)));
    }

    /**
     * Simplified ASCII gate for pure-ASCII chunks (all bytes &lt; 0x80).
     * Skips the classify check since on pure-ASCII data, classify is guaranteed all-1s,
     * making {@code classify.compare(EQ, 1)} trivially all-true.
     */
    @Inline
    public static ByteVector gateAsciiOnly(ByteVector accumulator, ByteVector r0) {
        return accumulator.add(r0, r0.compare(VectorOperators.NE, 0));
    }

    // --- Multi-byte gate (unrolled by byte length) ---

    @Inline
    public static ByteVector gateMultiByte2(ByteVector accumulator, ByteVector classify, ByteVector finalLit,
            ByteVector r0, ByteVector r1, ByteVector rl0, ByteVector rl1) {
        var gate = classify.compare(VectorOperators.EQ, 2);
        gate = gate.and(r0.compare(VectorOperators.EQ, rl0));
        gate = gate.and(r1.compare(VectorOperators.EQ, rl1));
        return accumulator.add(finalLit, gate);
    }

    @Inline
    public static ByteVector gateMultiByte3(ByteVector accumulator, ByteVector classify, ByteVector finalLit,
            ByteVector r0, ByteVector r1, ByteVector r2,
            ByteVector rl0, ByteVector rl1, ByteVector rl2) {
        var gate = classify.compare(VectorOperators.EQ, 3);
        gate = gate.and(r0.compare(VectorOperators.EQ, rl0));
        gate = gate.and(r1.compare(VectorOperators.EQ, rl1));
        gate = gate.and(r2.compare(VectorOperators.EQ, rl2));
        return accumulator.add(finalLit, gate);
    }

    @Inline
    public static ByteVector gateMultiByte4(ByteVector accumulator, ByteVector classify, ByteVector finalLit,
            ByteVector r0, ByteVector r1, ByteVector r2, ByteVector r3,
            ByteVector rl0, ByteVector rl1, ByteVector rl2, ByteVector rl3) {
        var gate = classify.compare(VectorOperators.EQ, 4);
        gate = gate.and(r0.compare(VectorOperators.EQ, rl0));
        gate = gate.and(r1.compare(VectorOperators.EQ, rl1));
        gate = gate.and(r2.compare(VectorOperators.EQ, rl2));
        gate = gate.and(r3.compare(VectorOperators.EQ, rl3));
        return accumulator.add(finalLit, gate);
    }

    // --- Range matching ---

    @Inline
    public static ByteVector rangeMatch(ByteVector inputVec, ByteVector accumulator,
            ByteVector lower, ByteVector upper, ByteVector literal) {
        var inRange = inputVec.compare(VectorOperators.GE, lower)
                .and(inputVec.compare(VectorOperators.LE, upper));
        return accumulator.add(literal, inRange);
    }

}
