package org.knownhosts.libfindchars.api;

import java.util.Arrays;

public final class MatchStorage {

    private int[] positions;

    private byte[] literals;

    private final int reserve;

    public MatchStorage(int expectedElements, int reserve) {
        this.positions = new int[expectedElements + reserve];
        this.literals = new byte[expectedElements + reserve];
        this.reserve = reserve;
    }

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
