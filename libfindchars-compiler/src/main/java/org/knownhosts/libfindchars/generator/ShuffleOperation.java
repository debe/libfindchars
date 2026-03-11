package org.knownhosts.libfindchars.generator;

import java.util.List;

import org.knownhosts.libfindchars.compiler.LiteralGroup;

public record ShuffleOperation(List<LiteralGroup> literalGroups) {

    public ShuffleOperation {
        literalGroups = List.copyOf(literalGroups);
    }

    public ShuffleOperation(LiteralGroup... literalGroups) {
        this(List.of(literalGroups));
    }
}
