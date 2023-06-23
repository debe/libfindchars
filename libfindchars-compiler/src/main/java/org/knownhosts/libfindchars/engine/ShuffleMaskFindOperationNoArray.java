package org.knownhosts.libfindchars.engine;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.knownhosts.libfindchars.api.FindMask;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.Vector;
import jdk.incubator.vector.VectorOperators;

public class ShuffleMaskFindOperationNoArray implements FindOperation{

	
	private final ByteVector lowByteVec;
	private final ByteVector highByteVec;

//	private final VectorSpecies<Byte> preferredSpecies;

	private final ByteVector lowNibbleMask;

	private final ByteVector highNibbleMask;

	private final ByteVector[] literalMasks;
	

	
	public ShuffleMaskFindOperationNoArray( List<FindMask> findMasks) {
//		this.preferredSpecies = preferredSpecies;
		this.lowByteVec =  ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 0x0f);
		this.highByteVec = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 0x7f);

		lowNibbleMask = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, 
				Arrays.copyOf(findMasks.get(0).lowNibbleMask(),ByteVector.SPECIES_PREFERRED.vectorByteSize()), 0);
		
		highNibbleMask = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, 
				Arrays.copyOf(findMasks.get(0).highNibbleMask(), ByteVector.SPECIES_PREFERRED.vectorByteSize()), 0);

    	literalMasks = findMasks.get(0).literals().values()
    					.stream()
                        .map(b -> ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, b))
                       .collect(Collectors.toList())
                       .toArray(new ByteVector[] {});    	
	}
	
	@Override
    public Vector<Byte> find(ByteVector inputVec) {
		var vShuffleLow = lowNibbleMask.rearrange(inputVec.and(lowByteVec).toShuffle());
        var vHighShiftedData = inputVec.lanewise(VectorOperators.LSHR,4).and(highByteVec);
        var vShuffleHigh = highNibbleMask.rearrange(vHighShiftedData.toShuffle());
        var vTmp = vShuffleLow.and(vShuffleHigh);	
        var reduced = ByteVector.SPECIES_PREFERRED.broadcast(0l);
        
        for (int i = 0; i < literalMasks.length; i++) {
    		var vOnlyLiteral = vTmp.lanewise(VectorOperators.XOR, literalMasks[i]);
    		var positions = vOnlyLiteral.compare(VectorOperators.EQ, 0);
    		reduced = reduced.add(vTmp, positions);
		}
        return reduced;
   }
    

}
