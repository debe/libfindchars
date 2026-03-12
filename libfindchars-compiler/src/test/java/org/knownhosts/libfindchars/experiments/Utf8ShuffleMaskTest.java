package org.knownhosts.libfindchars.experiments;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.api.FindMask;
import org.knownhosts.libfindchars.compiler.AsciiLiteralGroup;
import org.knownhosts.libfindchars.compiler.ByteLiteral;
import org.knownhosts.libfindchars.compiler.LiteralCompiler;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.java_smt.api.SolverException;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

class Utf8ShuffleMaskTest {

    static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    static final int VECTOR_SIZE = SPECIES.vectorByteSize();

    // UTF-8 classification LUT: high nibble -> byte class
    // 0x0-0x7 -> 1 (ASCII), 0x8-0xB -> 0 (continuation), 0xC-0xD -> 2 (2-byte start),
    // 0xE -> 3 (3-byte start), 0xF -> 4 (4-byte start)
    static final byte[] CLASSIFY_TABLE = {
        1, 1, 1, 1, 1, 1, 1, 1,  // 0x0-0x7: ASCII
        0, 0, 0, 0,              // 0x8-0xB: continuation
        2, 2,                    // 0xC-0xD: 2-byte start
        3,                       // 0xE: 3-byte start
        4                        // 0xF: 4-byte start
    };

    static final ByteVector CLASSIFY_VEC = ByteVector.fromArray(SPECIES,
            java.util.Arrays.copyOf(CLASSIFY_TABLE, VECTOR_SIZE), 0);
    static final ByteVector LOW_MASK = ByteVector.broadcast(SPECIES, 0x0f);
    static final ByteVector ZERO = ByteVector.broadcast(SPECIES, (byte) 0);

    // Pre-solved masks for multi-character test
    static List<FindMask> round0Masks;
    static List<FindMask> round1Masks;
    static List<FindMask> round2Masks;

