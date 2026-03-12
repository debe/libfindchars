package org.knownhosts.libfindchars.api;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Static helper methods containing the actual SIMD logic for compiled engines.
 * Each method is small enough for C2 to inline from the generated hot loop.
 * The shuffle+literal-match methods are overloaded by literal count (1-8)
 * so each produces straight-line code with no loops.
 */
public final class EngineKernel {

    private EngineKernel() {}

    // --- Shuffle mask + literal matching (unrolled by literal count) ---

    private static ByteVector shuffle(ByteVector inputVec, ByteVector lowLUT, ByteVector highLUT,
            ByteVector lowMask, ByteVector highMask) {
        var lo = lowLUT.rearrange(inputVec.and(lowMask).toShuffle());
        var hi = highLUT.rearrange(inputVec.lanewise(VectorOperators.LSHR, 4).and(highMask).toShuffle());
        return lo.and(hi);
    }

    private static ByteVector matchLit(ByteVector buf, ByteVector accumulator, ByteVector lit) {
        return accumulator.add(buf, buf.lanewise(VectorOperators.XOR, lit).compare(VectorOperators.EQ, 0));
    }

    @Inline
    public static ByteVector shuffleAndMatch1(ByteVector inputVec, ByteVector accumulator,
            ByteVector lowLUT, ByteVector highLUT, ByteVector lowMask, ByteVector highMask,
            ByteVector lit0) {
        var buf = shuffle(inputVec, lowLUT, highLUT, lowMask, highMask);
        return matchLit(buf, accumulator, lit0);
    }

    @Inline
    public static ByteVector shuffleAndMatch2(ByteVector inputVec, ByteVector accumulator,
            ByteVector lowLUT, ByteVector highLUT, ByteVector lowMask, ByteVector highMask,
            ByteVector lit0, ByteVector lit1) {
        var buf = shuffle(inputVec, lowLUT, highLUT, lowMask, highMask);
        accumulator = matchLit(buf, accumulator, lit0);
        return matchLit(buf, accumulator, lit1);
    }

    @Inline
    public static ByteVector shuffleAndMatch3(ByteVector inputVec, ByteVector accumulator,
            ByteVector lowLUT, ByteVector highLUT, ByteVector lowMask, ByteVector highMask,
            ByteVector lit0, ByteVector lit1, ByteVector lit2) {
        var buf = shuffle(inputVec, lowLUT, highLUT, lowMask, highMask);
        accumulator = matchLit(buf, accumulator, lit0);
        accumulator = matchLit(buf, accumulator, lit1);
        return matchLit(buf, accumulator, lit2);
    }

    @Inline
    public static ByteVector shuffleAndMatch4(ByteVector inputVec, ByteVector accumulator,
            ByteVector lowLUT, ByteVector highLUT, ByteVector lowMask, ByteVector highMask,
            ByteVector lit0, ByteVector lit1, ByteVector lit2, ByteVector lit3) {
        var buf = shuffle(inputVec, lowLUT, highLUT, lowMask, highMask);
        accumulator = matchLit(buf, accumulator, lit0);
        accumulator = matchLit(buf, accumulator, lit1);
        accumulator = matchLit(buf, accumulator, lit2);
        return matchLit(buf, accumulator, lit3);
    }

    @Inline
    public static ByteVector shuffleAndMatch5(ByteVector inputVec, ByteVector accumulator,
            ByteVector lowLUT, ByteVector highLUT, ByteVector lowMask, ByteVector highMask,
            ByteVector lit0, ByteVector lit1, ByteVector lit2, ByteVector lit3,
            ByteVector lit4) {
        var buf = shuffle(inputVec, lowLUT, highLUT, lowMask, highMask);
        accumulator = matchLit(buf, accumulator, lit0);
        accumulator = matchLit(buf, accumulator, lit1);
        accumulator = matchLit(buf, accumulator, lit2);
        accumulator = matchLit(buf, accumulator, lit3);
        return matchLit(buf, accumulator, lit4);
    }

