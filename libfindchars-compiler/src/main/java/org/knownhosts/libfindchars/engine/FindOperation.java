package org.knownhosts.libfindchars.engine;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.Vector;

public interface FindOperation {

    default Vector<Byte> find(MemorySegment mappedFile, int offset) {
        var inputVec = ByteVector.fromMemorySegment(ByteVector.SPECIES_PREFERRED, mappedFile, offset, ByteOrder.nativeOrder()); 
        return find(inputVec);
    }
    
	default Vector<Byte> find(byte[] data, int offset) {
        var inputVec = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, data, offset); 
        return find(inputVec);
	}
	   
    Vector<Byte> find(ByteVector dataVector);
}
