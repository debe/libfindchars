package org.knownhosts.libfindchars.engine;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.api.AutoMemorySegment;

class AutoMemorySegmentTest {

	@Test
	void testNative() {
		
		int size = 10;
		int bytes  = size * 4;
		
		AutoMemorySegment autoSegment = AutoMemorySegment.withProvider(MemorySegment::allocateNative, 40);
		
		autoSegment.ensureSize(11, 4, 0);
		
		Assertions.assertTrue(autoSegment.get().byteSize() > bytes);
		
		autoSegment.ensureSize(20, 4, 0);
		
		Assertions.assertTrue(autoSegment.get().byteSize() > 20 * 4);

	}
	
	@Test
	void testHeap() {
		
		int size = 10;
		int bytes  = size * 4;
		
		AutoMemorySegment autoSegment = AutoMemorySegment.withProvider((t,u) -> MemorySegment.ofArray(new int[t.intValue()]), 5);
		autoSegment.get().set(ValueLayout.JAVA_INT, 4*4, 4);

		autoSegment.ensureSize(11, 4, 0);
		
		Assertions.assertTrue(autoSegment.get().byteSize() > bytes);
		
		autoSegment.get().set(ValueLayout.JAVA_INT, 10*4, 10);

		autoSegment.ensureSize(20, 4, 0);
		
		Assertions.assertTrue(autoSegment.get().byteSize() > 20 * 4);
		Assertions.assertEquals(4, autoSegment.get().get(ValueLayout.JAVA_INT,4*4));

		Assertions.assertEquals(10, autoSegment.get().get(ValueLayout.JAVA_INT,10*4));

		}

}
