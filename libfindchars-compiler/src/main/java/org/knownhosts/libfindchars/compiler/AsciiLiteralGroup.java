package org.knownhosts.libfindchars.compiler;

import java.util.List;

public record AsciiLiteralGroup(String name, List<ByteLiteral> literals) {

    public AsciiLiteralGroup(String name, ByteLiteral... literals) {
        this(name, List.of(literals));
    }

}
