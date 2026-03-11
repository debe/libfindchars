package org.knownhosts.libfindchars.compiler;

import java.util.List;

public record AsciiLiteralGroup(String name, List<Literal> literals) implements LiteralGroup {

    public AsciiLiteralGroup(String name, Literal... literals) {
        this(name, List.of(literals));
    }

}
