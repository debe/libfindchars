package org.knownhosts.libfindchars.compiler;

import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.api.MatchStorage;

import static org.junit.jupiter.api.Assertions.*;

class MatchStorageTest {

    @Test
    void initialCapacityIncludesReserve() {
        var storage = new MatchStorage(10, 5);
        assertEquals(15, storage.getLiteralBuffer().length);
        assertEquals(15, storage.getPositionsBuffer().length);
    }

    @Test
    void ensureSizeRetainsExistingData() {
        var storage = new MatchStorage(4, 2);
        storage.getPositionsBuffer()[0] = 42L;
        storage.getLiteralBuffer()[0] = (byte) 7;

        storage.ensureSize(100, 0);

        assertEquals(42L, storage.getPositionsBuffer()[0]);
        assertEquals((byte) 7, storage.getLiteralBuffer()[0]);
    }

    @Test
    void growDoublesCapacity() {
        var storage = new MatchStorage(4, 0);
        int oldSize = storage.getLiteralBuffer().length;

        int newSize = storage.ensureSize(oldSize + 1, 0);

        assertTrue(newSize >= oldSize * 2, "Expected at least double: " + newSize + " vs " + oldSize);
    }

    @Test
    void ensureSizeNoOpWhenSufficient() {
        var storage = new MatchStorage(100, 10);
        int size = storage.ensureSize(50, 0);
        assertEquals(110, size);
    }

    @Test
    void growFromOffset() {
        var storage = new MatchStorage(10, 2);
        // Fill to offset 8, then ensure we can write 10 more
        storage.ensureSize(10, 8);
        assertTrue(storage.getLiteralBuffer().length >= 20,
                "Should grow to fit offset(8) + additions(10) + reserve(2)");
    }
}
