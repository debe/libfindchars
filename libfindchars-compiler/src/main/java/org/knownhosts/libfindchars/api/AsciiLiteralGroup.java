package org.knownhosts.libfindchars.api;

import java.util.List;

public record AsciiLiteralGroup(String name, Literal... literals) implements LiteralGroup {

	@Override
	public String getName() {
		return name;
	}

	@Override
	public List<Literal> getLiterals() {
		return List.of(literals);
	}


}
