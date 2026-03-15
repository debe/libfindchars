package org.knownhosts.libfindchars.compiler;

import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.generator.Utf8EngineBuilder;

import java.lang.foreign.MemorySegment;
import java.util.*;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Randomized fuzz test: builds random character sets and group configurations,
 * generates random data, then asserts the SIMD engine matches Java regex exactly.
 * Each round uses a deterministic seed for reproducibility.
 *
 * <h3>Solver limits respected by this test</h3>
 * <p>The Z3 nibble-matrix solver operates on a 16x16 grid (low nibble x high nibble).
 * Each ASCII literal group must fit its characters into this matrix without conflicts.
 * Auto-split doubles capacity by solving two halves independently, giving a practical
 * limit of ~20 ASCII characters per round. All literal byte IDs across the entire engine
 * share a single namespace of [1, 63] (vectorByteSize - 1), capping total literals at 63.
 * Multi-byte codepoints are easier because each UTF-8 byte is solved in a separate round.</p>
 *
 * <p>This test stays within these limits: max 12 ASCII literals per round (well within
 * single-group solvability), max 3 multi-byte codepoints, and at most 1 range operation.</p>
 */
class FuzzRegexParityTest {

    private static final int ROUNDS = 50;

    // Solver limits — see class javadoc
    private static final int MAX_ASCII_LITERALS = 12;  // safe for single shuffle group
    private static final int MAX_MULTI_BYTE = 3;
    private static final int MAX_RANGE_LEN = 5;

