package org.knownhosts.libfindchars.api;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

public final class FindCharsEngine {

    private final VectorSpecies<Byte> species;
    private final FindOp[] ops;
    private final MatchDecoder decoder;

    public FindCharsEngine(VectorSpecies<Byte> species, FindOp... ops) {
        this.species = species;
        this.ops = ops.clone();
        this.decoder = new MatchDecoder(species);
    }

    public FindCharsEngine(FindOp... ops) {
        this(ByteVector.SPECIES_PREFERRED, ops);
    }

    /**
     * Finds all matching characters in the given memory segment.
     *
     * <p>This method is <b>not thread-safe</b>. The internal {@link MatchDecoder}
     * reuses working buffers to avoid allocation on the hot path. Each thread
     * must use its own {@code FindCharsEngine} instance (same pattern as
     * {@code SimpleDateFormat}).
     */
    public MatchView find(MemorySegment mappedFile, MatchStorage matchStorage) {
        var vectorByteSize = species.vectorByteSize();
        var fileOffset = 0;
        var globalCount = 0;

        for (int i = 0; i < ((int) mappedFile.byteSize() - vectorByteSize);
             i = i + vectorByteSize) {
            var accumulator = ByteVector.broadcast(species, (byte) 0);
            matchStorage.ensureSize(species.elementSize(), globalCount);

            var inputVec = ByteVector.fromMemorySegment(species, mappedFile, i, ByteOrder.nativeOrder());

            for (var op : ops) {
                accumulator = op.apply(inputVec, accumulator);
            }

            globalCount += decoder.decodePos(matchStorage, accumulator, globalCount, fileOffset);
            fileOffset += vectorByteSize;
        }

        // last chunk
        var accumulator = ByteVector.broadcast(species, (byte) 0);
        byte[] lastChunkPadded = new byte[vectorByteSize];
        MemorySegment.copy(mappedFile, ValueLayout.JAVA_BYTE, fileOffset, lastChunkPadded, 0, (int) mappedFile.byteSize() - fileOffset);
        matchStorage.ensureSize(species.elementSize(), globalCount);

        var inputVec = ByteVector.fromArray(species, lastChunkPadded, 0);

        for (var op : ops) {
            accumulator = op.apply(inputVec, accumulator);
        }

        globalCount += decoder.decodePos(matchStorage, accumulator, globalCount, fileOffset);

        return new MatchView(globalCount);
    }
}
