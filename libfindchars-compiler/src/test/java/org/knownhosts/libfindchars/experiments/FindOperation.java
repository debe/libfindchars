package org.knownhosts.libfindchars.experiments;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;

import jdk.incubator.vector.ByteVector;

public interface FindOperation {

    default ByteVector find(MemorySegment mappedFile, int offset, ByteVector accumulator) {
        var inputVec = ByteVector.fromMemorySegment(ByteVector.SPECIES_PREFERRED, mappedFile, offset, ByteOrder.nativeOrder()); 
        return find(inputVec, accumulator);
    }
    
	default ByteVector find(byte[] data, int offset, ByteVector accumulator) {
        var inputVec = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, data, offset); 
        return find(inputVec, accumulator);
	}
	   
	ByteVector find(ByteVector dataVector, ByteVector accumulator);
}
