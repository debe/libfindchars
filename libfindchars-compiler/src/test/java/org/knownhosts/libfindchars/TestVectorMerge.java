package org.knownhosts.libfindchars;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.Iterator;

import org.junit.Ignore;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ByteVector;

import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorOperators.Conversion;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

@Disabled
class TestVectorMerge {

	static boolean[] oddArray = new boolean[] {true,false,true,false,true,false,true,false,true,false,true,false,true,false,true,false,true,false};
	VectorMask<Integer> maskEven = 		IntVector.SPECIES_PREFERRED.loadMask(oddArray, 0);
	VectorMask<Integer> maskOdd = 		IntVector.SPECIES_PREFERRED.loadMask(oddArray, 1);
	VectorMask<Integer> maskAll = 		IntVector.SPECIES_PREFERRED.maskAll(true);

    static long MAGIC = 4292484099903637661L;

    
	
	@Test
	void test() {
		
		var literalVector = IntVector.SPECIES_PREFERRED.broadcast(1);
		var positionVector = IntVector.SPECIES_PREFERRED.fromArray(new int[] {1,2,3,4}, 0);
		
        VectorShuffle<Integer> zip0 = VectorShuffle.makeZip(IntVector.SPECIES_PREFERRED, 0);
        VectorShuffle<Integer> zip1 = VectorShuffle.makeZip(IntVector.SPECIES_PREFERRED, 1);
                
		var merge1 = literalVector.rearrange(zip0,positionVector);
		var merge2 = literalVector.rearrange(zip1,positionVector);

		
		var res1 = merge1.toIntArray();
		var res2 = merge2.toIntArray();
		Object t;
		
		
	}

	

	@Test
	void test2() {
		try(var arena = Arena.openShared()){
			
			MemorySegment segment = arena.allocate(64);	
			var literalVector = IntVector.SPECIES_PREFERRED.fromArray(new int[] {10,11,12,13}, 0);
			var positionVector = IntVector.SPECIES_PREFERRED.fromArray(new int[] {1,2,3,4}, 0);
			var blended1 = literalVector.blend(positionVector, maskAll);
			var blended2 = literalVector.blend(positionVector, maskAll);

			literalVector.intoMemorySegment(segment, 0, ByteOrder.nativeOrder());
			literalVector.intoMemorySegment(segment, 0+IntVector.SPECIES_PREFERRED.vectorByteSize(), ByteOrder.nativeOrder());
			positionVector.intoMemorySegment(segment, 4, ByteOrder.nativeOrder(), maskEven);
			positionVector.intoMemorySegment(segment, 4+IntVector.SPECIES_PREFERRED.vectorByteSize(), ByteOrder.nativeOrder(),maskEven);
	
		
			var res1 = segment.toArray(ValueLayout.JAVA_INT);
			
		}
		
	}
	
	@Test
	void test3() {
		var positions = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, new byte[] {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15},0);
		var casted1 = positions.convert(Conversion.ofCast(byte.class, int.class), 0);
		var casted2 = positions.convert(Conversion.ofCast(byte.class, int.class), 1);
		var casted3 = positions.convert(Conversion.ofCast(byte.class, int.class), 2);
		var casted4 = positions.convert(Conversion.ofCast(byte.class, int.class), 3);

		
	}
	
	@Test
	void test4() {
		var positions = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, new byte[] {0,1,2,0,0,0,0,7,8,9,10,11,12,13,14,15},0);
		var findMask = positions.compare(VectorOperators.NE, 0);

		var compressed = positions.compress(findMask);
		
	}
	
	@Test
	void test5() {
		var mphf = bruteforceMPHF(100);
	}
	
	
	public static int universalHash(byte element, int index, int limit) {
		return 	((31 * index + element + index) & limit) & 0xf;
	}
	public static int universalHash2(byte element, int index) {
	   return Math.abs((int)( (element ^ index) * MAGIC)) & 0xf ;
	}
	
	
	public  int bruteforceMPHF(int max) {
		var bytes = new byte[] {71 ,77 ,65 ,101};
		var bitset = new BitSet();
		for(int i = 0; i < max; i++) {
			for(byte k=0; k < 255; k++) {
				bitset.clear();
				for(int j =0; j< bytes.length; j++) {
						bitset.set(universalHash(bytes[j], i, k));
					}
				
				if(bitset.cardinality() == bytes.length) {
					System.out.println("cardinality was: " + bitset.cardinality());
					System.out.println("bitset is "+ bitset);
					System.out.println("i is "+ i);
					System.out.println("k is "+ String.format("0x%02X",k));

					return i;
				}
			}
		}
		return -1;
	}
	
	
	
}
