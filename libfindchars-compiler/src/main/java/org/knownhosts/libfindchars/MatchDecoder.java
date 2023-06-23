package org.knownhosts.libfindchars;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.Vector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;

public class MatchDecoder {
	private static int INT_BATCH_SIZE = VectorShape.preferredShape().vectorBitSize() / Integer.SIZE;

	private byte[] literalCacheSparse = new byte[IntVector.SPECIES_PREFERRED.vectorByteSize()];
	private int[] positionCache = new int[INT_BATCH_SIZE];

    
	 int decode8WithLiteral(MatchStorage tapeStorage, int bits, int offset, int arrayOffset)  {
     	for (int i = 0; i < positionCache.length; i++) {
            positionCache[i] = Integer.numberOfTrailingZeros(bits) % 32;
            tapeStorage.getLiteralBuffer()[(arrayOffset) + i]= literalCacheSparse[positionCache[i]];
            bits = bits & bits -1;
        }
        var v = IntVector.fromArray(IntVector.SPECIES_PREFERRED, positionCache, 0);
        var added = v.add(offset);
	      
	    added.intoArray(tapeStorage.getPositionsBuffer(), arrayOffset);
	    
	    return bits;
	 }


	public int decodePos(MatchStorage tapeStorage, Vector<Byte> positions, int segmentOffset, int fileOffset) {
		
		positions.reinterpretAsBytes().intoArray(literalCacheSparse, 0);
		var findMask = positions.compare(VectorOperators.NE, 0);
		var bits = (int)findMask.toLong();
		var count = findMask.trueCount();
		
		if(count < 0) {
			return 0;
		}
		
		//vectorized and very slow variation
//		var positionsCompressed = positions.compress(findMask);
//		positionsCompressed.reinterpretAsBytes().intoArray(tapeStorage.getLiteralBuffer(), segmentOffset >> 2);
//		
        var arrayOffset = segmentOffset;
        
        for (int i = 0; i < (count); i = i + INT_BATCH_SIZE) {
            bits = decode8WithLiteral(tapeStorage, bits, fileOffset, arrayOffset >> 2);
            arrayOffset+=IntVector.SPECIES_PREFERRED.vectorByteSize(); // peek on vector size
        }
        
        return count;


	}

    
  
 
  
}
