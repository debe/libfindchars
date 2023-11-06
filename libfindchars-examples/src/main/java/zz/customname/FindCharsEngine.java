package zz.customname;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;

import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.api.MatchView;

import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;


public class FindCharsEngine {

    private static int INT_BATCH_SIZE = VectorShape.preferredShape().vectorBitSize() / Integer.SIZE;
    private byte[] literalCacheSparse = new byte[IntVector.SPECIES_PREFERRED.vectorByteSize()];
    private int[] positionCache = new int[INT_BATCH_SIZE];


    private ByteVector lowByteVec_1;
    private ByteVector highByteVec_1;

    private ByteVector[] lowNibbleMasks_1;
    private ByteVector[] highNibbleMasks_1;

    private ByteVector[] literalMasks_1_0;
    private ByteVector[] literalMasks_1_1;
    private ByteVector lowerBound_2;
    private ByteVector upperBound_2;
    private ByteVector literal_2;
            
    public FindCharsEngine() {
        initialize();
    }
    
    void initialize() {

        this.lowByteVec_1 =  ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 0x0f);
        this.highByteVec_1 = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 0x7f);
        this.lowNibbleMasks_1 = new ByteVector[2];
        this.highNibbleMasks_1 = new ByteVector[2];
    
        this.literalMasks_1_0 = new ByteVector[4]; 
        this.literalMasks_1_1 = new ByteVector[1]; 
        
        lowNibbleMasks_1[0] = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, 
            Arrays.copyOf(new byte[]{-61,-70,-70,-69,-70,-103,-102,-102,-70,69,79,103,91,87,-70,-101}
,ByteVector.SPECIES_PREFERRED.vectorByteSize()), 0);
        
        highNibbleMasks_1[0] = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, 
            Arrays.copyOf(new byte[]{-31,-102,125,87,-102,-49,-102,-49}
, ByteVector.SPECIES_PREFERRED.vectorByteSize()), 0);
        
        literalMasks_1_0[0] = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 77);
        literalMasks_1_0[1] = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 71);
        literalMasks_1_0[2] = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 101);
        literalMasks_1_0[3] = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 65);
    
        lowNibbleMasks_1[1] = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, 
            Arrays.copyOf(new byte[]{126,126,126,126,126,126,126,126,126,126,-127,-127,-127,-127,-127,-127}
,ByteVector.SPECIES_PREFERRED.vectorByteSize()), 0);
        
        highNibbleMasks_1[1] = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, 
            Arrays.copyOf(new byte[]{-127,-127,-127,-1,-127,-127,-127,-127}
, ByteVector.SPECIES_PREFERRED.vectorByteSize()), 0);
        
        literalMasks_1_1[0] = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 126);
    
    
        this.lowerBound_2 = ByteVector.SPECIES_PREFERRED.broadcast(60).reinterpretAsBytes();
        this.upperBound_2 = ByteVector.SPECIES_PREFERRED.broadcast(62).reinterpretAsBytes();
        this.literal_2 = ByteVector.SPECIES_PREFERRED.broadcast(-119).reinterpretAsBytes();
    }

    public MatchView find(MemorySegment mappedFile, MatchStorage matchStorage) {
        var vectorByteSize = ByteVector.SPECIES_PREFERRED.vectorByteSize();
        var fileOffset = 0;
        var globalCount = 0;
        
        for (int i = 0; i < ((int)mappedFile.byteSize() - vectorByteSize);
                i = i + vectorByteSize) {
            var accumulator = ByteVector.SPECIES_PREFERRED.zero();
            matchStorage.ensureSize(ByteVector.SPECIES_PREFERRED.elementSize(), 1, globalCount);
            
            var inputVec = ByteVector.fromMemorySegment(ByteVector.SPECIES_PREFERRED, mappedFile, i, ByteOrder.nativeOrder()); 

        var vShuffleLow_1_1 = lowNibbleMasks_1[0].rearrange(inputVec.and(lowByteVec_1).toShuffle());
        var vHighShiftedData_1_1 = inputVec.lanewise(VectorOperators.LSHR,4).and(highByteVec_1);
        var vShuffleHigh_1_1 = highNibbleMasks_1[0].rearrange(vHighShiftedData_1_1.toShuffle());
        var buf_1_0 = vShuffleLow_1_1.and(vShuffleHigh_1_1);
    
        accumulator = accumulator.add(buf_1_0, buf_1_0.lanewise(VectorOperators.XOR, literalMasks_1_0[0]).compare(VectorOperators.EQ, 0));
        accumulator = accumulator.add(buf_1_0, buf_1_0.lanewise(VectorOperators.XOR, literalMasks_1_0[1]).compare(VectorOperators.EQ, 0));
        accumulator = accumulator.add(buf_1_0, buf_1_0.lanewise(VectorOperators.XOR, literalMasks_1_0[2]).compare(VectorOperators.EQ, 0));
        accumulator = accumulator.add(buf_1_0, buf_1_0.lanewise(VectorOperators.XOR, literalMasks_1_0[3]).compare(VectorOperators.EQ, 0));
        
        var vShuffleLow_1_2 = lowNibbleMasks_1[1].rearrange(inputVec.and(lowByteVec_1).toShuffle());
        var vHighShiftedData_1_2 = inputVec.lanewise(VectorOperators.LSHR,4).and(highByteVec_1);
        var vShuffleHigh_1_2 = highNibbleMasks_1[1].rearrange(vHighShiftedData_1_2.toShuffle());
        var buf_1_1 = vShuffleLow_1_2.and(vShuffleHigh_1_2);
    
        accumulator = accumulator.add(buf_1_1, buf_1_1.lanewise(VectorOperators.XOR, literalMasks_1_1[0]).compare(VectorOperators.EQ, 0));
        
	            
        var isGreater_2 = inputVec.compare(VectorOperators.GE, lowerBound_2);
        var isInRange_2 = isGreater_2.and(inputVec.compare(VectorOperators.LE, upperBound_2));
        accumulator = accumulator.add(literal_2, isInRange_2);
        	            

            accumulator.reinterpretAsBytes().intoArray(literalCacheSparse, 0);
            var findMask = accumulator.compare(VectorOperators.NE, 0);

            var bits = (int) findMask.toLong();
            var count = findMask.trueCount();

            if (count != 0) {
                var arrayOffset = globalCount;
        
                for (int k = 0; k < (count); k = k + INT_BATCH_SIZE) {
                    
                    for (int l = 0; l < positionCache.length; l++) {
                        positionCache[l] = Integer.numberOfTrailingZeros(bits) & 0x1f; // modulo 32 in fast;
                        matchStorage.getLiteralBuffer()[(arrayOffset) + l] = literalCacheSparse[positionCache[l]];
                        bits = bits & bits - 1;
                    }
                    var v = IntVector.fromArray(IntVector.SPECIES_PREFERRED, positionCache, 0);
                    var added = v.add(fileOffset);

                    added.intoArray(matchStorage.getPositionsBuffer(), arrayOffset);
                    arrayOffset += INT_BATCH_SIZE; // peek batch size
                }
            }
            
            globalCount += count;
            fileOffset += vectorByteSize;
        }
        
        // last chunk
        var accumulator = ByteVector.SPECIES_PREFERRED.zero();
        byte[] lastChunkPadded = new byte[vectorByteSize];
        MemorySegment.copy(mappedFile, ValueLayout.JAVA_BYTE, fileOffset,lastChunkPadded,0,(int)mappedFile.byteSize() - fileOffset);
        matchStorage.ensureSize(ByteVector.SPECIES_PREFERRED.elementSize(), 1, globalCount);
        var inputVec = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, lastChunkPadded, 0); 

        var vShuffleLow_1_1 = lowNibbleMasks_1[0].rearrange(inputVec.and(lowByteVec_1).toShuffle());
        var vHighShiftedData_1_1 = inputVec.lanewise(VectorOperators.LSHR,4).and(highByteVec_1);
        var vShuffleHigh_1_1 = highNibbleMasks_1[0].rearrange(vHighShiftedData_1_1.toShuffle());
        var buf_1_0 = vShuffleLow_1_1.and(vShuffleHigh_1_1);
    
        accumulator = accumulator.add(buf_1_0, buf_1_0.lanewise(VectorOperators.XOR, literalMasks_1_0[0]).compare(VectorOperators.EQ, 0));
        accumulator = accumulator.add(buf_1_0, buf_1_0.lanewise(VectorOperators.XOR, literalMasks_1_0[1]).compare(VectorOperators.EQ, 0));
        accumulator = accumulator.add(buf_1_0, buf_1_0.lanewise(VectorOperators.XOR, literalMasks_1_0[2]).compare(VectorOperators.EQ, 0));
        accumulator = accumulator.add(buf_1_0, buf_1_0.lanewise(VectorOperators.XOR, literalMasks_1_0[3]).compare(VectorOperators.EQ, 0));
        
        var vShuffleLow_1_2 = lowNibbleMasks_1[1].rearrange(inputVec.and(lowByteVec_1).toShuffle());
        var vHighShiftedData_1_2 = inputVec.lanewise(VectorOperators.LSHR,4).and(highByteVec_1);
        var vShuffleHigh_1_2 = highNibbleMasks_1[1].rearrange(vHighShiftedData_1_2.toShuffle());
        var buf_1_1 = vShuffleLow_1_2.and(vShuffleHigh_1_2);
    
        accumulator = accumulator.add(buf_1_1, buf_1_1.lanewise(VectorOperators.XOR, literalMasks_1_1[0]).compare(VectorOperators.EQ, 0));
        
            
        var isGreater_2 = inputVec.compare(VectorOperators.GE, lowerBound_2);
        var isInRange_2 = isGreater_2.and(inputVec.compare(VectorOperators.LE, upperBound_2));
        accumulator = accumulator.add(literal_2, isInRange_2);
                    
            accumulator.reinterpretAsBytes().intoArray(literalCacheSparse, 0);
            var findMask = accumulator.compare(VectorOperators.NE, 0);

            var bits = (int) findMask.toLong();
            var count = findMask.trueCount();

            if (count != 0) {
                var arrayOffset = globalCount;
        
                for (int k = 0; k < (count); k = k + INT_BATCH_SIZE) {
                    
                    for (int l = 0; l < positionCache.length; l++) {
                        positionCache[l] = Integer.numberOfTrailingZeros(bits) & 0x1f; // modulo 32 in fast;
                        matchStorage.getLiteralBuffer()[(arrayOffset) + l] = literalCacheSparse[positionCache[l]];
                        bits = bits & bits - 1;
                    }
                    var v = IntVector.fromArray(IntVector.SPECIES_PREFERRED, positionCache, 0);
                    var added = v.add(fileOffset);

                    added.intoArray(matchStorage.getPositionsBuffer(), arrayOffset);
                    arrayOffset += INT_BATCH_SIZE; // peek batch size
                }
            }
        globalCount+=count;
        
        return new MatchView(globalCount);

    }

       
}
