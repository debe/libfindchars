package org.knownhosts.libfindchars.api;

import java.util.Arrays;
import java.util.List;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;

public final class ShuffleMaskOp implements FindOp {

    private final ByteVector lowByteVec;
    private final ByteVector highByteVec;
    private final ByteVector[] lowNibbleMasks;
    private final ByteVector[] highNibbleMasks;
    private final ByteVector[][] literalMasks;

    public ShuffleMaskOp(List<FindMask> findMasks) {
        int len = findMasks.size();
        this.lowByteVec = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 0x0f);
        this.highByteVec = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, 0x7f);
        this.lowNibbleMasks = new ByteVector[len];
        this.highNibbleMasks = new ByteVector[len];
        this.literalMasks = new ByteVector[len][];

        for (int i = 0; i < len; i++) {
            var findMask = findMasks.get(i);
            lowNibbleMasks[i] = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED,
                    Arrays.copyOf(findMask.lowNibbleMask(), ByteVector.SPECIES_PREFERRED.vectorByteSize()), 0);
            highNibbleMasks[i] = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED,
                    Arrays.copyOf(findMask.highNibbleMask(), ByteVector.SPECIES_PREFERRED.vectorByteSize()), 0);

            var literals = findMask.literals().values();
            literalMasks[i] = new ByteVector[literals.size()];
            int j = 0;
            for (byte literal : literals) {
                literalMasks[i][j] = ByteVector.broadcast(ByteVector.SPECIES_PREFERRED, literal);
                j++;
            }
        }
    }

    @Override
    public ByteVector apply(ByteVector inputVec, ByteVector accumulator) {
        for (int i = 0; i < lowNibbleMasks.length; i++) {
            var vShuffleLow = lowNibbleMasks[i].rearrange(inputVec.and(lowByteVec).toShuffle());
            var vHighShiftedData = inputVec.lanewise(VectorOperators.LSHR, 4).and(highByteVec);
            var vShuffleHigh = highNibbleMasks[i].rearrange(vHighShiftedData.toShuffle());
            var buf = vShuffleLow.and(vShuffleHigh);

            for (int j = 0; j < literalMasks[i].length; j++) {
                var vOnlyLiteral = buf.lanewise(VectorOperators.XOR, literalMasks[i][j]);
                accumulator = accumulator.add(buf, vOnlyLiteral.compare(VectorOperators.EQ, 0));
            }
        }
        return accumulator;
    }
}
