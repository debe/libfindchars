package org.knownhosts.libfindchars.engine;

import org.knownhosts.libfindchars.MatchStorage;

public class MatchView {
	
	private final int size;
		
	public MatchView(int size) {
		this.size = size;
	}
	public int size() {
		return size;
	}
	
	
	public int[] getMatch(MatchStorage autoTape, int index) {
//		StructLayout l = StructLayout.structLayout(memoryLayout);
		return new int[] {
				autoTape.getLiteralBuffer()[index],
				autoTape.getPositionsBuffer()[index]
		};
	}
	

	public int getPositionAt(MatchStorage autoTape, int index) {
		return autoTape.getPositionsBuffer()[index];
	}
	

	public byte getLiteralAt(MatchStorage autoTape, int index) {
		return autoTape.getLiteralBuffer()[index];
	}
	
}
