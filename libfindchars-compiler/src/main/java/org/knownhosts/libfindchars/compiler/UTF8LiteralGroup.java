package org.knownhosts.libfindchars.compiler;

import java.util.List;

public record UTF8LiteralGroup(String name, UTF8Literal... utf8Literals) implements LiteralGroup {

	@Override
	public String getName() {
		return name;
	}

	@Override
	public List<UTF8Literal> getLiterals() {
		return List.of(utf8Literals);
	}


}