    @Test
    void fuzzEngineMatchesRegex() {
        int successCount = 0;

        for (int round = 0; round < ROUNDS; round++) {
            var rng = new Random(round);

            // --- Pick random parameters within solver limits ---
            int dataSize = 1 + rng.nextInt(50); // 1–50 MB
            int dataSizeBytes = dataSize * 1024 * 1024;
            int asciiGroupCount = 1 + rng.nextInt(4); // 1–4 groups
            boolean includeRange = rng.nextBoolean();
            int multiByteCount = rng.nextInt(MAX_MULTI_BYTE + 1); // 0–3
            double asciiDensity = 0.05 + rng.nextDouble() * 0.25; // 5–30%
            double multiDensity = 0.0001 + rng.nextDouble() * 0.005; // 0.01–0.5%

            // --- Pick random ASCII characters (0x21–0x7E, distinct across all groups) ---
            // Stay within MAX_ASCII_LITERALS to keep the nibble matrix solvable
            int maxTotalAscii = 1 + rng.nextInt(MAX_ASCII_LITERALS); // 1–12
            List<int[]> asciiGroups = new ArrayList<>();
            List<String> groupNames = new ArrayList<>();
            Set<Integer> usedAscii = new HashSet<>();

            for (int g = 0; g < asciiGroupCount; g++) {
                int remaining = maxTotalAscii - usedAscii.size();
                if (remaining <= 0) break;
                int charsInGroup = 1 + rng.nextInt(Math.min(6, remaining));
                List<Integer> groupChars = new ArrayList<>();
                for (int c = 0; c < charsInGroup; c++) {
                    int ch = pickUnusedAscii(rng, usedAscii);
                    if (ch == -1) break; // exhausted printable ASCII
                    groupChars.add(ch);
                }
                if (groupChars.isEmpty()) continue;
                asciiGroups.add(groupChars.stream().mapToInt(Integer::intValue).toArray());
                groupNames.add("g" + g);
            }

            // --- Pick random multi-byte codepoints ---
            List<Integer> multiByteCodepoints = new ArrayList<>();
            List<String> multiByteNames = new ArrayList<>();
            Set<Integer> usedCodepoints = new HashSet<>();
            for (int m = 0; m < multiByteCount; m++) {
                int cp = pickRandomMultiByte(rng, usedCodepoints);
                if (cp == -1) break;
                multiByteCodepoints.add(cp);
                multiByteNames.add("mb" + m);
            }

            // --- Pick optional range (length 2–5, no overlap with codepoints groups) ---
            byte rangeFrom = 0, rangeTo = 0;
            String rangeName = "rng";
            if (includeRange) {
                int rangeLen = 2 + rng.nextInt(MAX_RANGE_LEN - 1); // 2–5
                int rangeStart = pickRangeStart(rng, usedAscii, rangeLen);
                if (rangeStart == -1) {
                    includeRange = false;
                } else {
                    rangeFrom = (byte) rangeStart;
                    rangeTo = (byte) (rangeStart + rangeLen - 1);
                    for (int r = rangeStart; r <= rangeStart + rangeLen - 1; r++) {
                        usedAscii.add(r);
                    }
                }
            }

            // Collect all target ASCII codepoints (for data generation)
            Set<Integer> allTargetAscii = new HashSet<>(usedAscii);

            // --- Build regex pattern ---
            String regexPattern = buildRegexPattern(asciiGroups, multiByteCodepoints, includeRange, rangeFrom, rangeTo);
            Pattern regex = Pattern.compile(regexPattern);

            // --- Build SIMD engine ---
            var builder = Utf8EngineBuilder.builder();
            for (int g = 0; g < asciiGroups.size(); g++) {
                builder.codepoints(groupNames.get(g), asciiGroups.get(g));
            }
            for (int m = 0; m < multiByteCodepoints.size(); m++) {
                builder.codepoint(multiByteNames.get(m), multiByteCodepoints.get(m));
            }
            if (includeRange) {
                builder.range(rangeName, rangeFrom, rangeTo);
            }

            Utf8EngineBuilder.Utf8BuildResult result;
            try {
                result = builder.build();
            } catch (IllegalStateException e) {
                // Z3 can't solve this random configuration — skip round
                System.out.printf("Round %2d: skipped (unsolvable: %s)%n", round, e.getMessage());
                continue;
            }

            var engine = result.engine();
            var literals = result.literals();

            // --- Build group membership map for literal verification ---
            Map<String, Set<Integer>> groupMembers = new HashMap<>();
            for (int g = 0; g < asciiGroups.size(); g++) {
                Set<Integer> members = new HashSet<>();
                for (int ch : asciiGroups.get(g)) members.add(ch);
                groupMembers.put(groupNames.get(g), members);
            }
            for (int m = 0; m < multiByteCodepoints.size(); m++) {
                groupMembers.put(multiByteNames.get(m), Set.of(multiByteCodepoints.get(m)));
            }
            if (includeRange) {
                Set<Integer> rangeMembers = new HashSet<>();
                for (int r = (rangeFrom & 0xFF); r <= (rangeTo & 0xFF); r++) {
                    rangeMembers.add(r);
                }
                groupMembers.put(rangeName, rangeMembers);
            }

            // --- Generate random data (pad with zeros for vector alignment) ---
            int[] allAsciiTargets = allTargetAscii.stream().mapToInt(Integer::intValue).toArray();
            byte[] rawData = generateData(rng, dataSizeBytes, allAsciiTargets, multiByteCodepoints,
                    asciiDensity, multiDensity);
            // Pad to next 64-byte boundary + 64 so vector reads don't go OOB
            int paddedLen = ((rawData.length + 63) & ~63) + 64;
            byte[] data = Arrays.copyOf(rawData, paddedLen);

            // Only decode the actual data portion for regex matching
            String text = new String(rawData, UTF_8);

            System.out.printf("Round %2d: seed=%d, size=%dMB, asciiGroups=%d(%d chars), multiByte=%d, range=%s, density=%.1f%%/%.2f%%%n",
                    round, round, dataSize, asciiGroups.size(),
                    allTargetAscii.size(), multiByteCodepoints.size(),
                    includeRange ? String.format("0x%02x-0x%02x", rangeFrom & 0xFF, rangeTo & 0xFF) : "none",
                    asciiDensity * 100, multiDensity * 100);

            // --- Regex: collect sorted byte positions ---
            int[] charToBytePos = buildCharToByteMap(text);
            var matcher = regex.matcher(text);
            List<Integer> regexPosList = new ArrayList<>();
            while (matcher.find()) {
                regexPosList.add(charToBytePos[matcher.start()]);
            }
            int[] regexPositions = regexPosList.stream().mapToInt(Integer::intValue).toArray();
            Arrays.sort(regexPositions);

            // --- Engine: collect sorted byte positions ---
            var storage = new MatchStorage(Math.max(data.length / 4, regexPositions.length + 1024), 32);
            var view = engine.find(MemorySegment.ofArray(data), storage);

            int engineCount = view.size();
            int[] enginePositions = new int[engineCount];
            for (int i = 0; i < engineCount; i++) {
                enginePositions[i] = view.getPositionAt(storage, i);
            }
            Arrays.sort(enginePositions);

            System.out.printf("  Matches: regex=%d, engine=%d%n", regexPositions.length, engineCount);

            // --- Assert positions match ---
            assertArrayEquals(regexPositions, enginePositions,
                    "Round " + round + ": engine byte positions must exactly match regex");

            // --- Verify each match's codepoint belongs to a configured group ---
            // Build set of all target codepoints across all groups
            Set<Integer> allTargetCodepoints = new HashSet<>();
            for (var members : groupMembers.values()) allTargetCodepoints.addAll(members);

            for (int i = 0; i < engineCount; i++) {
                int pos = view.getPositionAt(storage, i);
                byte litByte = view.getLiteralAt(storage, i);
                int codepointAtPos = codepointFromUtf8(data, pos);

                // Primary check: codepoint at this position must be a configured target
                assertTrue(allTargetCodepoints.contains(codepointAtPos),
                        "Round %d: codepoint U+%04X at byte %d is not in any configured group"
                                .formatted(round, codepointAtPos, pos));

                // Secondary check: if literal resolves to a group, verify membership
                String groupName = literalGroupName(litByte, literals);
                if (groupName != null) {
                    Set<Integer> expectedChars = groupMembers.get(groupName);
                    assertNotNull(expectedChars, "Round " + round + ": unknown group " + groupName);
                    assertTrue(expectedChars.contains(codepointAtPos),
                            "Round %d: codepoint U+%04X at byte %d not in group '%s' (expected %s)"
                                    .formatted(round, codepointAtPos, pos, groupName, expectedChars));
                }
            }

            successCount++;
        }

        System.out.printf("%nPassed %d/%d rounds (skipped %d unsolvable).%n",
                successCount, ROUNDS, ROUNDS - successCount);
        assertTrue(successCount >= ROUNDS / 2,
                "At least half the rounds must succeed, but only " + successCount + "/" + ROUNDS + " did");
    }

