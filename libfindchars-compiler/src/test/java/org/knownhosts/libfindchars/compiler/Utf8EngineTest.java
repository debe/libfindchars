package org.knownhosts.libfindchars.compiler;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.generator.Utf8EngineBuilder;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

class Utf8EngineTest {

    // Use SPECIES_256 (32-byte vectors) to avoid the known MatchDecoder truncation
    // bug on AVX-512 where (int) findMask.toLong() loses lanes >= 32.
    static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_256;
    static final int VECTOR_SIZE = SPECIES.vectorByteSize(); // 32

    static int bufSize() {
        return VECTOR_SIZE * 6; // plenty of room
    }

    @Test
    void asciiOnly() {
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoints("whitespace", '\t', '\n', ' ')
                .build();

        byte wsLiteral = result.literals().get("whitespace");
        Assertions.assertNotEquals((byte) 0, wsLiteral);

        byte[] buf = new byte[bufSize()];
        buf[5] = '\t';
        buf[15] = '\n';
        buf[25] = ' ';

        var matchStorage = new MatchStorage(256, VECTOR_SIZE);
        var view = result.engine().find(MemorySegment.ofArray(buf), matchStorage);

        var positions = new ArrayList<Integer>();
        for (int i = 0; i < view.size(); i++) {
            Assertions.assertEquals(wsLiteral, view.getLiteralAt(matchStorage, i));
            positions.add(view.getPositionAt(matchStorage, i));
        }

        Assertions.assertTrue(positions.contains(5), "Should find \\t at 5");
        Assertions.assertTrue(positions.contains(15), "Should find \\n at 15");
        Assertions.assertTrue(positions.contains(25), "Should find ' ' at 25");
        Assertions.assertEquals(3, view.size(), "Exactly 3 matches");
    }

    @Test
    void twoByteChar() {
        // 'é' = U+00E9 = [0xC3, 0xA9]
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoint("eaccute", 0xE9)
                .build();

        byte eLiteral = result.literals().get("eaccute");
        Assertions.assertNotEquals((byte) 0, eLiteral);

        byte[] buf = new byte[bufSize()];
        buf[10] = (byte) 0xC3;
        buf[11] = (byte) 0xA9;
        // Place second occurrence in next chunk
        buf[VECTOR_SIZE + 10] = (byte) 0xC3;
        buf[VECTOR_SIZE + 11] = (byte) 0xA9;

        var matchStorage = new MatchStorage(256, VECTOR_SIZE);
        var view = result.engine().find(MemorySegment.ofArray(buf), matchStorage);

        var positions = new ArrayList<Integer>();
        for (int i = 0; i < view.size(); i++) {
            positions.add(view.getPositionAt(matchStorage, i));
            Assertions.assertEquals(eLiteral, view.getLiteralAt(matchStorage, i));
        }

        Assertions.assertTrue(positions.contains(10), "Should find 'é' at 10");
        Assertions.assertTrue(positions.contains(VECTOR_SIZE + 10), "Should find 'é' at " + (VECTOR_SIZE + 10));
        Assertions.assertFalse(positions.contains(11), "Should not report continuation byte");
        Assertions.assertEquals(2, view.size());
    }

    @Test
    void threeByteChar() {
        // '日' = U+65E5 = [0xE6, 0x97, 0xA5]
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoint("hi", 0x65E5)
                .build();

        byte hiLiteral = result.literals().get("hi");
        Assertions.assertNotEquals((byte) 0, hiLiteral);

        byte[] buf = new byte[bufSize()];
        buf[20] = (byte) 0xE6;
        buf[21] = (byte) 0x97;
        buf[22] = (byte) 0xA5;

        var matchStorage = new MatchStorage(256, VECTOR_SIZE);
        var view = result.engine().find(MemorySegment.ofArray(buf), matchStorage);

        Assertions.assertEquals(1, view.size(), "Exactly 1 match");
        Assertions.assertEquals(20, view.getPositionAt(matchStorage, 0));
        Assertions.assertEquals(hiLiteral, view.getLiteralAt(matchStorage, 0));
    }

    @Test
    void fourByteChar() {
        // '😀' = U+1F600 = [0xF0, 0x9F, 0x98, 0x80]
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoint("grin", 0x1F600)
                .build();

        byte grinLiteral = result.literals().get("grin");
        Assertions.assertNotEquals((byte) 0, grinLiteral);

        byte[] buf = new byte[bufSize()];
        buf[10] = (byte) 0xF0;
        buf[11] = (byte) 0x9F;
        buf[12] = (byte) 0x98;
        buf[13] = (byte) 0x80;

        var matchStorage = new MatchStorage(256, VECTOR_SIZE);
        var view = result.engine().find(MemorySegment.ofArray(buf), matchStorage);

        Assertions.assertEquals(1, view.size(), "Exactly 1 match");
        Assertions.assertEquals(10, view.getPositionAt(matchStorage, 0));
        Assertions.assertEquals(grinLiteral, view.getLiteralAt(matchStorage, 0));
    }

