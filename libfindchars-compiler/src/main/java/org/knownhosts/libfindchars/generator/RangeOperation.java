package org.knownhosts.libfindchars.generator;

import java.util.Objects;

public record RangeOperation(String name, byte from, byte to) {

    public RangeOperation {
        Objects.requireNonNull(name, "range operation name is mandatory");
    }

    public RangeOperation(String name, int from, int to) {
        this(name, (byte) from, (byte) to);
    }
}
