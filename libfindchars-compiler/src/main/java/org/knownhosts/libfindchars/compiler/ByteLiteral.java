package org.knownhosts.libfindchars.compiler;

public record ByteLiteral(String name, char... chars) implements Literal {
}
