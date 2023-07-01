package org.knownhosts.libfindchars.experiments;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jdk.incubator.vector.ByteVector;

class RangeFindOperationTest {

	
	@BeforeAll
	static void setup() {
		
	}
	
	@Test
	void inBounds() throws Exception {

			var inputVec = ByteVector.SPECIES_PREFERRED.fromArray("ABCDEFGHJIKLMNOPQRSTUVWXYZ".getBytes(), 0).reinterpretAsBytes();
			var rangeFindOp = new RangeFindOperation((byte)0x41,(byte) 0x5A,(byte) 1);
			var accumulator = ByteVector.SPECIES_PREFERRED.broadcast(0L).reinterpretAsBytes();
			accumulator = rangeFindOp.find(inputVec, accumulator);
			System.out.println(accumulator);
			Assertions.assertTrue(accumulator.eq((byte)0x1).allTrue());
			;
	}
	
	
	@Test
	void testLowerBound() throws Exception {
			var inputVec = ByteVector.SPECIES_PREFERRED.fromArray("@ABCDEFGHJIKLMNOPQRSTUVWXYZ".getBytes(), 0).reinterpretAsBytes();
			var rangeFindOp = new RangeFindOperation((byte)0x41,(byte) 0x5A,(byte) 1);
			var accumulator = ByteVector.SPECIES_PREFERRED.broadcast(0L).reinterpretAsBytes();
			accumulator = rangeFindOp.find(inputVec, accumulator);
			System.out.println(accumulator);
			Assertions.assertEquals(1,accumulator.eq((byte)0x1).firstTrue());
			;
	}
	
	@Test
	void testHigherBound() throws Exception {
			var inputVec = ByteVector.SPECIES_PREFERRED.fromArray("@[ABCDEFGHJIKLMNOPQRSTUVWXYZ".getBytes(), 0).reinterpretAsBytes();
			var rangeFindOp = new RangeFindOperation((byte)0x41,(byte) 0x5A,(byte) 1);
			var accumulator = ByteVector.SPECIES_PREFERRED.broadcast(0L).reinterpretAsBytes();
			accumulator = rangeFindOp.find(inputVec, accumulator);
			System.out.println(accumulator);
			Assertions.assertEquals(2,accumulator.eq((byte)0x1).firstTrue());
			;
	}

}
