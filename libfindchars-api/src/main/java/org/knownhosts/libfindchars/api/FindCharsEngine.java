package org.knownhosts.libfindchars.api;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public final class FindCharsEngine implements FindEngine {

    private final VectorSpecies<Byte> species;
    private final int vectorByteSize;
    private final ByteVector zero;

    private final ByteVector lowMask;
    private final ByteVector highMask;
    private final int shuffleGroupCount;
    private final ByteVector[] lowLUTs;
    private final ByteVector[] highLUTs;
    private final ByteVector[] allLiterals;
    private final int[] groupLitStart;
    private final int[] groupLitCount;

    private final int rangeCount;
    private final ByteVector[] rangeLower;
    private final ByteVector[] rangeUpper;
    private final ByteVector[] rangeLiteral;

    // Inline decode state
    private final VectorSpecies<Integer> intSpecies;
    private final int intBatchSize;
    private final byte[] literalCacheSparse;
    private final int[] positionCache;

    public FindCharsEngine(VectorSpecies<Byte> species, FindOp... ops) {
        this.species = species;
        this.vectorByteSize = species.vectorByteSize();
        this.zero = ByteVector.broadcast(species, (byte) 0);
        this.lowMask = ByteVector.broadcast(species, 0x0f);
        this.highMask = ByteVector.broadcast(species, 0x7f);

        ShuffleMaskOp shuffleOp = null;
        java.util.List<RangeOp> ranges = new java.util.ArrayList<>();
        for (var op : ops) {
            if (op instanceof ShuffleMaskOp s) shuffleOp = s;
            else if (op instanceof RangeOp r) ranges.add(r);
        }

        if (shuffleOp != null) {
            this.shuffleGroupCount = shuffleOp.groupCount();
            this.lowLUTs = new ByteVector[shuffleGroupCount];
            this.highLUTs = new ByteVector[shuffleGroupCount];
            int total = 0;
            for (int g = 0; g < shuffleGroupCount; g++) {
                lowLUTs[g] = shuffleOp.lowLUT(g);
                highLUTs[g] = shuffleOp.highLUT(g);
                total += shuffleOp.literalCount(g);
            }
            this.allLiterals = new ByteVector[total];
            this.groupLitStart = new int[shuffleGroupCount];
            this.groupLitCount = new int[shuffleGroupCount];
            int idx = 0;
            for (int g = 0; g < shuffleGroupCount; g++) {
                groupLitStart[g] = idx;
                groupLitCount[g] = shuffleOp.literalCount(g);
                for (int l = 0; l < groupLitCount[g]; l++) {
                    allLiterals[idx++] = shuffleOp.literalVec(g, l);
                }
            }
        } else {
            this.shuffleGroupCount = 0;
            this.lowLUTs = new ByteVector[0];
            this.highLUTs = new ByteVector[0];
            this.allLiterals = new ByteVector[0];
            this.groupLitStart = new int[0];
            this.groupLitCount = new int[0];
        }

        this.rangeCount = ranges.size();
        this.rangeLower = new ByteVector[rangeCount];
        this.rangeUpper = new ByteVector[rangeCount];
        this.rangeLiteral = new ByteVector[rangeCount];
        for (int r = 0; r < rangeCount; r++) {
            rangeLower[r] = ranges.get(r).lowerBoundVec();
            rangeUpper[r] = ranges.get(r).upperBoundVec();
            rangeLiteral[r] = ranges.get(r).literalVec();
        }

        this.intSpecies = species.withLanes(int.class);
        this.intBatchSize = intSpecies.length();
        this.literalCacheSparse = new byte[vectorByteSize];
        this.positionCache = new int[intBatchSize];
    }

    public FindCharsEngine(FindOp... ops) {
        this(ByteVector.SPECIES_PREFERRED, ops);
    }

    public MatchView find(MemorySegment mappedFile, MatchStorage matchStorage) {
        var fileOffset = 0;
        var globalCount = 0;
        final int dataSize = (int) mappedFile.byteSize();

        for (int i = 0; i < (dataSize - vectorByteSize); i += vectorByteSize) {
            matchStorage.ensureSize(vectorByteSize, globalCount);
            var inputVec = ByteVector.fromMemorySegment(species, mappedFile, i, ByteOrder.nativeOrder());

            var accumulator = applyAll(inputVec);
            globalCount = decode(matchStorage, accumulator, globalCount, fileOffset);
            fileOffset += vectorByteSize;
        }

        // last chunk
        byte[] lastChunkPadded = new byte[vectorByteSize];
        MemorySegment.copy(mappedFile, ValueLayout.JAVA_BYTE, fileOffset, lastChunkPadded, 0, dataSize - fileOffset);
        matchStorage.ensureSize(vectorByteSize, globalCount);
        var inputVec = ByteVector.fromArray(species, lastChunkPadded, 0);
        var accumulator = applyAll(inputVec);
        globalCount = decode(matchStorage, accumulator, globalCount, fileOffset);

        return new MatchView(globalCount);
    }

    private ByteVector applyAll(ByteVector inputVec) {
        var accumulator = zero;
        if (shuffleGroupCount >= 1) accumulator = applyGroup(inputVec, accumulator, 0);
        if (shuffleGroupCount >= 2) accumulator = applyGroup(inputVec, accumulator, 1);
        if (shuffleGroupCount >= 3) accumulator = applyGroup(inputVec, accumulator, 2);
        if (shuffleGroupCount >= 4) {
            for (int g = 3; g < shuffleGroupCount; g++) {
                accumulator = applyGroup(inputVec, accumulator, g);
            }
        }
        if (rangeCount >= 1) accumulator = applyRange(inputVec, accumulator, 0);
        if (rangeCount >= 2) accumulator = applyRange(inputVec, accumulator, 1);
        if (rangeCount >= 3) {
            for (int r = 2; r < rangeCount; r++) {
                accumulator = applyRange(inputVec, accumulator, r);
            }
        }
        return accumulator;
    }

    private ByteVector applyGroup(ByteVector inputVec, ByteVector accumulator, int g) {
        int s = groupLitStart[g];
        return switch (groupLitCount[g]) {
            case 1 -> EngineKernel.shuffleAndMatch1(inputVec, accumulator,
                    lowLUTs[g], highLUTs[g], lowMask, highMask, allLiterals[s]);
            case 2 -> EngineKernel.shuffleAndMatch2(inputVec, accumulator,
                    lowLUTs[g], highLUTs[g], lowMask, highMask,
                    allLiterals[s], allLiterals[s+1]);
            case 3 -> EngineKernel.shuffleAndMatch3(inputVec, accumulator,
                    lowLUTs[g], highLUTs[g], lowMask, highMask,
                    allLiterals[s], allLiterals[s+1], allLiterals[s+2]);
            case 4 -> EngineKernel.shuffleAndMatch4(inputVec, accumulator,
                    lowLUTs[g], highLUTs[g], lowMask, highMask,
                    allLiterals[s], allLiterals[s+1], allLiterals[s+2], allLiterals[s+3]);
            case 5 -> EngineKernel.shuffleAndMatch5(inputVec, accumulator,
                    lowLUTs[g], highLUTs[g], lowMask, highMask,
                    allLiterals[s], allLiterals[s+1], allLiterals[s+2], allLiterals[s+3],
                    allLiterals[s+4]);
            case 6 -> EngineKernel.shuffleAndMatch6(inputVec, accumulator,
                    lowLUTs[g], highLUTs[g], lowMask, highMask,
                    allLiterals[s], allLiterals[s+1], allLiterals[s+2], allLiterals[s+3],
                    allLiterals[s+4], allLiterals[s+5]);
            case 7 -> EngineKernel.shuffleAndMatch7(inputVec, accumulator,
                    lowLUTs[g], highLUTs[g], lowMask, highMask,
                    allLiterals[s], allLiterals[s+1], allLiterals[s+2], allLiterals[s+3],
                    allLiterals[s+4], allLiterals[s+5], allLiterals[s+6]);
            case 8 -> EngineKernel.shuffleAndMatch8(inputVec, accumulator,
                    lowLUTs[g], highLUTs[g], lowMask, highMask,
                    allLiterals[s], allLiterals[s+1], allLiterals[s+2], allLiterals[s+3],
                    allLiterals[s+4], allLiterals[s+5], allLiterals[s+6], allLiterals[s+7]);
            default -> applyGroupLoop(inputVec, accumulator, g);
        };
    }

    private ByteVector applyGroupLoop(ByteVector inputVec, ByteVector accumulator, int g) {
        var lo = lowLUTs[g].rearrange(inputVec.and(lowMask).toShuffle());
        var hi = highLUTs[g].rearrange(inputVec.lanewise(VectorOperators.LSHR, 4).and(highMask).toShuffle());
        var buf = lo.and(hi);
        int s = groupLitStart[g];
        for (int l = s; l < s + groupLitCount[g]; l++) {
            accumulator = accumulator.add(buf, buf.lanewise(VectorOperators.XOR, allLiterals[l]).compare(VectorOperators.EQ, 0));
        }
        return accumulator;
    }

    private ByteVector applyRange(ByteVector inputVec, ByteVector accumulator, int r) {
        return EngineKernel.rangeMatch(inputVec, accumulator, rangeLower[r], rangeUpper[r], rangeLiteral[r]);
    }

    private int decode(MatchStorage matchStorage, ByteVector accumulator, int globalCount, int fileOffset) {
        return EngineKernel.decode(matchStorage, accumulator, literalCacheSparse, positionCache,
                intSpecies, intBatchSize, globalCount, fileOffset);
    }
}