    @BeforeAll
    static void solveAllMasks() throws InvalidConfigurationException, InterruptedException, SolverException {
        try (var compiler = new LiteralCompiler()) {
            // Round 0: first bytes of all target chars
            // ASCII whitespace: \t=0x09, \n=0x0A, ' '=0x20
            // 'é' starts with 0xC3, '日' starts with 0xE6
            var asciiWs = new ByteLiteral("whitespace", chars(0x09, 0x0A, 0x20));
            var eAccuteR0 = new ByteLiteral("eaccute_r0", chars(0xC3));
            var hiR0 = new ByteLiteral("hi_r0", chars(0xE6));
            var r0Group = new AsciiLiteralGroup("round0", asciiWs, eAccuteR0, hiR0);
            round0Masks = compiler.solve(r0Group);

            // Round 1: second bytes (applied to slice(1, next))
            // 'é': byte[1]=0xA9
            // '日': byte[1]=0x97
            var eAccuteR1 = new ByteLiteral("eaccute_r1", chars(0xA9));
            var hiR1 = new ByteLiteral("hi_r1", chars(0x97));
            var r1Group = new AsciiLiteralGroup("round1", eAccuteR1, hiR1);
            round1Masks = compiler.solve(r1Group);

            // Round 2: third bytes (applied to slice(2, next))
            // '日': byte[2]=0xA5
            var hiR2 = new ByteLiteral("hi_r2", chars(0xA5));
            var r2Group = new AsciiLiteralGroup("round2", hiR2);
            round2Masks = compiler.solve(r2Group);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- Helper methods ---

    static char[] chars(int... values) {
        char[] result = new char[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (char) (values[i] & 0xFF);
        }
        return result;
    }

    static ByteVector classifyUtf8Bytes(ByteVector input) {
        return CLASSIFY_VEC.rearrange(
                input.lanewise(VectorOperators.LSHR, 4).and(LOW_MASK).toShuffle());
    }

    static ByteVector applyShuffleMask(ByteVector input, FindMask mask) {
        var lowLUT = ByteVector.fromArray(SPECIES,
                java.util.Arrays.copyOf(mask.lowNibbleMask(), VECTOR_SIZE), 0);
        var highLUT = ByteVector.fromArray(SPECIES,
                java.util.Arrays.copyOf(mask.highNibbleMask(), VECTOR_SIZE), 0);

        var lo = lowLUT.rearrange(input.and(LOW_MASK).toShuffle());
        var hi = highLUT.rearrange(input.lanewise(VectorOperators.LSHR, 4).and(LOW_MASK).toShuffle());
        var raw = lo.and(hi);

        // Filter: only keep lanes that exactly match a known literal value.
        // Raw AND can produce non-zero for non-target nibble combos; we must
        // zero those out — same logic as ShuffleMaskOp.apply().
        var cleaned = ZERO;
        for (byte litVal : mask.literals().values()) {
            var litVec = ByteVector.broadcast(SPECIES, litVal);
            var matches = raw.compare(VectorOperators.EQ, litVec);
            cleaned = cleaned.blend(raw, matches);
        }
        return cleaned;
    }

    static byte[] buildTestBuffer(int size, byte[][] sequences, int[] positions) {
        byte[] buffer = new byte[size];
        for (int i = 0; i < sequences.length; i++) {
            System.arraycopy(sequences[i], 0, buffer, positions[i], sequences[i].length);
        }
        return buffer;
    }

    static List<int[]> findAllInBuffer(byte[] buffer, List<FindMask> r0, List<FindMask> r1,
                                        List<FindMask> r2, int maxRounds) {
        // Returns list of [position, literalId] pairs
        List<int[]> results = new ArrayList<>();
        int len = buffer.length;

        for (int offset = 0; offset + VECTOR_SIZE <= len; offset += VECTOR_SIZE) {
            var chunk = ByteVector.fromArray(SPECIES, buffer, offset);

            // Ensure we have a next chunk for slice operations
            byte[] nextChunkBytes = new byte[VECTOR_SIZE];
            int nextStart = offset + VECTOR_SIZE;
            int copyLen = Math.min(VECTOR_SIZE, len - nextStart);
            if (copyLen > 0) {
                System.arraycopy(buffer, nextStart, nextChunkBytes, 0, copyLen);
            }
            var nextChunk = ByteVector.fromArray(SPECIES, nextChunkBytes, 0);

            var classify = classifyUtf8Bytes(chunk);
            var r0Result = applyShuffleMask(chunk, r0.getFirst());

            // Round 1: slice(1, next)
            ByteVector r1Result = ZERO;
            if (maxRounds >= 2 && r1 != null) {
                var shifted1 = chunk.slice(1, nextChunk);
                r1Result = applyShuffleMask(shifted1, r1.getFirst());
            }

            // Round 2: slice(2, next)
            ByteVector r2Result = ZERO;
            if (maxRounds >= 3 && r2 != null) {
                var shifted2 = chunk.slice(2, nextChunk);
                r2Result = applyShuffleMask(shifted2, r2.getFirst());
            }

            // Extract results per lane
            byte[] classBytes = new byte[VECTOR_SIZE];
            byte[] r0Bytes = new byte[VECTOR_SIZE];
            byte[] r1Bytes = new byte[VECTOR_SIZE];
            byte[] r2Bytes = new byte[VECTOR_SIZE];
            classify.intoArray(classBytes, 0);
            r0Result.intoArray(r0Bytes, 0);
            r1Result.intoArray(r1Bytes, 0);
            r2Result.intoArray(r2Bytes, 0);

            for (int lane = 0; lane < VECTOR_SIZE; lane++) {
                int cls = classBytes[lane] & 0xFF;
                int id0 = r0Bytes[lane] & 0xFF;
                int id1 = r1Bytes[lane] & 0xFF;
                int id2 = r2Bytes[lane] & 0xFF;

                if (cls == 1 && id0 != 0) {
                    // ASCII hit
                    results.add(new int[]{offset + lane, id0});
                } else if (cls == 2 && id0 != 0 && id1 != 0) {
                    // 2-byte UTF-8 hit
                    results.add(new int[]{offset + lane, id1});
                } else if (cls == 3 && id0 != 0 && id1 != 0 && id2 != 0) {
                    // 3-byte UTF-8 hit
                    results.add(new int[]{offset + lane, id2});
                }
            }
        }
        return results;
    }

    // --- Foundation tests ---

    @Test
    void utf8Classification() {
        // Build a vector with one byte from each category
        byte[] testBytes = new byte[VECTOR_SIZE];
        testBytes[0] = 0x41;        // ASCII 'A' -> class 1
        testBytes[1] = 0x09;        // ASCII tab -> class 1
        testBytes[2] = (byte) 0x80; // continuation -> class 0
        testBytes[3] = (byte) 0xBF; // continuation -> class 0
        testBytes[4] = (byte) 0xC3; // 2-byte start -> class 2
        testBytes[5] = (byte) 0xDF; // 2-byte start -> class 2
        testBytes[6] = (byte) 0xE6; // 3-byte start -> class 3
        testBytes[7] = (byte) 0xEF; // 3-byte start -> class 3
        testBytes[8] = (byte) 0xF0; // 4-byte start -> class 4
        testBytes[9] = (byte) 0xF4; // 4-byte start -> class 4
        testBytes[10] = 0x00;       // ASCII null -> class 1
        testBytes[11] = 0x7F;       // ASCII DEL -> class 1

        var input = ByteVector.fromArray(SPECIES, testBytes, 0);
        var result = classifyUtf8Bytes(input);
        byte[] resultBytes = new byte[VECTOR_SIZE];
        result.intoArray(resultBytes, 0);

        Assertions.assertEquals(1, resultBytes[0], "ASCII 'A'");
        Assertions.assertEquals(1, resultBytes[1], "ASCII tab");
        Assertions.assertEquals(0, resultBytes[2], "continuation 0x80");
        Assertions.assertEquals(0, resultBytes[3], "continuation 0xBF");
        Assertions.assertEquals(2, resultBytes[4], "2-byte start 0xC3");
        Assertions.assertEquals(2, resultBytes[5], "2-byte start 0xDF");
        Assertions.assertEquals(3, resultBytes[6], "3-byte start 0xE6");
        Assertions.assertEquals(3, resultBytes[7], "3-byte start 0xEF");
        Assertions.assertEquals(4, resultBytes[8], "4-byte start 0xF0");
        Assertions.assertEquals(4, resultBytes[9], "4-byte start 0xF4");
        Assertions.assertEquals(1, resultBytes[10], "ASCII null");
        Assertions.assertEquals(1, resultBytes[11], "ASCII DEL");
    }

    @Test
    void shuffleMaskFullByteRange() throws Exception {
        // Verify the nibble shuffle trick works for bytes > 0x7F
        try (var compiler = new LiteralCompiler()) {
            var lit = new ByteLiteral("highbytes", chars(0xC3, 0xE6, 0xF0, 0xA9));
            var group = new AsciiLiteralGroup("fullrange", lit);
            var masks = compiler.solve(group);

            var mask = masks.getFirst();
            // Verify each byte produces the correct literal ID via nibble AND
            byte expectedLiteral = mask.literalOf("highbytes");
            for (int val : new int[]{0xC3, 0xE6, 0xF0, 0xA9}) {
                int lo = val & 0x0F;
                int hi = (val >> 4) & 0x0F;
                byte result = (byte) (mask.lowNibbleMask()[lo] & mask.highNibbleMask()[hi]);
                Assertions.assertEquals(expectedLiteral, result,
                        "Nibble AND failed for 0x" + Integer.toHexString(val));
            }

            // Also verify via vector operation
            byte[] testData = new byte[VECTOR_SIZE];
            testData[0] = (byte) 0xC3;
            testData[3] = (byte) 0xE6;
            testData[7] = (byte) 0xF0;
            testData[11] = (byte) 0xA9;
            var input = ByteVector.fromArray(SPECIES, testData, 0);
            var vecResult = applyShuffleMask(input, mask);
            byte[] resultBytes = new byte[VECTOR_SIZE];
            vecResult.intoArray(resultBytes, 0);

            Assertions.assertEquals(expectedLiteral, resultBytes[0], "Vector: 0xC3");
            Assertions.assertEquals(expectedLiteral, resultBytes[3], "Vector: 0xE6");
            Assertions.assertEquals(expectedLiteral, resultBytes[7], "Vector: 0xF0");
            Assertions.assertEquals(expectedLiteral, resultBytes[11], "Vector: 0xA9");
            Assertions.assertEquals(0, resultBytes[1], "Non-target lane should be 0");
        }
    }

    // --- Single-character detection tests ---

    @Test
    void findAsciiChar() throws Exception {
        try (var compiler = new LiteralCompiler()) {
            var lit = new ByteLiteral("A", chars(0x41));
            var group = new AsciiLiteralGroup("r0", lit);
            var masks = compiler.solve(group);

            int bufSize = Math.max(512, VECTOR_SIZE * 2);
            byte[] buffer = new byte[bufSize];
            buffer[5] = 0x41;
            buffer[100] = 0x41;
            buffer[VECTOR_SIZE + 3] = 0x41;

            var results = findAllInBuffer(buffer, masks, null, null, 1);
            var positions = results.stream().map(r -> r[0]).toList();

            Assertions.assertTrue(positions.contains(5));
            Assertions.assertTrue(positions.contains(100));
            if (VECTOR_SIZE + 3 < bufSize - VECTOR_SIZE) {
                Assertions.assertTrue(positions.contains(VECTOR_SIZE + 3));
            }
        }
    }

    @Test
    void findTwoByteChar() throws Exception {
        // 'é' = [0xC3, 0xA9]
        try (var compiler = new LiteralCompiler()) {
            var r0Lit = new ByteLiteral("e_r0", chars(0xC3));
            var r0Group = new AsciiLiteralGroup("r0", r0Lit);
            var r0Masks = compiler.solve(r0Group);

            var r1Lit = new ByteLiteral("e_r1", chars(0xA9));
            var r1Group = new AsciiLiteralGroup("r1", r1Lit);
            var r1Masks = compiler.solve(r1Group);

            int bufSize = Math.max(512, VECTOR_SIZE * 2);
            byte[] buffer = new byte[bufSize];
            // Place 'é' at position 10
            buffer[10] = (byte) 0xC3;
            buffer[11] = (byte) 0xA9;
            // Place 'é' at position 50
            buffer[50] = (byte) 0xC3;
            buffer[51] = (byte) 0xA9;

            var results = findAllInBuffer(buffer, r0Masks, r1Masks, null, 2);
            var positions = results.stream().map(r -> r[0]).toList();

            Assertions.assertTrue(positions.contains(10), "Should find 'é' at position 10");
            Assertions.assertTrue(positions.contains(50), "Should find 'é' at position 50");
            Assertions.assertFalse(positions.contains(11), "Should not report continuation byte position");
            Assertions.assertFalse(positions.contains(51), "Should not report continuation byte position");
        }
    }

    @Test
    void findThreeByteChar() throws Exception {
        // '日' = [0xE6, 0x97, 0xA5]
        try (var compiler = new LiteralCompiler()) {
            var r0Lit = new ByteLiteral("hi_r0", chars(0xE6));
            var r0Group = new AsciiLiteralGroup("r0", r0Lit);
            var r0Masks = compiler.solve(r0Group);

            var r1Lit = new ByteLiteral("hi_r1", chars(0x97));
            var r1Group = new AsciiLiteralGroup("r1", r1Lit);
            var r1Masks = compiler.solve(r1Group);

            var r2Lit = new ByteLiteral("hi_r2", chars(0xA5));
            var r2Group = new AsciiLiteralGroup("r2", r2Lit);
            var r2Masks = compiler.solve(r2Group);

            int bufSize = Math.max(512, VECTOR_SIZE * 2);
            byte[] buffer = new byte[bufSize];
            buffer[20] = (byte) 0xE6;
            buffer[21] = (byte) 0x97;
            buffer[22] = (byte) 0xA5;

            var results = findAllInBuffer(buffer, r0Masks, r1Masks, r2Masks, 3);
            var positions = results.stream().map(r -> r[0]).toList();

            Assertions.assertTrue(positions.contains(20), "Should find '日' at position 20");
            Assertions.assertFalse(positions.contains(21), "Should not report byte[1] position");
            Assertions.assertFalse(positions.contains(22), "Should not report byte[2] position");
        }
    }

    @Test
    void findFourByteChar() throws Exception {
        // '😀' = [0xF0, 0x9F, 0x98, 0x80]
        try (var compiler = new LiteralCompiler()) {
            var r0Lit = new ByteLiteral("emoji_r0", chars(0xF0));
            var r0Group = new AsciiLiteralGroup("r0", r0Lit);
            var r0Masks = compiler.solve(r0Group);

            var r1Lit = new ByteLiteral("emoji_r1", chars(0x9F));
            var r1Group = new AsciiLiteralGroup("r1", r1Lit);
            var r1Masks = compiler.solve(r1Group);

            var r2Lit = new ByteLiteral("emoji_r2", chars(0x98));
            var r2Group = new AsciiLiteralGroup("r2", r2Lit);
            var r2Masks = compiler.solve(r2Group);

            var r3Lit = new ByteLiteral("emoji_r3", chars(0x80));
            var r3Group = new AsciiLiteralGroup("r3", r3Lit);
            var r3Masks = compiler.solve(r3Group);

            int bufSize = Math.max(512, VECTOR_SIZE * 2);
            byte[] buffer = new byte[bufSize];
            buffer[30] = (byte) 0xF0;
            buffer[31] = (byte) 0x9F;
            buffer[32] = (byte) 0x98;
            buffer[33] = (byte) 0x80;

            // Use extended findAll with 4 rounds
            List<int[]> results = findAllInBuffer4(buffer, r0Masks, r1Masks, r2Masks, r3Masks);
            var positions = results.stream().map(r -> r[0]).toList();

            Assertions.assertTrue(positions.contains(30), "Should find '😀' at position 30");
            Assertions.assertEquals(1, positions.size(), "Should find exactly 1 match");
        }
    }

    // 4-round variant of findAllInBuffer
    static List<int[]> findAllInBuffer4(byte[] buffer, List<FindMask> r0, List<FindMask> r1,
                                         List<FindMask> r2, List<FindMask> r3) {
        List<int[]> results = new ArrayList<>();
        int len = buffer.length;

        for (int offset = 0; offset + VECTOR_SIZE <= len; offset += VECTOR_SIZE) {
            var chunk = ByteVector.fromArray(SPECIES, buffer, offset);
            byte[] nextChunkBytes = new byte[VECTOR_SIZE];
            int nextStart = offset + VECTOR_SIZE;
            int copyLen = Math.min(VECTOR_SIZE, len - nextStart);
            if (copyLen > 0) {
                System.arraycopy(buffer, nextStart, nextChunkBytes, 0, copyLen);
            }
            var nextChunk = ByteVector.fromArray(SPECIES, nextChunkBytes, 0);

            var classify = classifyUtf8Bytes(chunk);
            var r0Result = applyShuffleMask(chunk, r0.getFirst());
            var r1Result = applyShuffleMask(chunk.slice(1, nextChunk), r1.getFirst());
            var r2Result = applyShuffleMask(chunk.slice(2, nextChunk), r2.getFirst());
            var r3Result = applyShuffleMask(chunk.slice(3, nextChunk), r3.getFirst());

            byte[] classBytes = new byte[VECTOR_SIZE];
            byte[] r0b = new byte[VECTOR_SIZE], r1b = new byte[VECTOR_SIZE];
            byte[] r2b = new byte[VECTOR_SIZE], r3b = new byte[VECTOR_SIZE];
            classify.intoArray(classBytes, 0);
            r0Result.intoArray(r0b, 0);
            r1Result.intoArray(r1b, 0);
            r2Result.intoArray(r2b, 0);
            r3Result.intoArray(r3b, 0);

            for (int lane = 0; lane < VECTOR_SIZE; lane++) {
                int cls = classBytes[lane] & 0xFF;
                if (cls == 4 && (r0b[lane] & 0xFF) != 0 && (r1b[lane] & 0xFF) != 0
                        && (r2b[lane] & 0xFF) != 0 && (r3b[lane] & 0xFF) != 0) {
                    results.add(new int[]{offset + lane, r3b[lane] & 0xFF});
                }
            }
        }
        return results;
    }

    // --- Multi-character simultaneous detection (the key test) ---

    @Test
    void findMixedAsciiAndUtf8Simultaneously() {
        // Uses pre-solved masks from @BeforeAll
        // Detect: \t(0x09), \n(0x0A), ' '(0x20), 'é'(C3 A9), '日'(E6 97 A5)
        int bufSize = Math.max(512, VECTOR_SIZE * 2);
        byte[] buffer = new byte[bufSize];

        // Place ASCII chars
        buffer[5] = 0x09;   // \t
        buffer[15] = 0x0A;  // \n
        buffer[25] = 0x20;  // ' '

        // Place 'é' at position 40
        buffer[40] = (byte) 0xC3;
        buffer[41] = (byte) 0xA9;

        // Place '日' at position 60
        buffer[60] = (byte) 0xE6;
        buffer[61] = (byte) 0x97;
        buffer[62] = (byte) 0xA5;

        var results = findAllInBuffer(buffer, round0Masks, round1Masks, round2Masks, 3);
        var positions = results.stream().map(r -> r[0]).toList();

        // All 5 characters should be found at their start positions
        Assertions.assertTrue(positions.contains(5), "Should find \\t at 5");
        Assertions.assertTrue(positions.contains(15), "Should find \\n at 15");
        Assertions.assertTrue(positions.contains(25), "Should find ' ' at 25");
        Assertions.assertTrue(positions.contains(40), "Should find 'é' at 40");
        Assertions.assertTrue(positions.contains(60), "Should find '日' at 60");

        // Continuation byte positions should NOT appear
        Assertions.assertFalse(positions.contains(41), "Should not report é byte[1]");
        Assertions.assertFalse(positions.contains(61), "Should not report 日 byte[1]");
        Assertions.assertFalse(positions.contains(62), "Should not report 日 byte[2]");

        // Verify distinct literal IDs per character type
        byte wsLiteral = round0Masks.getFirst().literalOf("whitespace");
        byte eR1Literal = round1Masks.getFirst().literalOf("eaccute_r1");
        byte hiR2Literal = round2Masks.getFirst().literalOf("hi_r2");

        // Check literal IDs in results
        for (int[] r : results) {
            if (r[0] == 5 || r[0] == 15 || r[0] == 25) {
                Assertions.assertEquals(wsLiteral & 0xFF, r[1], "ASCII whitespace literal ID at pos " + r[0]);
            } else if (r[0] == 40) {
                Assertions.assertEquals(eR1Literal & 0xFF, r[1], "'é' literal ID from round 1");
            } else if (r[0] == 60) {
                Assertions.assertEquals(hiR2Literal & 0xFF, r[1], "'日' literal ID from round 2");
            }
        }
    }

    // --- Correctness tests ---

    @Test
    void resultIsOnlyCharacterStarts() throws Exception {
        // '日' at pos 10: only pos 10 reported, never 11 or 12
        try (var compiler = new LiteralCompiler()) {
            var r0Lit = new ByteLiteral("hi_r0", chars(0xE6));
            var r0Group = new AsciiLiteralGroup("r0", r0Lit);
            var r0M = compiler.solve(r0Group);

            var r1Lit = new ByteLiteral("hi_r1", chars(0x97));
            var r1Group = new AsciiLiteralGroup("r1", r1Lit);
            var r1M = compiler.solve(r1Group);

            var r2Lit = new ByteLiteral("hi_r2", chars(0xA5));
            var r2Group = new AsciiLiteralGroup("r2", r2Lit);
            var r2M = compiler.solve(r2Group);

            int bufSize = Math.max(512, VECTOR_SIZE * 2);
            byte[] buffer = new byte[bufSize];
            buffer[10] = (byte) 0xE6;
            buffer[11] = (byte) 0x97;
            buffer[12] = (byte) 0xA5;

            var results = findAllInBuffer(buffer, r0M, r1M, r2M, 3);

            Assertions.assertEquals(1, results.size(), "Exactly one match");
            Assertions.assertEquals(10, results.getFirst()[0], "Match at start byte only");
        }
    }

    @Test
    void noFalsePositives_partialSequences() throws Exception {
        // Partial match [E6, 97, 00] should yield zero results
        try (var compiler = new LiteralCompiler()) {
            var r0Lit = new ByteLiteral("hi_r0", chars(0xE6));
            var r0Group = new AsciiLiteralGroup("r0", r0Lit);
            var r0M = compiler.solve(r0Group);

            var r1Lit = new ByteLiteral("hi_r1", chars(0x97));
            var r1Group = new AsciiLiteralGroup("r1", r1Lit);
            var r1M = compiler.solve(r1Group);

            var r2Lit = new ByteLiteral("hi_r2", chars(0xA5));
            var r2Group = new AsciiLiteralGroup("r2", r2Lit);
            var r2M = compiler.solve(r2Group);

            int bufSize = Math.max(512, VECTOR_SIZE * 2);
            byte[] buffer = new byte[bufSize];
            // Partial: first two bytes of '日' but wrong third byte
            buffer[10] = (byte) 0xE6;
            buffer[11] = (byte) 0x97;
            buffer[12] = 0x00; // wrong third byte

            var results = findAllInBuffer(buffer, r0M, r1M, r2M, 3);

            Assertions.assertEquals(0, results.size(), "Partial sequence should not match");
        }
    }

    @Test
    void multipleOccurrences() throws Exception {
        // Multiple instances of same char
        try (var compiler = new LiteralCompiler()) {
            var r0Lit = new ByteLiteral("e_r0", chars(0xC3));
            var r0Group = new AsciiLiteralGroup("r0", r0Lit);
            var r0M = compiler.solve(r0Group);

            var r1Lit = new ByteLiteral("e_r1", chars(0xA9));
            var r1Group = new AsciiLiteralGroup("r1", r1Lit);
            var r1M = compiler.solve(r1Group);

            int bufSize = Math.max(512, VECTOR_SIZE * 2);
            byte[] buffer = new byte[bufSize];
            // Place 'é' at 4 positions
            int[] positions = {10, 30, 50, 70};
            for (int pos : positions) {
                buffer[pos] = (byte) 0xC3;
                buffer[pos + 1] = (byte) 0xA9;
            }

            var results = findAllInBuffer(buffer, r0M, r1M, null, 2);
            var foundPositions = results.stream().map(r -> r[0]).toList();

            Assertions.assertEquals(4, foundPositions.size(), "Should find all 4 occurrences");
            for (int pos : positions) {
                Assertions.assertTrue(foundPositions.contains(pos), "Should find 'é' at " + pos);
            }
        }
    }

    // --- Edge handling tests ---

    @Test
    void sequenceSpansVectorBoundary() throws Exception {
        // '日' at VECTOR_SIZE-2 straddles chunks
        try (var compiler = new LiteralCompiler()) {
            var r0Lit = new ByteLiteral("hi_r0", chars(0xE6));
            var r0Group = new AsciiLiteralGroup("r0", r0Lit);
            var r0M = compiler.solve(r0Group);

            var r1Lit = new ByteLiteral("hi_r1", chars(0x97));
            var r1Group = new AsciiLiteralGroup("r1", r1Lit);
            var r1M = compiler.solve(r1Group);

            var r2Lit = new ByteLiteral("hi_r2", chars(0xA5));
            var r2Group = new AsciiLiteralGroup("r2", r2Lit);
            var r2M = compiler.solve(r2Group);

            int bufSize = VECTOR_SIZE * 3;
            byte[] buffer = new byte[bufSize];
            int pos = VECTOR_SIZE - 2; // start byte in chunk 0, bytes 1+2 in chunk 1
            buffer[pos] = (byte) 0xE6;
            buffer[pos + 1] = (byte) 0x97;
            buffer[pos + 2] = (byte) 0xA5;

            var results = findAllInBuffer(buffer, r0M, r1M, r2M, 3);
            var foundPositions = results.stream().map(r -> r[0]).toList();

            Assertions.assertTrue(foundPositions.contains(pos),
                    "Should find '日' spanning vector boundary at " + pos);
        }
    }

    @Test
    void allStraddlePositions() throws Exception {
        // 4-byte char at all 3 straddle positions (1+3, 2+2, 3+1)
        // '😀' = [F0, 9F, 98, 80]
        try (var compiler = new LiteralCompiler()) {
            var r0Lit = new ByteLiteral("emoji_r0", chars(0xF0));
            var r0M = compiler.solve(new AsciiLiteralGroup("r0", r0Lit));

            var r1Lit = new ByteLiteral("emoji_r1", chars(0x9F));
            var r1M = compiler.solve(new AsciiLiteralGroup("r1", r1Lit));

            var r2Lit = new ByteLiteral("emoji_r2", chars(0x98));
            var r2M = compiler.solve(new AsciiLiteralGroup("r2", r2Lit));

            var r3Lit = new ByteLiteral("emoji_r3", chars(0x80));
            var r3M = compiler.solve(new AsciiLiteralGroup("r3", r3Lit));

            // Test each straddle position
            for (int straddleOffset : new int[]{1, 2, 3}) {
                int pos = VECTOR_SIZE - straddleOffset;
                int bufSize = VECTOR_SIZE * 3;
                byte[] buffer = new byte[bufSize];
                buffer[pos] = (byte) 0xF0;
                buffer[pos + 1] = (byte) 0x9F;
                buffer[pos + 2] = (byte) 0x98;
                buffer[pos + 3] = (byte) 0x80;

                var results = findAllInBuffer4(buffer, r0M, r1M, r2M, r3M);
                var foundPositions = results.stream().map(r -> r[0]).toList();

                Assertions.assertTrue(foundPositions.contains(pos),
                        "Should find '😀' at straddle position " + pos + " (offset " + straddleOffset + ")");
            }
        }
    }

    // --- Integration test ---

    @Test
    void mixedRealUtf8Text() {
        // Real UTF-8 string: "Hello\t日本語\nélan"
        // H=48, e=65, l=6C, l=6C, o=6F, \t=09,
        // 日=[E6,97,A5], 本=[E6,9C,AC], 語=[E8,AA,9E],
        // \n=0A, é=[C3,A9], l=6C, a=61, n=6E
        byte[] buffer = new byte[Math.max(512, VECTOR_SIZE * 2)];
        int i = 0;
        buffer[i++] = 0x48; // H
        buffer[i++] = 0x65; // e
        buffer[i++] = 0x6C; // l
        buffer[i++] = 0x6C; // l
        buffer[i++] = 0x6F; // o
        buffer[i++] = 0x09; // \t  (pos 5)
        buffer[i++] = (byte) 0xE6; buffer[i++] = (byte) 0x97; buffer[i++] = (byte) 0xA5; // 日 (pos 6)
        buffer[i++] = (byte) 0xE6; buffer[i++] = (byte) 0x9C; buffer[i++] = (byte) 0xAC; // 本 (pos 9)
        buffer[i++] = (byte) 0xE8; buffer[i++] = (byte) 0xAA; buffer[i++] = (byte) 0x9E; // 語 (pos 12)
        buffer[i++] = 0x0A; // \n  (pos 15)
        buffer[i++] = (byte) 0xC3; buffer[i++] = (byte) 0xA9; // é (pos 16)
        buffer[i++] = 0x6C; // l
        buffer[i++] = 0x61; // a
        buffer[i] = 0x6E; // n

        // Use pre-solved masks: searching for \t, \n, ' ', 'é', '日'
        var results = findAllInBuffer(buffer, round0Masks, round1Masks, round2Masks, 3);
        var positions = results.stream().map(r -> r[0]).toList();

        // Should find \t at 5, '日' at 6, \n at 15, 'é' at 16
        Assertions.assertTrue(positions.contains(5), "Should find \\t at 5");
        Assertions.assertTrue(positions.contains(6), "Should find '日' at 6");
        Assertions.assertTrue(positions.contains(15), "Should find \\n at 15");
        Assertions.assertTrue(positions.contains(16), "Should find 'é' at 16");

        // '本' at pos 9 also starts with 0xE6 but has different byte[1]=0x9C
        // Our round1 mask only matches 0x97 (for '日'), so '本' should NOT match
        Assertions.assertFalse(positions.contains(9), "'本' should not match (different byte[1])");

        // '語' starts with 0xE8, which is not in round0 masks, so should NOT match
        Assertions.assertFalse(positions.contains(12), "'語' should not match (different byte[0])");
    }
}
