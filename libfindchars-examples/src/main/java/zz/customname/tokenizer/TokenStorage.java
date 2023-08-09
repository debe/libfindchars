package zz.customname.tokenizer;

import com.carrotsearch.hppc.ArraySizingStrategy;
import com.carrotsearch.hppc.BoundedProportionalArraySizingStrategy;
import org.knownhosts.libfindchars.api.Growable;

import java.util.Arrays;

public class TokenStorage implements Growable {

    private int[] positions;

    private int[] sizes;

    private final int reserve;

    private final ArraySizingStrategy resizer;

    public TokenStorage(int expectedElements, int reserve) {
        this.positions = new int[expectedElements+reserve];
        this.sizes = new int[expectedElements+reserve];
        this.resizer = new BoundedProportionalArraySizingStrategy();

        this.reserve = reserve;
    }

    @Override
    public int ensureSize(int expectedElements, int elementSize, int offset) {
        final int maxSize = Math.min(sizes.length, positions.length);
        final int additions = expectedElements  + reserve ; // plus reservation

        if ((offset + additions)  >  maxSize) {
            return grow(maxSize, offset, additions);
        }
        return maxSize;
    }


    @Override
    public int grow(int maxSize, int offset, int additions) {
        final int newSize = resizer.grow(maxSize, offset, additions);
        this.positions = Arrays.copyOf(positions, newSize);
        this.sizes = Arrays.copyOf(sizes, newSize);
        return newSize;
    }

    public int[] getSizeBuffer() {
        return sizes;
    }

    public int[] getPositionsBuffer() {
        return positions;
    }
}