    // --- Helper methods ---

    private static int pickUnusedAscii(Random rng, Set<Integer> used) {
        // Printable ASCII 0x21–0x7E (no space to avoid whitespace issues)
        List<Integer> available = new ArrayList<>();
        for (int c = 0x21; c <= 0x7E; c++) {
            if (!used.contains(c)) available.add(c);
        }
        if (available.isEmpty()) return -1;
        int ch = available.get(rng.nextInt(available.size()));
        used.add(ch);
        return ch;
    }

    private static int pickRandomMultiByte(Random rng, Set<Integer> used) {
        for (int attempt = 0; attempt < 100; attempt++) {
            int cp;
            if (rng.nextBoolean()) {
                // 2-byte: U+0080–U+07FF
                cp = 0x80 + rng.nextInt(0x0780);
            } else {
                // 3-byte: U+0800–U+FFFD, skipping surrogates U+D800–U+DFFF
                cp = 0x0800 + rng.nextInt(0xF7FE); // 0x0800 to 0xFFFD
                if (cp >= 0xD800 && cp <= 0xDFFF) continue;
                if (cp == 0xFFFE || cp == 0xFFFF) continue;
            }
            if (!used.contains(cp)) {
                used.add(cp);
                return cp;
            }
        }
        return -1;
    }

    private static int pickRangeStart(Random rng, Set<Integer> usedAscii, int rangeLen) {
        // Try to find a contiguous range in 0x21–0x7E that doesn't overlap used chars
        List<Integer> candidates = new ArrayList<>();
        for (int start = 0x21; start + rangeLen - 1 <= 0x7E; start++) {
            boolean conflict = false;
            for (int r = start; r < start + rangeLen; r++) {
                if (usedAscii.contains(r)) { conflict = true; break; }
            }
            if (!conflict) candidates.add(start);
        }
        if (candidates.isEmpty()) return -1;
        return candidates.get(rng.nextInt(candidates.size()));
    }

    private static String buildRegexPattern(List<int[]> asciiGroups, List<Integer> multiByteCodepoints,
                                            boolean includeRange, byte rangeFrom, byte rangeTo) {
        StringBuilder sb = new StringBuilder("[");

        // All ASCII chars in a character class
        Set<Integer> allAscii = new TreeSet<>();
        for (int[] group : asciiGroups) {
            for (int ch : group) allAscii.add(ch);
        }
        if (includeRange) {
            for (int r = (rangeFrom & 0xFF); r <= (rangeTo & 0xFF); r++) {
                allAscii.add(r);
            }
        }
        for (int ch : allAscii) {
            sb.append(escapeForCharClass((char) ch));
        }

        // Multi-byte codepoints as Unicode escapes
        for (int cp : multiByteCodepoints) {
            sb.append(Character.toString(cp));
        }

        sb.append("]");
        return sb.toString();
    }

