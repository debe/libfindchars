package org.knownhosts.libfindchars.api;

import java.util.Arrays;

import com.carrotsearch.hppc.ArraySizingStrategy;
import com.carrotsearch.hppc.BoundedProportionalArraySizingStrategy;

public class MatchStorage implements Growable {

    private int[] positions;

    private byte[] literals;

    private final int reserve;

    private final ArraySizingStrategy resizer;

    public MatchStorage(int expectedElements, int reserve) {
        this.positions = new int[expectedElements + reserve];
        this.literals = new byte[expectedElements + reserve];
        this.resizer = new BoundedProportionalArraySizingStrategy();

        this.reserve = reserve;
    }

    @Override
    public int ensureSize(int expectedElements, int elementSize, int offset) {
        final int maxSize = Math.min(literals.length, positions.length);
        final int additions = expectedElements + reserve; // plus reservation

        if ((offset + additions) > maxSize) {
            return grow(maxSize, offset, additions);
        }
        return maxSize;
    }


    @Override
    public int grow(int maxSize, int offset, int additions) {
        final int newSize = resizer.grow(maxSize, offset, additions);
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
