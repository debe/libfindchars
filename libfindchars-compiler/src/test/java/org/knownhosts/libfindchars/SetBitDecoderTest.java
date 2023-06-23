package org.knownhosts.libfindchars;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;

class SetBitDecoderTest {

	private MatchDecoder decoder;

	@BeforeEach
	void setup() {
		this.decoder = new MatchDecoder();
	}
	
	
	@Test
	void testDecodeVecMemorySegmentsInt() {
		List<Integer> countsVector = Lists.newArrayList();
		List<Integer> countsScalar = Lists.newArrayList();

		byte[] testbits = new byte[] {0xf,0xf,0x0,0xf,0x0,0x0,0xf,0x0,0xf,0xf,0x0,0xf,0x0,0x0,0xf,0x0};
		ByteVector bytevec = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, testbits, 0);
			
        MatchStorage autochanger = new MatchStorage(32, 32);
		int count = decoder.decodePos(autochanger, bytevec,0, 48);

     	for (int j = 0; j < count; j++) {
     		countsVector.add(autochanger.getPositionsBuffer()[j]);
        }
	    
        MatchStorage autochanger2 = new MatchStorage(32, 32);

		count = decodeScalar(autochanger2, (int)bytevec.compare(VectorOperators.NE, 0).toLong(), (byte)0xf, 48);
		
     	for (int j = 0; j < count; j++) {
     		countsScalar.add(autochanger2.getPositionsBuffer()[j]);
        }
     
		
		System.out.println(countsScalar);
		System.out.println(countsVector);
		Assertions.assertIterableEquals(countsScalar, countsVector);
	}

	
    private int decodeScalar(MatchStorage matchStorage, int bits, byte literal, int offset)  {

        var count = Integer.bitCount(bits);
        for (int i = 0; i < count; i++) {
            var hit = Integer.numberOfTrailingZeros(bits);
            bits = bits & bits -1;
            hit+=offset;
            
            matchStorage.getLiteralBuffer()[(i)]=literal;
    	    matchStorage.getPositionsBuffer()[i]=hit;

        }
        return count;
    }

}
