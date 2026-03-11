package org.knownhosts.libfindchars.api;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import jdk.incubator.vector.ByteVector;

public final class FindCharsEngine {

    private final FindOp[] ops;
    private final MatchDecoder decoder = new MatchDecoder();

    public FindCharsEngine(FindOp... ops) {
        this.ops = ops.clone();
    }

    public MatchView find(MemorySegment mappedFile, MatchStorage matchStorage) {
        var vectorByteSize = ByteVector.SPECIES_PREFERRED.vectorByteSize();
        var fileOffset = 0;
        var globalCount = 0;

        for (int i = 0; i < ((int) mappedFile.byteSize() - vectorByteSize);
             i = i + vectorByteSize) {
            var accumulator = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, (byte) 0);
            matchStorage.ensureSize(ByteVector.SPECIES_PREFERRED.elementSize(), 1, globalCount);

            var inputVec = ByteVector.fromMemorySegment(ByteVector.SPECIES_PREFERRED, mappedFile, i, ByteOrder.nativeOrder());

            for (var op : ops) {
                accumulator = op.apply(inputVec, accumulator);
            }

            globalCount += decoder.decodePos(matchStorage, accumulator, globalCount, fileOffset);
            fileOffset += vectorByteSize;
        }

        // last chunk
        var accumulator = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, (byte) 0);
        byte[] lastChunkPadded = new byte[vectorByteSize];
        MemorySegment.copy(mappedFile, ValueLayout.JAVA_BYTE, fileOffset, lastChunkPadded, 0, (int) mappedFile.byteSize() - fileOffset);
        matchStorage.ensureSize(ByteVector.SPECIES_PREFERRED.elementSize(), 1, globalCount);

        var inputVec = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, lastChunkPadded, 0);

        for (var op : ops) {
            accumulator = op.apply(inputVec, accumulator);
        }

        globalCount += decoder.decodePos(matchStorage, accumulator, globalCount, fileOffset);

        return new MatchView(globalCount);
    }
}
