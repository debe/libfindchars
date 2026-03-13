package org.knownhosts.libfindchars.api;

import java.lang.foreign.MemorySegment;

/**
 * Engine that detects characters in byte sequences and returns their positions.
 *
 * <p>Implementations are created via
 * {@code Utf8EngineBuilder.builder()...build()} and use SIMD vector
 * operations to scan memory segments at high throughput.
 */
public interface FindEngine {

    /**
     * Scans {@code data} for configured characters, writing match positions and
     * literal identifiers into {@code storage}.
     *
     * @param data    memory segment to scan (must be accessible for the duration of the call)
     * @param storage reusable buffer that receives match positions and literal bytes
     * @return a view over the matches found in this call
     */
    MatchView find(MemorySegment data, MatchStorage storage);

    /**
     * Convenience overload that wraps a byte array in a {@link MemorySegment}.
     *
     * @param data    byte array to scan
     * @param storage reusable buffer that receives match positions and literal bytes
     * @return a view over the matches found in this call
     */
    default MatchView find(byte[] data, MatchStorage storage) {
        return find(MemorySegment.ofArray(data), storage);
    }
}
