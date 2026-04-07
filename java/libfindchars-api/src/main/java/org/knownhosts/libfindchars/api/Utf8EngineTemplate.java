package org.knownhosts.libfindchars.api;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;


/**
 * Readable template for the UTF-8 SIMD engine. <b>Not thread-safe</b> — mutable
 * {@code decodeTmp} and {@code filterState} fields are reused across calls.
 *
 * <p>This class serves two purposes:
 * <ol>
 *   <li><b>C2 JIT fallback</b>: works as-is with array fields, loops, and switches.
 *       Slower than the compiled path but correct.</li>
 *   <li><b>Compiled engine template</b>: the {@link org.knownhosts.libfindchars.compiler.inline.TemplateTransformer}
 *       reads this class's bytecode and specializes it by constant-folding {@code @Inline int} fields,
 *       inlining {@code @Inline} private methods, and expanding {@code @Inline} array fields to
 *       individual named fields. The result is flat bytecode identical to what {@code Utf8EngineCodeGen}
 *       produced manually.</li>
 * </ol>
 */
public class Utf8EngineTemplate implements FindEngine {

    // Common SIMD vectors
    private final ByteVector zero;
    private final ByteVector classifyVec;
    private final ByteVector lowMask;

    // Per flat-group vectors (indexed by flat group index)
    @Inline private final ByteVector[] lowLUTs;
    @Inline private final ByteVector[] highLUTs;
    @Inline private final ByteVector[][] literalVecs;
    @Inline private final ByteVector[] cleanLUTs;

    // Per charSpec vectors
    @Inline private final ByteVector[] charSpecFinalLitVecs;
    @Inline private final ByteVector[][] charSpecRoundLitVecs;

    // Per range vectors
    @Inline private final ByteVector[] rangeLower;
    @Inline private final ByteVector[] rangeUpper;
    @Inline private final ByteVector[] rangeLit;

    // Configuration constants (constant-folded in compiled path)
    @Inline private final int maxRounds;
    @Inline private final int rangeCount;
    @Inline private final int charSpecCount;
    @Inline private final int totalGroups;
    @Inline private final int vectorByteSize;
    // Round-to-group mapping
    private final int[] roundGroupStart;
    private final int[] roundGroupCount;
    private final int[] flatGroupLitCounts;
    private final int[] charSpecByteLengths;

    // Chunk filter (VPA extension point)
    @Inline private final int filterEnabled;
    private final ChunkFilter chunkFilter;
    private final ByteVector[] filterLiterals;
    private final long[] filterState;
    private final byte[] filterScratchpad;

    // Platform-adaptive decode
    @Inline private final int useCompress;
    private final byte[] decodeTmp;
    private final byte[] tailPad;

    // Species for vector operations
    private final VectorSpecies<Byte> species;

    public Utf8EngineTemplate(
            VectorSpecies<Byte> species,
            ByteVector zero,
            ByteVector classifyVec,
            ByteVector lowMask,
            ByteVector[] lowLUTs,
            ByteVector[] highLUTs,
            ByteVector[][] literalVecs,
            ByteVector[] cleanLUTs,
            int[] roundGroupStart,
            int[] roundGroupCount,
            int[] flatGroupLitCounts,
            int[] charSpecByteLengths,
            ByteVector[][] charSpecRoundLitVecs,
            ByteVector[] charSpecFinalLitVecs,
            ByteVector[] rangeLower,
            ByteVector[] rangeUpper,
            ByteVector[] rangeLit,
            int filterEnabled,
            ChunkFilter chunkFilter,
            ByteVector[] filterLiterals,
            int useCompress) {

        this.species = species;
        this.zero = zero;
        this.classifyVec = classifyVec;
        this.lowMask = lowMask;
        this.lowLUTs = lowLUTs;
        this.highLUTs = highLUTs;
        this.literalVecs = literalVecs;
        this.cleanLUTs = cleanLUTs;
        this.roundGroupStart = roundGroupStart;
        this.roundGroupCount = roundGroupCount;
        this.flatGroupLitCounts = flatGroupLitCounts;
        this.charSpecByteLengths = charSpecByteLengths;
        this.charSpecRoundLitVecs = charSpecRoundLitVecs;
        this.charSpecFinalLitVecs = charSpecFinalLitVecs;
        this.rangeLower = rangeLower;
        this.rangeUpper = rangeUpper;
        this.rangeLit = rangeLit;

        this.maxRounds = roundGroupStart.length;
        this.rangeCount = rangeLower.length;
        this.charSpecCount = charSpecByteLengths.length;
        this.totalGroups = lowLUTs.length;

        this.vectorByteSize = species.vectorByteSize();

        // Chunk filter
        this.filterEnabled = filterEnabled;
        this.chunkFilter = chunkFilter;
        this.filterLiterals = filterLiterals;
        this.filterState = new long[8];
        this.filterScratchpad = new byte[species.vectorByteSize()];

        // Platform-adaptive decode
        this.useCompress = useCompress;
        this.decodeTmp = new byte[species.vectorByteSize()];
        this.tailPad = new byte[species.vectorByteSize()];
    }

