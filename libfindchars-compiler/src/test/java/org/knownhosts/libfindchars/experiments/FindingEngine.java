package org.knownhosts.libfindchars.experiments;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import org.knownhosts.libfindchars.api.MatchDecoder;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.api.MatchView;

import jdk.incubator.vector.ByteVector;

public class FindingEngine {

    private final FindOperation[] findOperation;

    public FindingEngine(FindOperation... findOperations) {
        this.findOperation = findOperations;
    }

    public MatchView find(MemorySegment mappedFile, MatchStorage tapeStorage) {
        var vectorByteSize = ByteVector.SPECIES_PREFERRED.vectorByteSize();
        var decoder = new MatchDecoder();
        var fileOffset = 0;
        var globalCount = 0;

        for (int i = 0; i < ((int) mappedFile.byteSize() - vectorByteSize);
             i = i + vectorByteSize) {
            var accumulator = ByteVector.SPECIES_PREFERRED.broadcast(0L).reinterpretAsBytes();
            tapeStorage.ensureSize(ByteVector.SPECIES_PREFERRED.elementSize(), 1, globalCount);

            for (int j = 0; j < findOperation.length; j++) {
                accumulator = findOperation[j].find(mappedFile, i, accumulator);
            }
            var count = decoder.decodePos(tapeStorage, accumulator, globalCount, fileOffset);

            globalCount += count;
            fileOffset += vectorByteSize;
        }

        // last chunk
        var accumulator = ByteVector.SPECIES_PREFERRED.broadcast(0L).reinterpretAsBytes();
        byte[] lastChunkPadded = new byte[vectorByteSize];
        MemorySegment.copy(mappedFile, ValueLayout.JAVA_BYTE, fileOffset, lastChunkPadded, 0, (int) mappedFile.byteSize() - fileOffset);
        tapeStorage.ensureSize(ByteVector.SPECIES_PREFERRED.elementSize(), 1, globalCount);
        for (int j = 0; j < findOperation.length; j++) {
            accumulator = findOperation[j].find(lastChunkPadded, 0, accumulator);
        }
        var count = decoder.decodePos(tapeStorage, accumulator, globalCount, fileOffset);
        globalCount += count;

        return new MatchView(globalCount);

    }


}
