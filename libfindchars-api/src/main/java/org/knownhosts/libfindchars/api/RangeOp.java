package org.knownhosts.libfindchars.api;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;

public final class RangeOp implements FindOp {

    private final ByteVector lowerBound;
    private final ByteVector upperBound;
    private final ByteVector literal;

    public RangeOp(byte lowerBound, byte upperBound, byte literal) {
        this.lowerBound = ByteVector.SPECIES_PREFERRED.broadcast(lowerBound).reinterpretAsBytes();
        this.upperBound = ByteVector.SPECIES_PREFERRED.broadcast(upperBound).reinterpretAsBytes();
        this.literal = ByteVector.SPECIES_PREFERRED.broadcast(literal).reinterpretAsBytes();
    }

    @Override
    public ByteVector apply(ByteVector inputVec, ByteVector accumulator) {
        var isGreater = inputVec.compare(VectorOperators.GE, lowerBound);
        var isInRange = isGreater.and(inputVec.compare(VectorOperators.LE, upperBound));
        return accumulator.add(literal, isInRange);
    }
}
