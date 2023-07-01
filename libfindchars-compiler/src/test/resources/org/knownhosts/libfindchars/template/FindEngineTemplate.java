package org.knownhosts.libfindchars.template;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import org.knownhosts.libfindchars.MatchDecoder;
import org.knownhosts.libfindchars.MatchStorage;
import org.knownhosts.libfindchars.engine.FindOperation;
import org.knownhosts.libfindchars.engine.MatchView;

import jdk.incubator.vector.ByteVector;
import spoon.template.ExtensionTemplate;
import spoon.template.Parameter;

public class FindEngineTemplate extends ExtensionTemplate {


	private final FindOperation[] findOperation;
    
	@Parameter
	private int findOpsLen;
	
    public FindEngineTemplate(FindOperation... findOperations) {
    	this.findOperation = findOperations;
    	this.findOpsLen = findOperations.length;
    }
    
    public MatchView find(MemorySegment mappedFile, MatchStorage tapeStorage) {
    	var vectorByteSize = ByteVector.SPECIES_PREFERRED.vectorByteSize();
        var decoder = new MatchDecoder();
        var fileOffset = 0;
        var globalCount = 0;
        
        for (int i = 0; i < ((int)mappedFile.byteSize() - vectorByteSize);
        		i = i + vectorByteSize) {
        	var accumulator = ByteVector.SPECIES_PREFERRED.broadcast(0L).reinterpretAsBytes();
        	tapeStorage.ensureSize(ByteVector.SPECIES_PREFERRED.elementSize(), 1, globalCount);
        	
            var inputVec = ByteVector.fromMemorySegment(ByteVector.SPECIES_PREFERRED, mappedFile, i, ByteOrder.nativeOrder()); 

        	for (int j = 0; j < findOpsLen; j++) {
        		accumulator = findOperation[0].find(inputVec, accumulator);
        		accumulator = findOperation[1].find(inputVec, accumulator);

			}
        	var count = decoder.decodePos(tapeStorage, accumulator, globalCount, fileOffset);
        	
        	globalCount += count;
            fileOffset += vectorByteSize;
        }
        
        // last chunk
    	var accumulator = ByteVector.SPECIES_PREFERRED.broadcast(0L).reinterpretAsBytes();
        byte[] lastChunkPadded = new byte[vectorByteSize];
        MemorySegment.copy(mappedFile, ValueLayout.JAVA_BYTE, fileOffset,lastChunkPadded,0,(int)mappedFile.byteSize() - fileOffset);
        tapeStorage.ensureSize(ByteVector.SPECIES_PREFERRED.elementSize(), 1, globalCount);
        var inputVec = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, lastChunkPadded, 0); 

        
        for (int j = 0; j < findOpsLen; j++) {
        	accumulator = findOperation[j].find(inputVec, accumulator);
		}
    	var count = decoder.decodePos(tapeStorage, accumulator, globalCount, fileOffset);
    	globalCount+=count;
		
    	return new MatchView(globalCount);

    }
    
}
