package org.knownhosts.libfindchars.api;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

public final class Utf8FindCharsEngine {

    private final VectorSpecies<Byte> species;
    private final Utf8ShuffleMaskOp op;
    private final MatchDecoder decoder;
    private final int maxRounds;

    public Utf8FindCharsEngine(VectorSpecies<Byte> species, Utf8ShuffleMaskOp op) {
        this.species = species;
        this.op = op;
        this.decoder = new MatchDecoder(species);
        this.maxRounds = op.maxRounds();
    }

    public Utf8FindCharsEngine(Utf8ShuffleMaskOp op) {
        this(ByteVector.SPECIES_PREFERRED, op);
    }

    /**
     * Finds all matching characters in the given memory segment.
     *
     * <p>This method is <b>not thread-safe</b>. Each thread must use its own
     * {@code Utf8FindCharsEngine} instance.
     */
    public MatchView find(MemorySegment data, MatchStorage matchStorage) {
        var vectorByteSize = species.vectorByteSize();
        var dataSize = (int) data.byteSize();
        var fileOffset = 0;
        var globalCount = 0;
        var zero = ByteVector.broadcast(species, (byte) 0);
        var nativeOrder = ByteOrder.nativeOrder();

        int i = 0;
        if (vectorByteSize <= dataSize) {
            // Pre-load first chunk — carried forward across iterations
            var chunk = ByteVector.fromMemorySegment(species, data, 0, nativeOrder);

            // Main loop: i + 2*vectorByteSize guards both round offset loads (i+1..i+3)
            // and the next-chunk pre-load at i+vectorByteSize
            for (; i + 2 * vectorByteSize <= dataSize; i += vectorByteSize) {
                var accumulator = zero;
                matchStorage.ensureSize(vectorByteSize, globalCount);

                var r1Input = (maxRounds >= 2) ? ByteVector.fromMemorySegment(species, data, i + 1, nativeOrder) : zero;
                var r2Input = (maxRounds >= 3) ? ByteVector.fromMemorySegment(species, data, i + 2, nativeOrder) : zero;
                var r3Input = (maxRounds >= 4) ? ByteVector.fromMemorySegment(species, data, i + 3, nativeOrder) : zero;

                accumulator = op.apply(chunk, r1Input, r2Input, r3Input, accumulator);
                globalCount += decoder.decodePos(matchStorage, accumulator, globalCount, fileOffset);
                fileOffset += vectorByteSize;

                // Pre-load next iteration's chunk — reused as chunk on next pass
                chunk = ByteVector.fromMemorySegment(species, data, i + vectorByteSize, nativeOrder);
            }

            // Tail: chunk already loaded (from pre-load or initial load), padded round loads
            var accumulator = zero;
            matchStorage.ensureSize(vectorByteSize, globalCount);

            int remaining = dataSize - i;
            byte[] padded = new byte[vectorByteSize + maxRounds - 1];
            MemorySegment.copy(data, ValueLayout.JAVA_BYTE, i, padded, 0, Math.min(remaining, padded.length));
            var r1Input = (maxRounds >= 2) ? ByteVector.fromArray(species, padded, 1) : zero;
            var r2Input = (maxRounds >= 3) ? ByteVector.fromArray(species, padded, 2) : zero;
            var r3Input = (maxRounds >= 4) ? ByteVector.fromArray(species, padded, 3) : zero;

            accumulator = op.apply(chunk, r1Input, r2Input, r3Input, accumulator);
            globalCount += decoder.decodePos(matchStorage, accumulator, globalCount, fileOffset);
        }

        return new MatchView(globalCount);
    }
}
