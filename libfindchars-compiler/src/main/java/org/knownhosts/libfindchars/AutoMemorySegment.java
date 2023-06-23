package org.knownhosts.libfindchars;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentScope;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import com.carrotsearch.hppc.ArraySizingStrategy;
import com.carrotsearch.hppc.BoundedProportionalArraySizingStrategy;

import jdk.incubator.vector.VectorShape;

public class AutoMemorySegment implements Supplier<MemorySegment>{

	private final BiFunction<Long, SegmentScope, MemorySegment> segmentProvider;

	private MemorySegment memorySegment;
	
	private final SegmentScope segmentScope;
		
	private final ArraySizingStrategy resizer;

	private final int reserve;
	
	
	private AutoMemorySegment(BiFunction<Long, SegmentScope,MemorySegment> segmentProvider, long segmentSize, SegmentScope segmentScope, int reserve) {
		this.segmentProvider = segmentProvider;
		this.reserve = reserve;
		this.segmentScope = segmentScope;
        this.resizer = new BoundedProportionalArraySizingStrategy();
        this.memorySegment = segmentProvider.apply(segmentSize, segmentScope);
	}
	
	
	public static AutoMemorySegment withProvider(BiFunction<Long, SegmentScope, MemorySegment> segmentProvider, long segmentSize, SegmentScope segmentScope, int reserve ) {
		 var autoMemSegment  = new AutoMemorySegment(segmentProvider, segmentSize, segmentScope, reserve);
		 return autoMemSegment;
	}
	
	public static AutoMemorySegment withProvider(BiFunction<Long, SegmentScope, MemorySegment> segmentProvider, long segmentSize, SegmentScope segmentScope ) {
		 var autoMemSegment  = new AutoMemorySegment(segmentProvider, segmentSize, segmentScope, VectorShape.preferredShape().vectorBitSize() >> 3);
		 return autoMemSegment;
	}
	
	public static AutoMemorySegment withProvider(BiFunction<Long, SegmentScope, MemorySegment> segmentProvider, long segmentSize, int reserve ) {
		 var autoMemSegment  = new AutoMemorySegment(segmentProvider, segmentSize, SegmentScope.auto(), reserve);
		 return autoMemSegment;
	}
	
	public static AutoMemorySegment withProvider(BiFunction<Long, SegmentScope, MemorySegment> segmentProvider, long segmentSize ) {
		 var autoMemSegment  = new AutoMemorySegment(segmentProvider, segmentSize, SegmentScope.auto(), VectorShape.preferredShape().vectorBitSize() >> 3);
		 return autoMemSegment;
	}

	
	public void ensureSize(int expectedElements, int elementSize, int offset) {
		final int maxSize = (int)memorySegment.byteSize();
	    final int additions = (expectedElements * elementSize) + (reserve * elementSize); // plus reservation
	    
	    if ((offset + additions)  >  maxSize) {
	        final long newSize = resizer.grow(maxSize, offset, additions);
	        grow(newSize);
	    }
	}
	
	
	void grow(long newSize) {
		final MemorySegment newSegment = segmentProvider.apply(newSize, segmentScope);
		MemorySegment.copy(memorySegment, 0, newSegment, 0, memorySegment.byteSize());
		this.memorySegment = newSegment;
	}
	
	
	@Override
	public MemorySegment get() {
		return memorySegment;
	}

}
