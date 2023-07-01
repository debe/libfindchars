package org.knownhosts.libfindchars.generator;

import java.util.ArrayList;
import java.util.List;

import org.knownhosts.libfindchars.compiler.LiteralGroup;

public final class ShuffleOperation extends Operation {
	
	public List<LiteralGroup> getLiteralGroups() {
		return literalGroups;
	}

	private List<LiteralGroup> literalGroups = new ArrayList<>();
	
	public ShuffleOperation withLiteralGroups(LiteralGroup... literalGroup) {
		if(literalGroup != null) {
			for (LiteralGroup group : literalGroup) {
				this.literalGroups.add(group);
			}
		}
		return this;
	}
}
