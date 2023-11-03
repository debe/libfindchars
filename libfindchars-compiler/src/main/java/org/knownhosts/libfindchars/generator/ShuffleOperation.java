package org.knownhosts.libfindchars.generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.knownhosts.libfindchars.compiler.LiteralGroup;

public final class ShuffleOperation extends Operation {

    public List<LiteralGroup> getLiteralGroups() {
        return literalGroups;
    }

    private final List<LiteralGroup> literalGroups = new ArrayList<>();

    public ShuffleOperation withLiteralGroups(LiteralGroup... literalGroup) {
        if (literalGroup != null) {
            Collections.addAll(this.literalGroups, literalGroup);
        }
        return this;
    }
}
