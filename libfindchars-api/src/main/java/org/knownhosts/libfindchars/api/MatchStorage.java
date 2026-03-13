package org.knownhosts.libfindchars.api;

import java.util.Arrays;

/**
 * Auto-growing dual-buffer storage for match results.
 *
 * <p>Holds parallel arrays of match positions ({@code int[]}) and literal
 * identifiers ({@code byte[]}).  The engine writes into these buffers during
 * a {@link FindEngine#find} call, and the returned {@link MatchView} reads
 * them back.  A single instance can be reused across calls &mdash; the engine
 * overwrites previous results.
 */
public final class MatchStorage {

    private int[] positions;

    private byte[] literals;

    private final int reserve;

    /**
     * @param expectedElements initial capacity (number of matches expected)
     * @param reserve          extra slots kept beyond the current write offset to
     *                         avoid growing on every vector iteration
     */
    public MatchStorage(int expectedElements, int reserve) {
        this.positions = new int[expectedElements + reserve];
        this.literals = new byte[expectedElements + reserve];
        this.reserve = reserve;
    }

    /**
     * Ensures the buffers can hold at least {@code expectedElements} entries
     * beyond the current {@code offset}, growing them if necessary.
     *
     * @param expectedElements number of new elements that may be written
     * @param offset           current write position in the buffers
     * @return the (possibly new) buffer capacity
     */
    public int ensureSize(int expectedElements, int offset) {
        final int maxSize = Math.min(literals.length, positions.length);
        final int additions = expectedElements + reserve; // plus reservation

        if ((offset + additions) > maxSize) {
            return grow(maxSize, offset, additions);
        }
        return maxSize;
    }

    private int grow(int maxSize, int offset, int additions) {
        final int newSize = Math.max(maxSize * 2, offset + additions);
        this.positions = Arrays.copyOf(positions, newSize);
        this.literals = Arrays.copyOf(literals, newSize);
        return newSize;
    }

    public byte[] getLiteralBuffer() {
        return literals;
    }

    public int[] getPositionsBuffer() {
        return positions;
    }

}
