package org.knownhosts.libfindchars.experiments;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;

import org.knownhosts.libfindchars.api.MatchDecoder;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.api.MatchView;

import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.ByteVector;

public class GeneratedEngine {

    private ByteVector lowerBound_1;
    private ByteVector upperBound_1;
    private ByteVector literal_1;
    private ByteVector lowByteVec_2;
    private ByteVector highByteVec_2;

    private ByteVector[] lowNibbleMasks_2;
    private ByteVector[] highNibbleMasks_2;

    private ByteVector[] literalMasks_2_0;
    private ByteVector[] literalMasks_2_1;

    public GeneratedEngine() {
        initialize();
    }

    public MatchView find(MemorySegment mappedFile, MatchStorage tapeStorage) {
        var vectorByteSize = ByteVector.SPECIES_PREFERRED.vectorByteSize();
        var decoder = new MatchDecoder();
        var fileOffset = 0;
        var globalCount = 0;

        for (int i = 0; i < ((int) mappedFile.byteSize() - vectorByteSize);
             i = i + vectorByteSize) {
            var accumulator = ByteVector.SPECIES_PREFERRED.broadcast(0L).reinterpretAsBytes();
            tapeStorage.ensureSize(ByteVector.SPECIES_PREFERRED.elementSize(), 1, globalCount);

            var inputVec = ByteVector.fromMemorySegment(ByteVector.SPECIES_PREFERRED, mappedFile, i, ByteOrder.nativeOrder());

            accumulator = _inlineFind(inputVec, accumulator);
            var count = decoder.decodePos(tapeStorage, accumulator, globalCount, fileOffset);

            globalCount += count;
            fileOffset += vectorByteSize;
        }

        // last chunk
        var accumulator = ByteVector.SPECIES_PREFERRED.broadcast(0L).reinterpretAsBytes();
        byte[] lastChunkPadded = new byte[vectorByteSize];
        MemorySegment.copy(mappedFile, ValueLayout.JAVA_BYTE, fileOffset, lastChunkPadded, 0, (int) mappedFile.byteSize() - fileOffset);
        tapeStorage.ensureSize(ByteVector.SPECIES_PREFERRED.elementSize(), 1, globalCount);
        var inputVec = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, lastChunkPadded, 0);

        accumulator = _inlineFind(inputVec, accumulator);

        var count = decoder.decodePos(tapeStorage, accumulator, globalCount, fileOffset);
        globalCount += count;

        return new MatchView(globalCount);

    }

    void initialize() {
        this.lowerBound_1 = ByteVector.SPECIES_PREFERRED.broadcast(97).reinterpretAsBytes();
        this.upperBound_1 = ByteVector.SPECIES_PREFERRED.broadcast(113).reinterpretAsBytes();
        this.literal_1 = ByteVector.SPECIES_PREFERRED.broadcast(1).reinterpretAsBytes();

        this.lowByteVec_2 = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 0x0f);
        this.highByteVec_2 = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 0x7f);
        this.lowNibbleMasks_2 = new ByteVector[2];
        this.highNibbleMasks_2 = new ByteVector[2];

        this.literalMasks_2_0 = new ByteVector[4];
        this.literalMasks_2_1 = new ByteVector[1];

        lowNibbleMasks_2[0] = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED,
                Arrays.copyOf(new byte[]{-61, -70, -70, -69, -70, -103, -102, -102, -70, 69, 79, 103, 91, 87, -70, -101}
                        , ByteVector.SPECIES_PREFERRED.vectorByteSize()), 0);

        highNibbleMasks_2[0] = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED,
                Arrays.copyOf(new byte[]{-31, -102, 125, 87, -102, -49, -102, -49}
                        , ByteVector.SPECIES_PREFERRED.vectorByteSize()), 0);

        literalMasks_2_0[0] = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 77);
        literalMasks_2_0[1] = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 71);
        literalMasks_2_0[2] = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 101);
        literalMasks_2_0[3] = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 65);

        lowNibbleMasks_2[1] = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED,
                Arrays.copyOf(new byte[]{126, 126, 126, 126, 126, 126, 126, 126, 126, 126, -127, -127, -127, -127, -127, -127}
                        , ByteVector.SPECIES_PREFERRED.vectorByteSize()), 0);

        highNibbleMasks_2[1] = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED,
                Arrays.copyOf(new byte[]{-127, -127, -127, -1, -127, -127, -127, -127}
                        , ByteVector.SPECIES_PREFERRED.vectorByteSize()), 0);

        literalMasks_2_1[0] = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 126);


    }

    ByteVector _inlineFind(ByteVector inputVec, ByteVector accumulator) {
        var isGreater_1 = inputVec.compare(VectorOperators.GE, lowerBound_1);
        var isInRange_1 = isGreater_1.and(inputVec.compare(VectorOperators.LE, upperBound_1));
        accumulator = accumulator.add(literal_1, isInRange_1);

        var vShuffleLow_2_1 = lowNibbleMasks_2[0].rearrange(inputVec.and(lowByteVec_2).toShuffle());
        var vHighShiftedData_2_1 = inputVec.lanewise(VectorOperators.LSHR, 4).and(highByteVec_2);
        var vShuffleHigh_2_1 = highNibbleMasks_2[0].rearrange(vHighShiftedData_2_1.toShuffle());
        var buf_2_0 = vShuffleLow_2_1.and(vShuffleHigh_2_1);

        accumulator = accumulator.add(buf_2_0, buf_2_0.lanewise(VectorOperators.XOR, literalMasks_2_0[0]).compare(VectorOperators.EQ, 0));
        accumulator = accumulator.add(buf_2_0, buf_2_0.lanewise(VectorOperators.XOR, literalMasks_2_0[1]).compare(VectorOperators.EQ, 0));
        accumulator = accumulator.add(buf_2_0, buf_2_0.lanewise(VectorOperators.XOR, literalMasks_2_0[2]).compare(VectorOperators.EQ, 0));
        accumulator = accumulator.add(buf_2_0, buf_2_0.lanewise(VectorOperators.XOR, literalMasks_2_0[3]).compare(VectorOperators.EQ, 0));

        var vShuffleLow_2_2 = lowNibbleMasks_2[1].rearrange(inputVec.and(lowByteVec_2).toShuffle());
        var vHighShiftedData_2_2 = inputVec.lanewise(VectorOperators.LSHR, 4).and(highByteVec_2);
        var vShuffleHigh_2_2 = highNibbleMasks_2[1].rearrange(vHighShiftedData_2_2.toShuffle());
        var buf_2_1 = vShuffleLow_2_2.and(vShuffleHigh_2_2);

        accumulator = accumulator.add(buf_2_1, buf_2_1.lanewise(VectorOperators.XOR, literalMasks_2_1[0]).compare(VectorOperators.EQ, 0));


        return accumulator;
    }


}
