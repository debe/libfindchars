package org.knownhosts.libfindchars.engine;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import org.knownhosts.libfindchars.MatchDecoder;
import org.knownhosts.libfindchars.MatchStorage;

import jdk.incubator.vector.ByteVector;

public class FindingEngine {
	
	private final FindOperation findOperation;
    
    public FindingEngine(FindOperation findOperation) {
    	this.findOperation = findOperation;

    }
    
    public MatchView find(MemorySegment mappedFile, MatchStorage tapeStorage) {
    	var vectorByteSize = ByteVector.SPECIES_PREFERRED.vectorByteSize();
        var decoder = new MatchDecoder();
        var fileOffset = 0;
        var globalCount = 0;
        for (int i = 0; i < ((int)mappedFile.byteSize() - vectorByteSize);
        		i = i + vectorByteSize) {
        	tapeStorage.ensureSize(ByteVector.SPECIES_PREFERRED.elementSize(), 1, globalCount << 2);
        	var count = decoder.decodePos(tapeStorage, findOperation.find(mappedFile, i), globalCount<<2, fileOffset);
        	globalCount+=count;
            fileOffset += vectorByteSize;
        }
        
        // last chunk
        byte[] lastChunkPadded = new byte[vectorByteSize];
        MemorySegment.copy(mappedFile, ValueLayout.JAVA_BYTE, fileOffset,lastChunkPadded,0,(int)mappedFile.byteSize() - fileOffset);
        tapeStorage.ensureSize(ByteVector.SPECIES_PREFERRED.elementSize(), 1, globalCount << 2);
    	var count = decoder.decodePos(tapeStorage,findOperation.find(lastChunkPadded, 0), globalCount<<2, fileOffset);
    	globalCount+=count;
		
    
    	return new MatchView(globalCount);

    }
    
}
