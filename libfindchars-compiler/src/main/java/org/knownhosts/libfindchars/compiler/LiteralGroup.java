package org.knownhosts.libfindchars.compiler;

import java.util.List;

public sealed interface LiteralGroup permits AsciiLiteralGroup, UTF8LiteralGroup{

	public String getName();

	public List<? extends Literal> getLiterals();

}