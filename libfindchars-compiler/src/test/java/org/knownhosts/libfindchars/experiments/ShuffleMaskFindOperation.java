package org.knownhosts.libfindchars.experiments;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.knownhosts.libfindchars.api.FindMask;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;

public class ShuffleMaskFindOperation implements FindOperation{

	
	private final ByteVector lowByteVec;
	private final ByteVector highByteVec;

//	private final VectorSpecies<Byte> preferredSpecies;

	private final ByteVector[] lowNibbleMasks;

	private final ByteVector[] highNibbleMasks;

	private final ByteVector[] literalMasks;
	
	private  int len;

	private  final int lit_len;
	
	public ShuffleMaskFindOperation( List<FindMask> findMasks) {
		len = findMasks.size();
//		this.preferredSpecies = preferredSpecies;
		this.lowByteVec =  ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 0x0f);
		this.highByteVec = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 0x7f);
		this.lowNibbleMasks = new ByteVector[len];
		this.highNibbleMasks = new ByteVector[len];

		for (int i = 0; i < findMasks.size(); i++) {
			lowNibbleMasks[i] = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, 
					Arrays.copyOf(findMasks.get(i).lowNibbleMask(),ByteVector.SPECIES_PREFERRED.vectorByteSize()), 0);
			
			highNibbleMasks[i] = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, 
					Arrays.copyOf(findMasks.get(i).highNibbleMask(), ByteVector.SPECIES_PREFERRED.vectorByteSize()), 0);
		}

    	literalMasks = 
    			findMasks.stream().flatMap(e -> e.literals().values().stream())
    			.map(b -> ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, b))
    			.collect(Collectors.toList())
    			.toArray(new ByteVector[] {});
    	lit_len = literalMasks.length;
	}
	
	@Override
    public ByteVector find(ByteVector inputVec, ByteVector accumulator) {
    	
		for (int i = 0; i < len; i++) {
		 	var vShuffleLow = lowNibbleMasks[i].rearrange(inputVec.and(lowByteVec).toShuffle());
	        var vHighShiftedData = inputVec.lanewise(VectorOperators.LSHR,4).and(highByteVec);
	        var vShuffleHigh = highNibbleMasks[i].rearrange(vHighShiftedData.toShuffle());
	        var buf = vShuffleLow.and(vShuffleHigh);
	        

	        for (int j = 0; j < literalMasks.length; j++) {
	        
//	        for (int j = 0; j < 4; j++) {
        		var vOnlyLiteral = buf.lanewise(VectorOperators.XOR, literalMasks[j]);
        		accumulator = accumulator.add(buf, vOnlyLiteral.compare(VectorOperators.EQ, 0));
			}
		}
		return accumulator;

   }
    

}
