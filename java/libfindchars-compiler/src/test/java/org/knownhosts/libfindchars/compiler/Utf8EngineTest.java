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
            positions.add((int) view.getPositionAt(matchStorage, i));
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
            positions.add((int) view.getPositionAt(matchStorage, i));
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
            posLitPairs.add(new int[]{(int) view.getPositionAt(matchStorage, i), view.getLiteralAt(matchStorage, i)});
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
            positions.add((int) view.getPositionAt(matchStorage, i));
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
            positions.add((int) view.getPositionAt(matchStorage, i));
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

    // --- Shared lead-byte / multi-byte edge case tests ---

    @Test
    void sharedLeadByteTwoByteChars() {
        // é = U+00E9 = [C3, A9], ô = U+00F4 = [C3, B4] — both share lead byte 0xC3
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoint("eacute", 0xE9)
                .codepoint("ocirc", 0xF4)
                .build();

        byte eLit = result.literals().get("eacute");
        byte oLit = result.literals().get("ocirc");
        Assertions.assertNotEquals(eLit, oLit, "é and ô must have distinct literal bytes");

        byte[] buf = new byte[bufSize()];
        // é at position 4
        buf[4] = (byte) 0xC3; buf[5] = (byte) 0xA9;
        // ô at position 10
        buf[10] = (byte) 0xC3; buf[11] = (byte) 0xB4;
        // Another é at position 20
        buf[20] = (byte) 0xC3; buf[21] = (byte) 0xA9;

        var matchStorage = new MatchStorage(256, VECTOR_SIZE);
        var view = result.engine().find(MemorySegment.ofArray(buf), matchStorage);

        var positions = new ArrayList<Integer>();
        var literals = new ArrayList<Byte>();
        for (int i = 0; i < view.size(); i++) {
            positions.add((int) view.getPositionAt(matchStorage, i));
            literals.add(view.getLiteralAt(matchStorage, i));
        }

        Assertions.assertEquals(3, view.size(), "Should find all 3 matches");
        Assertions.assertTrue(positions.contains(4), "é at 4");
        Assertions.assertTrue(positions.contains(10), "ô at 10");
        Assertions.assertTrue(positions.contains(20), "é at 20");

        // Verify correct literal assignment
        Assertions.assertEquals(eLit, literals.get(positions.indexOf(4)), "é literal at 4");
        Assertions.assertEquals(oLit, literals.get(positions.indexOf(10)), "ô literal at 10");
        Assertions.assertEquals(eLit, literals.get(positions.indexOf(20)), "é literal at 20");
    }

    @Test
    void multipleSharedLeadByte2ByteChars() {
        // é [C3,A9], ô [C3,B4], ü [C3,BC] — three chars sharing 0xC3 lead
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoint("eacute", 0xE9)
                .codepoint("ocirc", 0xF4)
                .codepoint("uuml", 0xFC)
                .build();

        byte eLit = result.literals().get("eacute");
        byte oLit = result.literals().get("ocirc");
        byte uLit = result.literals().get("uuml");
        var litSet = new HashSet<>(java.util.List.of(eLit, oLit, uLit));
        Assertions.assertEquals(3, litSet.size(), "All three literal bytes must be distinct");

        byte[] buf = new byte[bufSize()];
        buf[2] = (byte) 0xC3; buf[3] = (byte) 0xA9;   // é
        buf[8] = (byte) 0xC3; buf[9] = (byte) 0xB4;   // ô
        buf[14] = (byte) 0xC3; buf[15] = (byte) 0xBC;  // ü

        var matchStorage = new MatchStorage(256, VECTOR_SIZE);
        var view = result.engine().find(MemorySegment.ofArray(buf), matchStorage);

        var positions = new ArrayList<Integer>();
        for (int i = 0; i < view.size(); i++) {
            positions.add((int) view.getPositionAt(matchStorage, i));
        }

        Assertions.assertEquals(3, view.size(), "Should find all 3 chars");
        Assertions.assertTrue(positions.contains(2), "é at 2");
        Assertions.assertTrue(positions.contains(8), "ô at 8");
        Assertions.assertTrue(positions.contains(14), "ü at 14");
    }

    @Test
    void sharedLeadByte3ByteChars() {
        // ™ = U+2122 = [E2, 84, A2], ← = U+2190 = [E2, 86, 90] — shared 0xE2 lead
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoint("tm", 0x2122)
                .codepoint("leftarrow", 0x2190)
                .build();

        byte tmLit = result.literals().get("tm");
        byte laLit = result.literals().get("leftarrow");
        Assertions.assertNotEquals(tmLit, laLit, "™ and ← must have distinct literal bytes");

        byte[] buf = new byte[bufSize()];
        // ™ at position 5
        buf[5] = (byte) 0xE2; buf[6] = (byte) 0x84; buf[7] = (byte) 0xA2;
        // ← at position 15
        buf[15] = (byte) 0xE2; buf[16] = (byte) 0x86; buf[17] = (byte) 0x90;

        var matchStorage = new MatchStorage(256, VECTOR_SIZE);
        var view = result.engine().find(MemorySegment.ofArray(buf), matchStorage);

        var positions = new ArrayList<Integer>();
        for (int i = 0; i < view.size(); i++) {
            positions.add((int) view.getPositionAt(matchStorage, i));
        }

        Assertions.assertEquals(2, view.size(), "Should find both 3-byte chars");
        Assertions.assertTrue(positions.contains(5), "™ at 5");
        Assertions.assertTrue(positions.contains(15), "← at 15");
    }

    @Test
    void mixedAsciiAndSharedLeadByteMultiByte() {
        // ASCII whitespace + punctuation + é + ô + range
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoints("whitespace", '\t', '\n', ' ')
                .codepoints("punct", ',', '.', ';')
                .codepoint("eacute", 0xE9)
                .codepoint("ocirc", 0xF4)
                .range("digits", (byte) '0', (byte) '9')
                .build();

        byte wsLit = result.literals().get("whitespace");
        byte pLit = result.literals().get("punct");
        byte eLit = result.literals().get("eacute");
        byte oLit = result.literals().get("ocirc");
        byte dLit = result.literals().get("digits");

        byte[] buf = new byte[bufSize()];
        buf[0] = '\t';
        buf[3] = ',';
        buf[6] = '5';
        buf[10] = (byte) 0xC3; buf[11] = (byte) 0xA9;  // é
        buf[16] = (byte) 0xC3; buf[17] = (byte) 0xB4;  // ô
        buf[22] = ' ';

        var matchStorage = new MatchStorage(256, VECTOR_SIZE);
        var view = result.engine().find(MemorySegment.ofArray(buf), matchStorage);

        var positions = new ArrayList<Integer>();
        var lits = new ArrayList<Byte>();
        for (int i = 0; i < view.size(); i++) {
            positions.add((int) view.getPositionAt(matchStorage, i));
            lits.add(view.getLiteralAt(matchStorage, i));
        }

        Assertions.assertEquals(6, view.size(), "Should find 6 matches");
        Assertions.assertTrue(positions.contains(0), "\\t at 0");
        Assertions.assertTrue(positions.contains(3), ", at 3");
        Assertions.assertTrue(positions.contains(6), "5 at 6");
        Assertions.assertTrue(positions.contains(10), "é at 10");
        Assertions.assertTrue(positions.contains(16), "ô at 16");
        Assertions.assertTrue(positions.contains(22), "' ' at 22");
    }

    @Test
    void adjacentMultiByteChars() {
        // Two é back-to-back: [C3,A9,C3,A9]
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoint("eacute", 0xE9)
                .build();

        byte eLit = result.literals().get("eacute");

        byte[] buf = new byte[bufSize()];
        buf[10] = (byte) 0xC3; buf[11] = (byte) 0xA9;
        buf[12] = (byte) 0xC3; buf[13] = (byte) 0xA9;

        var matchStorage = new MatchStorage(256, VECTOR_SIZE);
        var view = result.engine().find(MemorySegment.ofArray(buf), matchStorage);

        var positions = new ArrayList<Integer>();
        for (int i = 0; i < view.size(); i++) {
            positions.add((int) view.getPositionAt(matchStorage, i));
            Assertions.assertEquals(eLit, view.getLiteralAt(matchStorage, i));
        }

        Assertions.assertEquals(2, view.size(), "Should find both adjacent é");
        Assertions.assertTrue(positions.contains(10), "First é at 10");
        Assertions.assertTrue(positions.contains(12), "Second é at 12");
    }

    @Test
    void multiByteAtBufferEnd() {
        // 2-byte char at the very last 2 bytes of the buffer (tail processing path)
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoint("eacute", 0xE9)
                .build();

        byte eLit = result.literals().get("eacute");
        int bufLen = VECTOR_SIZE * 2 + 2; // just past a vector boundary + 2 tail bytes
        byte[] buf = new byte[bufLen];
        buf[bufLen - 2] = (byte) 0xC3;
        buf[bufLen - 1] = (byte) 0xA9;

        var matchStorage = new MatchStorage(256, VECTOR_SIZE);
        var view = result.engine().find(MemorySegment.ofArray(buf), matchStorage);

        var positions = new ArrayList<Integer>();
        for (int i = 0; i < view.size(); i++) {
            positions.add((int) view.getPositionAt(matchStorage, i));
        }

        Assertions.assertTrue(positions.contains(bufLen - 2),
                "Should find é at buffer tail position " + (bufLen - 2));
    }

    @Test
    void allMultiByteNoAscii() {
        // Only multi-byte codepoints, no ASCII targets
        var result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoint("eacute", 0xE9)   // [C3, A9]
                .codepoint("ocirc", 0xF4)     // [C3, B4]
                .codepoint("tm", 0x2122)      // [E2, 84, A2]
                .build();

        byte eLit = result.literals().get("eacute");
        byte oLit = result.literals().get("ocirc");
        byte tmLit = result.literals().get("tm");

        byte[] buf = new byte[bufSize()];
        buf[2] = (byte) 0xC3; buf[3] = (byte) 0xA9;   // é
        buf[8] = (byte) 0xC3; buf[9] = (byte) 0xB4;   // ô
        buf[14] = (byte) 0xE2; buf[15] = (byte) 0x84; buf[16] = (byte) 0xA2; // ™

        var matchStorage = new MatchStorage(256, VECTOR_SIZE);
        var view = result.engine().find(MemorySegment.ofArray(buf), matchStorage);

        var positions = new ArrayList<Integer>();
        var lits = new ArrayList<Byte>();
        for (int i = 0; i < view.size(); i++) {
            positions.add((int) view.getPositionAt(matchStorage, i));
            lits.add(view.getLiteralAt(matchStorage, i));
        }

        Assertions.assertEquals(3, view.size(), "Should find all 3 multi-byte chars");
        Assertions.assertTrue(positions.contains(2), "é at 2");
        Assertions.assertTrue(positions.contains(8), "ô at 8");
        Assertions.assertTrue(positions.contains(14), "™ at 14");
    }
}