    @Inline
    public static ByteVector shuffleAndMatch6(ByteVector inputVec, ByteVector accumulator,
            ByteVector lowLUT, ByteVector highLUT, ByteVector lowMask, ByteVector highMask,
            ByteVector lit0, ByteVector lit1, ByteVector lit2, ByteVector lit3,
            ByteVector lit4, ByteVector lit5) {
        var buf = shuffle(inputVec, lowLUT, highLUT, lowMask, highMask);
        accumulator = matchLit(buf, accumulator, lit0);
        accumulator = matchLit(buf, accumulator, lit1);
        accumulator = matchLit(buf, accumulator, lit2);
        accumulator = matchLit(buf, accumulator, lit3);
        accumulator = matchLit(buf, accumulator, lit4);
        return matchLit(buf, accumulator, lit5);
    }

    @Inline
    public static ByteVector shuffleAndMatch7(ByteVector inputVec, ByteVector accumulator,
            ByteVector lowLUT, ByteVector highLUT, ByteVector lowMask, ByteVector highMask,
            ByteVector lit0, ByteVector lit1, ByteVector lit2, ByteVector lit3,
            ByteVector lit4, ByteVector lit5, ByteVector lit6) {
        var buf = shuffle(inputVec, lowLUT, highLUT, lowMask, highMask);
        accumulator = matchLit(buf, accumulator, lit0);
        accumulator = matchLit(buf, accumulator, lit1);
        accumulator = matchLit(buf, accumulator, lit2);
        accumulator = matchLit(buf, accumulator, lit3);
        accumulator = matchLit(buf, accumulator, lit4);
        accumulator = matchLit(buf, accumulator, lit5);
        return matchLit(buf, accumulator, lit6);
    }

    @Inline
    public static ByteVector shuffleAndMatch8(ByteVector inputVec, ByteVector accumulator,
            ByteVector lowLUT, ByteVector highLUT, ByteVector lowMask, ByteVector highMask,
            ByteVector lit0, ByteVector lit1, ByteVector lit2, ByteVector lit3,
            ByteVector lit4, ByteVector lit5, ByteVector lit6, ByteVector lit7) {
        var buf = shuffle(inputVec, lowLUT, highLUT, lowMask, highMask);
        accumulator = matchLit(buf, accumulator, lit0);
        accumulator = matchLit(buf, accumulator, lit1);
        accumulator = matchLit(buf, accumulator, lit2);
        accumulator = matchLit(buf, accumulator, lit3);
        accumulator = matchLit(buf, accumulator, lit4);
        accumulator = matchLit(buf, accumulator, lit5);
        accumulator = matchLit(buf, accumulator, lit6);
        return matchLit(buf, accumulator, lit7);
    }

    // --- Range matching ---

    @Inline
    public static ByteVector rangeMatch(ByteVector inputVec, ByteVector accumulator,
            ByteVector lower, ByteVector upper, ByteVector literal) {
        var inRange = inputVec.compare(VectorOperators.GE, lower)
                .and(inputVec.compare(VectorOperators.LE, upper));
        return accumulator.add(literal, inRange);
    }

    // --- Decode ---

    @Inline
    public static int decode(MatchStorage matchStorage, ByteVector accumulator,
            byte[] literalCacheSparse, int[] positionCache,
            VectorSpecies<Integer> intSpecies, int intBatchSize,
            int globalCount, int fileOffset) {
        accumulator.reinterpretAsBytes().intoArray(literalCacheSparse, 0);
        var findMask = accumulator.compare(VectorOperators.NE, 0);
        var bits = findMask.toLong();
        var count = findMask.trueCount();

        if (count != 0) {
            // Use Long.numberOfTrailingZeros to handle all 64 lanes (SPECIES_512)
            // without branching. The & 0x3f masks the result to [0,63] which covers
            // all species up to 512-bit. For narrower species, upper bits are zero
            // and LZCNT naturally returns positions within the valid range.
            var arrayOffset = globalCount;
            for (int k = 0; k < count; k += intBatchSize) {
                for (int m = 0; m < positionCache.length; m++) {
                    positionCache[m] = Long.numberOfTrailingZeros(bits) & 0x3f;
                    matchStorage.getLiteralBuffer()[arrayOffset + m] = literalCacheSparse[positionCache[m]];
                    bits = bits & (bits - 1);
                }
                var v = IntVector.fromArray(intSpecies, positionCache, 0);
                v.add(fileOffset).intoArray(matchStorage.getPositionsBuffer(), arrayOffset);
                arrayOffset += intBatchSize;
            }
        }
        return globalCount + count;
    }
}
