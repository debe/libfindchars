package org.knownhosts.libfindchars.api;

import jdk.incubator.vector.ByteVector;

public sealed interface FindOp permits ShuffleMaskOp, RangeOp {
    ByteVector apply(ByteVector inputVec, ByteVector accumulator);
}