    @Override
    public MatchView find(MemorySegment data, MatchStorage matchStorage) {
        long dataSize = data.byteSize();

        if (dataSize == 0) {
            return new MatchView(0);
        }

        // Initial estimate — grows incrementally if actual match count exceeds this.
        matchStorage.ensureSize((int) Math.min(dataSize / 10, Integer.MAX_VALUE - 64), 0);

        // Reset filter carry state for each find() call
        if (filterEnabled != 0) {
            Arrays.fill(filterState, 0);
        }

        int globalCount = 0;
        long i = 0;

        var litBuf = matchStorage.getLiteralBuffer();
        var posBuf = matchStorage.getPositionsBuffer();

        // Main loop: process aligned chunks
        while (i + vectorByteSize <= dataSize) {
            // Grow storage if approaching capacity (one comparison per chunk).
            if (globalCount + vectorByteSize > litBuf.length) {
                matchStorage.ensureSize(vectorByteSize, globalCount);
                litBuf = matchStorage.getLiteralBuffer();
                posBuf = matchStorage.getPositionsBuffer();
            }
            globalCount = processMainBody(data, litBuf, posBuf, i, globalCount);
            i += vectorByteSize;
        }

        // Tail: process remaining bytes (may emit up to vectorByteSize matches)
        if (i < dataSize) {
            if (globalCount + vectorByteSize > litBuf.length) {
                matchStorage.ensureSize(vectorByteSize, globalCount);
                litBuf = matchStorage.getLiteralBuffer();
                posBuf = matchStorage.getPositionsBuffer();
            }
            int remaining = (int) (dataSize - i);
            Arrays.fill(tailPad, (byte) 0);
            MemorySegment.copy(data, ValueLayout.JAVA_BYTE, i, tailPad, 0, remaining);
            var chunk = ByteVector.fromArray(species, tailPad, 0);

            if (maxRounds > 1 && Utf8Kernel.hasNonAscii(chunk)) {
                byte[] padded = new byte[vectorByteSize + maxRounds - 1];
                MemorySegment.copy(data, ValueLayout.JAVA_BYTE, i, padded, 0, remaining);
                globalCount = processTailBody(matchStorage, chunk, padded, i, globalCount);
            } else {
                globalCount = processSingleChunk(matchStorage, chunk, i, globalCount);
            }
        }

        return new MatchView(globalCount);
    }

