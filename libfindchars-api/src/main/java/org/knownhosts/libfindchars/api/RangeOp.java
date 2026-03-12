package org.knownhosts.libfindchars.api;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

public final class RangeOp implements FindOp {

    private final ByteVector lowerBound;
    private final ByteVector upperBound;
    private final ByteVector literal;

    public RangeOp(VectorSpecies<Byte> species, byte lowerBound, byte upperBound, byte literal) {
        this.lowerBound = species.broadcast(lowerBound).reinterpretAsBytes();
        this.upperBound = species.broadcast(upperBound).reinterpretAsBytes();
        this.literal = species.broadcast(literal).reinterpretAsBytes();
    }

    public RangeOp(byte lowerBound, byte upperBound, byte literal) {
        this(ByteVector.SPECIES_PREFERRED, lowerBound, upperBound, literal);
    }

    // Package-private accessors for FindCharsEngine flattening
    ByteVector lowerBoundVec() { return lowerBound; }
    ByteVector upperBoundVec() { return upperBound; }
    ByteVector literalVec() { return literal; }

    @Override
    public ByteVector apply(ByteVector inputVec, ByteVector accumulator) {
        var isGreater = inputVec.compare(VectorOperators.GE, lowerBound);
        var isInRange = isGreater.and(inputVec.compare(VectorOperators.LE, upperBound));
        return accumulator.add(literal, isInRange);
    }
}
