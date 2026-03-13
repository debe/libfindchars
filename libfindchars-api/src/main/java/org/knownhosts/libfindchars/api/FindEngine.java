package org.knownhosts.libfindchars.api;

import java.lang.foreign.MemorySegment;

public interface FindEngine {
    MatchView find(MemorySegment data, MatchStorage storage);

    default MatchView find(byte[] data, MatchStorage storage) {
        return find(MemorySegment.ofArray(data), storage);
    }
}
