package org.knownhosts.libfindchars.experiments;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;

public class RangeFindOperation implements FindOperation {


    private final ByteVector lowerBound;
    private final ByteVector upperBound;
    private final ByteVector literal;

    public RangeFindOperation(byte lowerBound, byte upperBound, byte literal) {

        this.lowerBound = ByteVector.SPECIES_PREFERRED.broadcast(lowerBound).reinterpretAsBytes();
        this.upperBound = ByteVector.SPECIES_PREFERRED.broadcast(upperBound).reinterpretAsBytes();
        this.literal = ByteVector.SPECIES_PREFERRED.broadcast(literal).reinterpretAsBytes();
    }

    @Override
    public ByteVector find(ByteVector inputVec, ByteVector accumulator) {
        var isGreater = inputVec.compare(VectorOperators.GE, lowerBound);
        var isInRange = isGreater.and(inputVec.compare(VectorOperators.LE, upperBound));
        accumulator = accumulator.add(literal, isInRange);

        return accumulator;

    }


}
