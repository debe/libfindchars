package org.knownhosts.libfindchars;

public interface Growable {

	int ensureSize(int expectedElements, int elementSize, int offset);
	
	int grow(int currentBufferLength, int elementsCount, int expectedAdditions);

	
}
