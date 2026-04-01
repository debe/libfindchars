package org.knownhosts.libfindchars.api;

/**
 * Immutable view over the results of a single {@link FindEngine#find} call.
 *
 * <p>The view records how many matches were found ({@link #size()}) and
 * provides indexed access to match positions and literal identifiers stored
 * in the associated {@link MatchStorage}.
 */
public final class MatchView {

    private final int size;

    public MatchView(int size) {
        this.size = size;
    }

    /** Returns the number of matches found. */
    public int size() {
        return size;
    }

    /**
     * Returns the byte offset of the match at {@code index}.
     *
     * @param autoTape the storage that was passed to {@link FindEngine#find}
     * @param index    match index, {@code 0 <= index < size()}
     * @return byte offset of the matched character in the scanned data
     */
    public long getPositionAt(MatchStorage autoTape, int index) {
        return autoTape.getPositionsBuffer()[index];
    }

    /**
     * Returns the literal identifier of the match at {@code index}.
     *
     * <p>Compare the returned byte against the values in the literal map
     * obtained from the builder to determine which character group matched.
     *
     * @param autoTape the storage that was passed to {@link FindEngine#find}
     * @param index    match index, {@code 0 <= index < size()}
     * @return literal byte identifying the matched character group
     */
    public byte getLiteralAt(MatchStorage autoTape, int index) {
        return autoTape.getLiteralBuffer()[index];
    }

}