    // Per-chunk processing: load chunk + SIMD detection + optional filter + decode.
    private int processMainBody(MemorySegment data, byte[] litBuf, long[] posBuf,
                                long i, int globalCount) {
        var chunk = ByteVector.fromMemorySegment(species, data, i, ByteOrder.nativeOrder());

        // --- SIMD detection ---
        var accumulator = zero;
        var r0 = applyRound(chunk, 0);

        if (maxRounds > 1 && Utf8Kernel.hasNonAscii(chunk)) {
            var classify = Utf8Kernel.classify(chunk, classifyVec, lowMask);
            ByteVector r1 = zero, r2 = zero, r3 = zero;
            if (maxRounds >= 2) {
                r1 = applyRound(ByteVector.fromMemorySegment(species, data, i + 1, ByteOrder.nativeOrder()), 1);
            }
            if (maxRounds >= 3) {
                r2 = applyRound(ByteVector.fromMemorySegment(species, data, i + 2, ByteOrder.nativeOrder()), 2);
            }
            if (maxRounds >= 4) {
                r3 = applyRound(ByteVector.fromMemorySegment(species, data, i + 3, ByteOrder.nativeOrder()), 3);
            }
            accumulator = Utf8Kernel.gateAscii(accumulator, r0, classify);
            for (int s = 0; s < charSpecCount; s++) {
                accumulator = gateCharSpec(accumulator, classify, s, r0, r1, r2, r3);
            }
        } else {
            accumulator = Utf8Kernel.gateAsciiOnly(accumulator, r0);
        }

        for (int r = 0; r < rangeCount; r++) {
            accumulator = Utf8Kernel.rangeMatch(chunk, accumulator,
                    rangeLower[r], rangeUpper[r], rangeLit[r]);
        }

        // --- Chunk filter (VPA extension point) ---
        if (filterEnabled != 0) {
            accumulator = chunkFilter.apply(accumulator, zero, species,
                    filterState, filterScratchpad, filterLiterals);
        }

        return decodeMatches(accumulator, litBuf, posBuf, globalCount, i);
    }

    @Override
    public MatchView find(byte[] data, MatchStorage matchStorage) {
        return find(java.lang.foreign.MemorySegment.ofArray(data), matchStorage);
    }

    // Single chunk processing without MemorySegment (for tail)
    private int processSingleChunk(MatchStorage matchStorage,
                                   ByteVector chunk, long i, int globalCount) {
        var accumulator = zero;
        var r0 = applyRound(chunk, 0);
        accumulator = Utf8Kernel.gateAsciiOnly(accumulator, r0);

        for (int r = 0; r < rangeCount; r++) {
            accumulator = Utf8Kernel.rangeMatch(chunk, accumulator,
                    rangeLower[r], rangeUpper[r], rangeLit[r]);
        }

        // --- Chunk filter (VPA extension point) ---
        if (filterEnabled != 0) {
            accumulator = chunkFilter.apply(accumulator, zero, species,
                    filterState, filterScratchpad, filterLiterals);
        }

        return decodeMatches(accumulator, matchStorage.getLiteralBuffer(),
                matchStorage.getPositionsBuffer(), globalCount, i);
    }

    private int processTailBody(MatchStorage matchStorage,
                                ByteVector chunk, byte[] padded,
                                long fileOffset, int globalCount) {
        var accumulator = zero;
        var r0 = applyRound(chunk, 0);

        if (Utf8Kernel.hasNonAscii(chunk)) {
            var classify = Utf8Kernel.classify(chunk, classifyVec, lowMask);
            ByteVector r1 = zero, r2 = zero, r3 = zero;
            if (maxRounds >= 2) r1 = applyRound(ByteVector.fromArray(species, padded, 1), 1);
            if (maxRounds >= 3) r2 = applyRound(ByteVector.fromArray(species, padded, 2), 2);
            if (maxRounds >= 4) r3 = applyRound(ByteVector.fromArray(species, padded, 3), 3);
            accumulator = Utf8Kernel.gateAscii(accumulator, r0, classify);
            for (int s = 0; s < charSpecCount; s++) {
                accumulator = gateCharSpec(accumulator, classify, s, r0, r1, r2, r3);
            }
        } else {
            accumulator = Utf8Kernel.gateAsciiOnly(accumulator, r0);
        }

        for (int r = 0; r < rangeCount; r++) {
            accumulator = Utf8Kernel.rangeMatch(chunk, accumulator,
                    rangeLower[r], rangeUpper[r], rangeLit[r]);
        }

        // --- Chunk filter (VPA extension point) ---
        if (filterEnabled != 0) {
            accumulator = chunkFilter.apply(accumulator, zero, species,
                    filterState, filterScratchpad, filterLiterals);
        }

        return decodeMatches(accumulator, matchStorage.getLiteralBuffer(),
                matchStorage.getPositionsBuffer(), globalCount, fileOffset);
    }

