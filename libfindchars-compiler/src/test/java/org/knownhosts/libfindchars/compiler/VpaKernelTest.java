package org.knownhosts.libfindchars.compiler;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;
import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.api.VpaKernel;

import static org.junit.jupiter.api.Assertions.*;

class VpaKernelTest {

    private static final VectorSpecies<Byte> SP = ByteVector.SPECIES_PREFERRED;
    private static final ByteVector ZERO = ByteVector.broadcast(SP, (byte) 0);

    @Test
    void prefixXorTogglesAtMarkerPositions() {
        // Place toggle markers at lanes 2 and 5 → inside region is [3,4]
        byte[] input = new byte[SP.vectorByteSize()];
        input[2] = (byte) 0xFF;
        input[5] = (byte) 0xFF;
        var v = ByteVector.fromArray(SP, input, 0);

        var result = VpaKernel.prefixXor(v, ZERO, SP);

        // Lanes 0,1: before first toggle → 0
        assertEquals(0, result.lane(0));
        assertEquals(0, result.lane(1));
        // Lane 2: first toggle → FF (XOR with 0 = FF)
        assertEquals((byte) 0xFF, result.lane(2));
        // Lanes 3,4: inside region → FF
        assertEquals((byte) 0xFF, result.lane(3));
        assertEquals((byte) 0xFF, result.lane(4));
        // Lane 5: second toggle → 0 (FF XOR FF = 0)
        assertEquals(0, result.lane(5));
        // Lane 6+: outside → 0
        if (SP.vectorByteSize() > 6) {
            assertEquals(0, result.lane(6));
        }
    }

    @Test
    void prefixXorAllZerosReturnsAllZeros() {
        var result = VpaKernel.prefixXor(ZERO, ZERO, SP);
        assertTrue(result.eq(ZERO).allTrue());
    }

    @Test
    void prefixXorSingleToggleAtStart() {
        // Toggle at lane 0 → all lanes from 0 onward are FF
        byte[] input = new byte[SP.vectorByteSize()];
        input[0] = (byte) 0xFF;
        var v = ByteVector.fromArray(SP, input, 0);

        var result = VpaKernel.prefixXor(v, ZERO, SP);

        for (int i = 0; i < SP.vectorByteSize(); i++) {
            assertEquals((byte) 0xFF, result.lane(i), "lane " + i);
        }
    }

    @Test
    void prefixSumCountsUpAndDown() {
        // +1 at lane 1, +1 at lane 3, -1 at lane 5
        // Expected: 0, 1, 1, 2, 2, 1, 1, 1, ...
        byte[] input = new byte[SP.vectorByteSize()];
        input[1] = 1;
        input[3] = 1;
        input[5] = (byte) -1;
        var v = ByteVector.fromArray(SP, input, 0);

        var result = VpaKernel.prefixSum(v, ZERO, SP);

        assertEquals(0, result.lane(0));
        assertEquals(1, result.lane(1));
        assertEquals(1, result.lane(2));
        assertEquals(2, result.lane(3));
        assertEquals(2, result.lane(4));
        assertEquals(1, result.lane(5));
        if (SP.vectorByteSize() > 6) {
            assertEquals(1, result.lane(6));
        }
    }

    @Test
    void prefixSumAllZerosReturnsAllZeros() {
        var result = VpaKernel.prefixSum(ZERO, ZERO, SP);
        assertTrue(result.eq(ZERO).allTrue());
    }

    @Test
    void prefixSumMonotonicIncrease() {
        // +1 at every lane → 1, 2, 3, ...
        byte[] input = new byte[SP.vectorByteSize()];
        for (int i = 0; i < input.length; i++) input[i] = 1;
        var v = ByteVector.fromArray(SP, input, 0);

        var result = VpaKernel.prefixSum(v, ZERO, SP);

        for (int i = 0; i < SP.vectorByteSize(); i++) {
            assertEquals((byte) (i + 1), result.lane(i), "lane " + i);
        }
    }

    @Test
    void shiftRProducesCorrectShuffle() {
        // Fill lanes with 0,1,2,3,...
        byte[] input = new byte[SP.vectorByteSize()];
        for (int i = 0; i < input.length; i++) input[i] = (byte) i;
        var v = ByteVector.fromArray(SP, input, 0);

        // Shift right by 3 → first 3 lanes from zero fallback, rest shifted
        var shifted = v.rearrange(VpaKernel.shiftR(SP, 3), ZERO);

        assertEquals(0, shifted.lane(0));
        assertEquals(0, shifted.lane(1));
        assertEquals(0, shifted.lane(2));
        assertEquals(0, shifted.lane(3)); // lane 0 of original
        assertEquals(1, shifted.lane(4)); // lane 1 of original
    }
}