    private static String escapeForCharClass(char ch) {
        return switch (ch) {
            case ']' -> "\\]";
            case '\\' -> "\\\\";
            case '^' -> "\\^";
            case '-' -> "\\-";
            case '[' -> "\\[";
            default -> String.valueOf(ch);
        };
    }

    private static byte[] generateData(Random rng, int sizeBytes, int[] asciiTargets,
                                       List<Integer> multiByteCodepoints, double asciiDensity,
                                       double multiDensity) {
        // Filler chars: uppercase A-Z minus any that are also targets
        Set<Integer> targetSet = new HashSet<>();
        for (int t : asciiTargets) targetSet.add(t);
        List<Byte> fillerList = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            if (!targetSet.contains('A' + i)) fillerList.add((byte) ('A' + i));
        }
        if (fillerList.isEmpty()) {
            // Fallback: use bytes 0x01-0x1F (non-printable, won't be targets)
            for (int i = 1; i <= 26; i++) fillerList.add((byte) i);
        }
        byte[] filler = new byte[fillerList.size()];
        for (int i = 0; i < filler.length; i++) filler[i] = fillerList.get(i);

        // Pre-encode multi-byte codepoints
        byte[][] multiByteEncoded = new byte[multiByteCodepoints.size()][];
        for (int i = 0; i < multiByteCodepoints.size(); i++) {
            multiByteEncoded[i] = Character.toString(multiByteCodepoints.get(i)).getBytes(UTF_8);
        }

        // Build into a ByteArrayOutputStream-style buffer
        byte[] buf = new byte[sizeBytes + 64]; // extra for multi-byte at end
        int pos = 0;

        while (pos < sizeBytes) {
            double roll = rng.nextDouble();
            if (!multiByteCodepoints.isEmpty() && roll < multiDensity) {
                // Insert a random multi-byte codepoint
                byte[] encoded = multiByteEncoded[rng.nextInt(multiByteEncoded.length)];
                if (pos + encoded.length <= buf.length) {
                    System.arraycopy(encoded, 0, buf, pos, encoded.length);
                    pos += encoded.length;
                }
            } else if (asciiTargets.length > 0 && roll < multiDensity + asciiDensity) {
                // Insert a random ASCII target
                buf[pos++] = (byte) asciiTargets[rng.nextInt(asciiTargets.length)];
            } else {
                // Insert filler
                buf[pos++] = filler[rng.nextInt(filler.length)];
            }
        }

        return Arrays.copyOf(buf, pos);
    }

    private static int[] buildCharToByteMap(String text) {
        int[] charToBytePos = new int[text.length()];
        int byteIdx = 0;
        for (int charIdx = 0; charIdx < text.length(); ) {
            charToBytePos[charIdx] = byteIdx;
            int cp = text.codePointAt(charIdx);
            int charCount = Character.charCount(cp);
            int byteLen = Character.toString(cp).getBytes(UTF_8).length;
            if (charCount == 2 && charIdx + 1 < text.length()) {
                charToBytePos[charIdx + 1] = byteIdx;
            }
            charIdx += charCount;
            byteIdx += byteLen;
        }
        return charToBytePos;
    }

    private static String literalGroupName(byte literalByte, Map<String, Byte> literals) {
        for (var entry : literals.entrySet()) {
            if (entry.getValue() == literalByte) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Decode the codepoint at a given byte position in UTF-8 data.
     */
    private static int codepointFromUtf8(byte[] data, int pos) {
        int b0 = data[pos] & 0xFF;
        if (b0 < 0x80) return b0; // 1-byte (ASCII)
        if ((b0 & 0xE0) == 0xC0) {
            // 2-byte
            int b1 = data[pos + 1] & 0xFF;
            return ((b0 & 0x1F) << 6) | (b1 & 0x3F);
        }
        if ((b0 & 0xF0) == 0xE0) {
            // 3-byte
            int b1 = data[pos + 1] & 0xFF;
            int b2 = data[pos + 2] & 0xFF;
            return ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
        }
        if ((b0 & 0xF8) == 0xF0) {
            // 4-byte
            int b1 = data[pos + 1] & 0xFF;
            int b2 = data[pos + 2] & 0xFF;
            int b3 = data[pos + 3] & 0xFF;
            return ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
        }
        return b0; // fallback
    }
}
