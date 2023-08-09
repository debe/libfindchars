package org.knownhosts.libfindchars.compiler;

import java.util.List;

public record AsciiLiteralGroup(String name, AsciiLiteral... asciiLiterals) implements LiteralGroup {

	@Override
	public String getName() {
		return name;
	}

	@Override
	public List<AsciiLiteral> getLiterals() {
		return List.of(asciiLiterals);
	}


}
