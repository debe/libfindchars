package org.knownhosts.libfindchars.api;

import java.util.Arrays;
import java.util.List;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public final class ShuffleMaskOp implements FindOp {

    private final ByteVector LOW_BYTE_VEC;
    private final ByteVector HIGH_BYTE_VEC;

    private final ByteVector[] lowNibbleMasks;
    private final ByteVector[] highNibbleMasks;
    private final ByteVector[][] literalMasks;

    public ShuffleMaskOp(VectorSpecies<Byte> species, List<FindMask> findMasks) {
        LOW_BYTE_VEC = ByteVector.broadcast(species, 0x0f);
        HIGH_BYTE_VEC = ByteVector.broadcast(species, 0x7f);

        int len = findMasks.size();
        this.lowNibbleMasks = new ByteVector[len];
        this.highNibbleMasks = new ByteVector[len];
        this.literalMasks = new ByteVector[len][];

        for (int i = 0; i < len; i++) {
            var findMask = findMasks.get(i);
            lowNibbleMasks[i] = ByteVector.fromArray(species,
                    Arrays.copyOf(findMask.lowNibbleMask(), species.vectorByteSize()), 0);
            highNibbleMasks[i] = ByteVector.fromArray(species,
                    Arrays.copyOf(findMask.highNibbleMask(), species.vectorByteSize()), 0);

            var literals = findMask.literals().values();
            literalMasks[i] = new ByteVector[literals.size()];
            int j = 0;
            for (byte literal : literals) {
                literalMasks[i][j] = ByteVector.broadcast(species, literal);
                j++;
            }
        }
    }

    public ShuffleMaskOp(List<FindMask> findMasks) {
        this(ByteVector.SPECIES_PREFERRED, findMasks);
    }

    @Override
    public ByteVector apply(ByteVector inputVec, ByteVector accumulator) {
        for (int i = 0; i < lowNibbleMasks.length; i++) {
            var vShuffleLow = lowNibbleMasks[i].rearrange(inputVec.and(LOW_BYTE_VEC).toShuffle());
            var vHighShiftedData = inputVec.lanewise(VectorOperators.LSHR, 4).and(HIGH_BYTE_VEC);
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
