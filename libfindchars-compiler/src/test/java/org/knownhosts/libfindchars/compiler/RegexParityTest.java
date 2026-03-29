package org.knownhosts.libfindchars.compiler;

import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.generator.Utf8EngineBuilder;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the SIMD engine finds exactly the same matches as a Java regex
 * over the same character set, asserting exact byte positions and literal correctness.
 */
class RegexParityTest {

    // Full character set matching the benchmark (Utf8Benchmark):
    // ASCII: whitespaces(5), punctuation(6), star, plus, nums(10), comparison range(<=>)
    // Multi-byte: £(U+00A3), œ(U+0153), ô(U+00F4), é(U+00E9), ™(U+2122)
    private static final Pattern REGEX =
            Pattern.compile("[\\r\\n\\t\\f :;{}\\[\\]*+0-9<=>£œôé™]");

    private static Utf8EngineBuilder.Utf8BuildResult buildFullEngine() {
        return Utf8EngineBuilder.builder()
                .codepoints("whitespaces", '\r', '\n', '\t', '\f', ' ')
                .codepoints("punctuations", ':', ';', '{', '}', '[', ']')
                .codepoints("star", '*')
                .codepoints("plus", '+')
                .codepoints("nums", '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
                .codepoint("pound", 0xA3)
                .codepoint("oe", 0x153)
                .codepoint("ocirc", 0xF4)
                .codepoint("eacute", 0xE9)
                .codepoint("trademark", 0x2122)
                .range("comparison", (byte) 0x3c, (byte) 0x3e)
                .build();
    }

    /**
     * Build char→byte position mapping for a UTF-8 encoded string.
     * charToBytePos[charIdx] = byte offset of that character in the UTF-8 encoding.
     */
    private static int[] buildCharToByteMap(String text) {
        int[] charToBytePos = new int[text.length()];
        int byteIdx = 0;
        for (int charIdx = 0; charIdx < text.length(); ) {
            charToBytePos[charIdx] = byteIdx;
            int cp = text.codePointAt(charIdx);
            int charCount = Character.charCount(cp);
            int byteLen = Character.toString(cp).getBytes(UTF_8).length;
            // For surrogate pairs, map the second char to the same byte position
            if (charCount == 2 && charIdx + 1 < text.length()) {
                charToBytePos[charIdx + 1] = byteIdx;
            }
            charIdx += charCount;
            byteIdx += byteLen;
        }
        return charToBytePos;
    }

    /**
     * Reverse lookup: literal byte → group name.
     */
    private static String literalGroupName(byte literalByte, Map<String, Byte> literals) {
        for (var entry : literals.entrySet()) {
            if (entry.getValue() == literalByte) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Verify that the literal group name is correct for the byte(s) at the given position.
     */
    private static void assertLiteralMatchesChar(byte[] data, long bytePos, String groupName) {
        assertNotNull(groupName, "No literal group for match at byte position " + bytePos);
        int p = (int) bytePos;

        int b = data[p] & 0xFF;

        switch (groupName) {
            case "whitespaces" -> assertTrue(
                    Set.of((int) '\r', (int) '\n', (int) '\t', (int) '\f', (int) ' ').contains(b),
                    "Byte 0x%02x at pos %d is not a whitespace".formatted(b, bytePos));
            case "punctuations" -> assertTrue(
                    Set.of((int) ':', (int) ';', (int) '{', (int) '}', (int) '[', (int) ']').contains(b),
                    "Byte 0x%02x at pos %d is not punctuation".formatted(b, bytePos));
            case "star" -> assertEquals('*', b,
                    "Byte at pos %d is not '*'".formatted(bytePos));
            case "plus" -> assertEquals('+', b,
                    "Byte at pos %d is not '+'".formatted(bytePos));
            case "nums" -> assertTrue(b >= '0' && b <= '9',
                    "Byte 0x%02x at pos %d is not a digit".formatted(b, bytePos));
            case "comparison" -> assertTrue(b >= 0x3c && b <= 0x3e,
                    "Byte 0x%02x at pos %d is not in comparison range [<=>]".formatted(b, bytePos));
            case "pound" -> {
                // £ = U+00A3 = 0xC2 0xA3
                assertEquals(0xC2, b, "pound lead byte mismatch at pos %d".formatted(bytePos));
                assertEquals(0xA3, data[p + 1] & 0xFF, "pound trail byte mismatch at pos %d".formatted(bytePos));
            }
            case "oe" -> {
                // œ = U+0153 = 0xC5 0x93
                assertEquals(0xC5, b, "oe lead byte mismatch at pos %d".formatted(bytePos));
                assertEquals(0x93, data[p + 1] & 0xFF, "oe trail byte mismatch at pos %d".formatted(bytePos));
            }
            case "ocirc" -> {
                // ô = U+00F4 = 0xC3 0xB4
                assertEquals(0xC3, b, "ocirc lead byte mismatch at pos %d".formatted(bytePos));
                assertEquals(0xB4, data[p + 1] & 0xFF, "ocirc trail byte mismatch at pos %d".formatted(bytePos));
            }
            case "eacute" -> {
                // é = U+00E9 = 0xC3 0xA9
                assertEquals(0xC3, b, "eacute lead byte mismatch at pos %d".formatted(bytePos));
                assertEquals(0xA9, data[p + 1] & 0xFF, "eacute trail byte mismatch at pos %d".formatted(bytePos));
            }
            case "trademark" -> {
                // ™ = U+2122 = 0xE2 0x84 0xA2
                assertEquals(0xE2, b, "trademark lead byte mismatch at pos %d".formatted(bytePos));
                assertEquals(0x84, data[p + 1] & 0xFF, "trademark byte 2 mismatch at pos %d".formatted(bytePos));
                assertEquals(0xA2, data[p + 2] & 0xFF, "trademark byte 3 mismatch at pos %d".formatted(bytePos));
            }
            default -> fail("Unknown literal group: " + groupName + " at pos " + bytePos);
        }
    }

    @Test
    void engineMatchesRegexOnRealFile() throws Exception {
        var filePath = Path.of("src/test/resources/3mb.txt");
        if (!Files.exists(filePath)) {
            filePath = Path.of("libfindchars-compiler/src/test/resources/3mb.txt");
        }
        assertTrue(Files.exists(filePath), "Need 3mb.txt test resource");

        byte[] fileBytes = Files.readAllBytes(filePath);
        String text = new String(fileBytes, UTF_8);

        // Build char→byte position mapping
        int[] charToBytePos = buildCharToByteMap(text);

        // --- Regex: collect sorted byte positions ---
        var matcher = REGEX.matcher(text);
        int regexCount = 0;
        // Count first to size the array
        while (matcher.find()) regexCount++;
        int[] regexPositions = new int[regexCount];
        matcher.reset();
        int idx = 0;
        while (matcher.find()) {
            regexPositions[idx++] = charToBytePos[matcher.start()];
        }
        Arrays.sort(regexPositions);
        assertTrue(regexPositions.length > 0, "Regex should find matches in 3mb.txt");

        // --- Engine: collect sorted byte positions ---
        var result = buildFullEngine();
        var engine = result.engine();
        var literals = result.literals();
        var storage = new MatchStorage(regexPositions.length + 1024, 32);
        var view = engine.find(MemorySegment.ofArray(fileBytes), storage);

        int engineCount = view.size();
        int[] enginePositions = new int[engineCount];
        for (int i = 0; i < engineCount; i++) {
            enginePositions[i] = (int) view.getPositionAt(storage, i);
        }
        Arrays.sort(enginePositions);

        System.out.println("File size: " + fileBytes.length + " bytes");
        System.out.println("Regex matches: " + regexPositions.length);
        System.out.println("Engine matches: " + engineCount);

        // Assert exact positions match
        assertArrayEquals(regexPositions, enginePositions,
                "Engine byte positions must exactly match regex byte positions");

        // Verify each engine match has a correct literal assignment
        for (int i = 0; i < engineCount; i++) {
            long pos = view.getPositionAt(storage, i);
            byte litByte = view.getLiteralAt(storage, i);
            String groupName = literalGroupName(litByte, literals);
            assertLiteralMatchesChar(fileBytes, pos, groupName);
        }
    }

    @Test
    void engineMatchesRegexOnSyntheticData() {
        // Synthetic UTF-8 string with all target chars plus non-target filler
        StringBuilder sb = new StringBuilder();
        // All ASCII targets
        sb.append("\r\n\t\f :;{}[]*+0123456789<=>");
        // Multi-byte targets
        sb.append("£œôé™");
        // Non-target filler
        sb.append("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ");
        // More targets scattered in text
        sb.append("hello world\n{ key: 1+2*3 }\n[0,1,2] <=> done £100 crème brûlée™\r\n");
        // Extra multi-byte to verify detection
        sb.append("price: £50 résumé œuvre ôter café™ end");

        String input = sb.toString();
        byte[] inputBytes = input.getBytes(UTF_8);

        // Pad to at least 64 bytes for vector alignment
        byte[] data = new byte[Math.max(inputBytes.length + 32, 128)];
        System.arraycopy(inputBytes, 0, data, 0, inputBytes.length);

        // Build char→byte position mapping (only over the input portion)
        int[] charToBytePos = buildCharToByteMap(input);

        // --- Regex: collect sorted byte positions ---
        var matcher = REGEX.matcher(input);
        int regexCount = 0;
        while (matcher.find()) regexCount++;
        int[] regexPositions = new int[regexCount];
        matcher.reset();
        int i = 0;
        while (matcher.find()) {
            regexPositions[i++] = charToBytePos[matcher.start()];
        }
        Arrays.sort(regexPositions);
        assertTrue(regexPositions.length > 0, "Regex should find matches in synthetic data");

        // --- Engine: scans whole padded buffer (zeros are non-matching) ---
        var result = buildFullEngine();
        var engine = result.engine();
        var literals = result.literals();
        var storage = new MatchStorage(256, 32);
        var view = engine.find(MemorySegment.ofArray(data), storage);

        int engineCount = view.size();
        int[] enginePositions = new int[engineCount];
        for (int j = 0; j < engineCount; j++) {
            enginePositions[j] = (int) view.getPositionAt(storage, j);
        }
        Arrays.sort(enginePositions);

        System.out.println("Synthetic - Regex: " + regexPositions.length + ", Engine: " + engineCount);

        // Assert exact positions match
        assertArrayEquals(regexPositions, enginePositions,
                "Engine byte positions must exactly match regex byte positions on synthetic data");

        // Verify literal assignments
        for (int j = 0; j < engineCount; j++) {
            long pos = view.getPositionAt(storage, j);
            byte litByte = view.getLiteralAt(storage, j);
            String groupName = literalGroupName(litByte, literals);
            assertLiteralMatchesChar(data, pos, groupName);
        }
    }
}