    @Test
    void mixedAsciiAndMultiByte() {
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoints("whitespace", '\t', '\n', ' ')
                .codepoint("eaccute", 0xE9)
                .codepoint("hi", 0x65E5)
                .build();

        byte wsLit = result.literals().get("whitespace");
        byte eLit = result.literals().get("eaccute");
        byte hiLit = result.literals().get("hi");

        byte[] buf = new byte[bufSize()];
        buf[3] = '\t';
        buf[8] = '\n';
        buf[15] = ' ';
        buf[20] = (byte) 0xC3;
        buf[21] = (byte) 0xA9;
        // Place 日 in the next chunk to stay within 32 lanes each
        buf[VECTOR_SIZE + 5] = (byte) 0xE6;
        buf[VECTOR_SIZE + 6] = (byte) 0x97;
        buf[VECTOR_SIZE + 7] = (byte) 0xA5;

        var matchStorage = new MatchStorage(256, VECTOR_SIZE);
        var view = result.engine().find(MemorySegment.ofArray(buf), matchStorage);

        var posLitPairs = new ArrayList<int[]>();
        for (int i = 0; i < view.size(); i++) {
            posLitPairs.add(new int[]{view.getPositionAt(matchStorage, i), view.getLiteralAt(matchStorage, i)});
        }
        var positions = posLitPairs.stream().map(p -> p[0]).toList();

        Assertions.assertTrue(positions.contains(3), "\\t at 3");
        Assertions.assertTrue(positions.contains(8), "\\n at 8");
        Assertions.assertTrue(positions.contains(15), "' ' at 15");
        Assertions.assertTrue(positions.contains(20), "'é' at 20");
        Assertions.assertTrue(positions.contains(VECTOR_SIZE + 5), "'日' at " + (VECTOR_SIZE + 5));

        for (var pair : posLitPairs) {
            if (pair[0] == 3 || pair[0] == 8 || pair[0] == 15) {
                Assertions.assertEquals(wsLit, (byte) pair[1], "whitespace literal at pos " + pair[0]);
            } else if (pair[0] == 20) {
                Assertions.assertEquals(eLit, (byte) pair[1], "é literal at pos 20");
            } else if (pair[0] == VECTOR_SIZE + 5) {
                Assertions.assertEquals(hiLit, (byte) pair[1], "日 literal at pos " + (VECTOR_SIZE + 5));
            }
        }

        Assertions.assertEquals(5, view.size());
    }

    @Test
    void boundarySpanning() {
        // Place 'é' [C3,A9] straddling vector boundary: start byte in last lane
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoint("eaccute", 0xE9)
                .build();

        byte eLit = result.literals().get("eaccute");
        int pos = VECTOR_SIZE - 1; // last byte of first chunk (lane 31)
        byte[] buf = new byte[VECTOR_SIZE * 3];
        buf[pos] = (byte) 0xC3;
        buf[pos + 1] = (byte) 0xA9;

        var matchStorage = new MatchStorage(256, VECTOR_SIZE);
        var view = result.engine().find(MemorySegment.ofArray(buf), matchStorage);

        var positions = new ArrayList<Integer>();
        for (int i = 0; i < view.size(); i++) {
            positions.add(view.getPositionAt(matchStorage, i));
        }

        Assertions.assertTrue(positions.contains(pos),
                "Should find 'é' spanning vector boundary at " + pos);
    }

    @Test
    void noFalsePositivesOnPartialSequence() {
        // Partial match: [E6, 97, 00] — only first 2 bytes of '日', wrong third byte
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoint("hi", 0x65E5)
                .build();

        byte[] buf = new byte[bufSize()];
        buf[10] = (byte) 0xE6;
        buf[11] = (byte) 0x97;
        buf[12] = 0x00; // wrong third byte

        var matchStorage = new MatchStorage(256, VECTOR_SIZE);
        var view = result.engine().find(MemorySegment.ofArray(buf), matchStorage);

        Assertions.assertEquals(0, view.size(), "Partial sequence should not match");
    }

    @Test
    void noCrossByteContamination() {
        // Buffer has '×' [C3,97]: searching for é [C3,A9] + 日 [E6,97,A5]
        // Must NOT match '×' — C3 matches é's r0 but 97 matches 日's r1, not é's r1
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoint("eaccute", 0xE9)
                .codepoint("hi", 0x65E5)
                .build();

        byte[] buf = new byte[bufSize()];
        // Place '×' = [C3, 97] at position 10
        buf[10] = (byte) 0xC3;
        buf[11] = (byte) 0x97;

        // Place a real 'é' at position 20
        buf[20] = (byte) 0xC3;
        buf[21] = (byte) 0xA9;

        var matchStorage = new MatchStorage(256, VECTOR_SIZE);
        var view = result.engine().find(MemorySegment.ofArray(buf), matchStorage);

        var positions = new ArrayList<Integer>();
        for (int i = 0; i < view.size(); i++) {
            positions.add(view.getPositionAt(matchStorage, i));
        }

        Assertions.assertFalse(positions.contains(10), "'×' [C3,97] must NOT match");
        Assertions.assertTrue(positions.contains(20), "Real 'é' [C3,A9] should match");
    }

    @Test
    void literalMapReturnsDistinctBytes() {
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoints("whitespace", '\t', '\n', ' ')
                .codepoint("eaccute", 0xE9)
                .codepoint("hi", 0x65E5)
                .codepoint("grin", 0x1F600)
                .build();

        var literals = result.literals();
        Assertions.assertEquals(4, literals.size());

        var values = new HashSet<>(literals.values());
        Assertions.assertEquals(4, values.size(), "All literal bytes must be distinct");
        for (byte b : literals.values()) {
            Assertions.assertNotEquals((byte) 0, b, "Literal bytes must be non-zero");
        }
    }
}