    /**
     * Platform-adaptive decode: extract match positions and literal bytes.
     * anyTrue() maps to a single NEON UMAXV — fast reject for no-match chunks.
     * toLong() requires multiple NEON→GPR transfers, only called when needed.
     */
    @Inline
    private int decodeMatches(ByteVector accumulator, byte[] litBuf, long[] posBuf,
                              int globalCount, long baseOffset) {
        var findMask = accumulator.compare(VectorOperators.NE, 0);
        if (findMask.anyTrue()) {
            var bits = findMask.toLong();
            if (useCompress != 0) {
                // AVX-512: VPCOMPRESSB intrinsic — single instruction
                var count = findMask.trueCount();
                accumulator.compress(findMask).intoArray(litBuf, globalCount);
                int offset = globalCount;
                while (bits != 0) {
                    posBuf[offset++] = Long.numberOfTrailingZeros(bits) + baseOffset;
                    bits &= (bits - 1);
                }
                return globalCount + count;
            } else {
                // ARM/AVX2: intoArray + scatter (avoids compress lambda fallback)
                accumulator.intoArray(decodeTmp, 0);
                int offset = globalCount;
                while (bits != 0) {
                    int lane = Long.numberOfTrailingZeros(bits);
                    litBuf[offset] = decodeTmp[lane];
                    posBuf[offset] = lane + baseOffset;
                    offset++;
                    bits &= (bits - 1);
                }
                return offset;
            }
        }
        return globalCount;
    }

    @Inline
    private ByteVector applyRound(ByteVector input, int round) {
        int start = roundGroupStart[round];
        int count = roundGroupCount[round];
        var result = applyGroup(input, start);
        for (int g = 1; g < count; g++) {
            result = Utf8Kernel.combineRounds(result, applyGroup(input, start + g));
        }
        return result;
    }

    @Inline
    private ByteVector applyGroup(ByteVector input, int groupIdx) {
        var raw = Utf8Kernel.shuffle(input, lowLUTs[groupIdx], highLUTs[groupIdx], lowMask);
        return raw.selectFrom(cleanLUTs[groupIdx]);
    }

    @Inline
    private ByteVector gateCharSpec(ByteVector accumulator, ByteVector classify,
                                     int specIdx, ByteVector r0, ByteVector r1,
                                     ByteVector r2, ByteVector r3) {
        int byteLen = charSpecByteLengths[specIdx];
        return switch (byteLen) {
            case 2 -> Utf8Kernel.gateMultiByte2(accumulator, classify,
                    charSpecFinalLitVecs[specIdx],
                    r0, r1,
                    charSpecRoundLitVecs[specIdx][0], charSpecRoundLitVecs[specIdx][1]);
            case 3 -> Utf8Kernel.gateMultiByte3(accumulator, classify,
                    charSpecFinalLitVecs[specIdx],
                    r0, r1, r2,
                    charSpecRoundLitVecs[specIdx][0], charSpecRoundLitVecs[specIdx][1],
                    charSpecRoundLitVecs[specIdx][2]);
            case 4 -> Utf8Kernel.gateMultiByte4(accumulator, classify,
                    charSpecFinalLitVecs[specIdx],
                    r0, r1, r2, r3,
                    charSpecRoundLitVecs[specIdx][0], charSpecRoundLitVecs[specIdx][1],
                    charSpecRoundLitVecs[specIdx][2], charSpecRoundLitVecs[specIdx][3]);
            default -> throw new UnsupportedOperationException("byteLen=" + byteLen);
        };
    }
}
