package org.knownhosts.libfindchars.bench.needle;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness gate for the needle package. No benchmark number is worth anything until this
 * passes, so this runs first and covers every strategy directly, not just the dispatcher.
 */
class NeedleFinderTest {

    private static MemorySegment seg(byte[] b) {
        return MemorySegment.ofArray(b);
    }

    /** Naive byte-level oracle. Deliberately dumb. */
    private static long naive(byte[] hay, byte[] needle) {
        if (needle.length == 0) {
            return 0;
        }
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static void assertAllStrategiesAgree(byte[] hay, byte[] needle) {
        long expected = naive(hay, needle);
        var data = seg(hay);

        assertEquals(expected, NeedleFinder.of(needle).find(data),
                () -> "NeedleFinder disagreed, m=" + needle.length + " n=" + hay.length);

        if (needle.length > 0) {
            assertEquals(expected, NeedleFinder.twoWayFor(needle).find(data, 0, hay.length),
                    () -> "TwoWay disagreed, m=" + needle.length);
            assertEquals(expected, NeedleFinder.simdScanFor(needle).find(data, hay.length),
                    () -> "SimdScan disagreed, m=" + needle.length);

            var filter = NeedleFinder.filterFor(needle);
            if (filter != null && needle.length <= hay.length) {
                var stats = new QGramFilter.Stats();
                assertEquals(expected, NeedleFinder.findQGram(filter, data, stats),
                        () -> "QGramFilter disagreed, m=" + needle.length);
            }
        }
    }

    // --- the coverage argument is the correctness proof: test it at every stride phase ---

    @ParameterizedTest(name = "m={0}")
    @ValueSource(ints = {33, 40, 64, 100, 257})
    void occurrenceFoundAtEveryOffsetModStride(int m) {
        byte[] needle = new byte[m];
        var rnd = new Random(1234 + m);
        for (int i = 0; i < m; i++) {
            needle[i] = (byte) ('a' + rnd.nextInt(26));
        }
        var filter = NeedleFinder.filterFor(needle);
        assertNotNull(filter, "random needle should be filter-eligible");
        int stride = filter.stride();

        // Every possible phase of an occurrence relative to the probe grid.
        for (int phase = 0; phase < stride + 3; phase++) {
            byte[] hay = new byte[phase + m + 37];
            for (int i = 0; i < hay.length; i++) {
                hay[i] = 'Z';
            }
            System.arraycopy(needle, 0, hay, phase, m);
            long expected = naive(hay, needle);
            assertEquals(phase, expected, "oracle sanity");
            var stats = new QGramFilter.Stats();
            assertEquals(phase, NeedleFinder.findQGram(filter, seg(hay), stats),
                    "missed occurrence at phase " + phase + " (stride " + stride + ")");
        }
    }

    @Test
    void periodicNeedleIsRejectedByFilterAndStillFound() {
        byte[] needle = "a".repeat(4096).getBytes(StandardCharsets.US_ASCII);
        assertNull(NeedleFinder.filterFor(needle), "periodic needle must not use the filter");

        // Falls through to the full scan, whose anchors are both 'a' -- so it saturates its
        // verification budget and hands off to Two-Way. Slow path, but still correct and O(n).
        var finder = NeedleFinder.of(needle);
        assertEquals("simd-scan", finder.strategy());

        byte[] hay = ("b".repeat(500) + "a".repeat(5000)).getBytes(StandardCharsets.US_ASCII);
        assertEquals(naive(hay, needle), finder.find(seg(hay)));

        // No occurrence: every position is a candidate, so this is the case that must lean on
        // the budget handoff rather than verifying 4096 bytes at each of them.
        byte[] miss = "a".repeat(4095).getBytes(StandardCharsets.US_ASCII);
        var missFinder = NeedleFinder.of(needle);
        assertEquals(-1, missFinder.find(seg(miss)));
    }

    @Test
    void lemireAdversarialInput() {
        byte[] hay = "a".repeat(1_000_000).getBytes(StandardCharsets.US_ASCII);
        byte[] needle = ("a".repeat(4095) + "b").getBytes(StandardCharsets.US_ASCII);

        // Only one q-gram of this needle is informative (the one holding 'b'), so strided
        // sampling cannot work -- it degrades to a rare-byte full scan for 'b'.
        var finder = NeedleFinder.of(needle);
        assertEquals("simd-scan", finder.strategy());
        assertEquals(-1, finder.find(seg(hay)));
        assertEquals(0, finder.stats().verifies, "no 'b' in the haystack, so nothing to verify");

        // ...and it is still correct when the needle IS present.
        byte[] hit = new byte[hay.length];
        System.arraycopy(hay, 0, hit, 0, hay.length);
        System.arraycopy(needle, 0, hit, 123_456, needle.length);
        assertEquals(123_456, NeedleFinder.of(needle).find(seg(hit)));
    }

    @Test
    void repeatedQGramsWithinANonPeriodicNeedle() {
        // "abcdefgh" appears three times, well under MAX_GRAM_REPEAT.
        String n = "abcdefgh" + "X".repeat(20) + "abcdefgh" + "Y".repeat(20) + "abcdefgh" + "Z".repeat(10);
        byte[] needle = ascii(n);
        byte[] hay = ascii("prefix".repeat(50) + n + "suffix".repeat(50));
        assertAllStrategiesAgree(hay, needle);
    }

    @Test
    void degenerateLengths() {
        byte[] hay = ascii("the quick brown fox jumps over the lazy dog");
        assertEquals(0, NeedleFinder.of(new byte[0]).find(seg(hay)));
        assertAllStrategiesAgree(hay, ascii("q"));
        assertAllStrategiesAgree(hay, ascii("z"));
        assertAllStrategiesAgree(hay, ascii("!"));
        assertAllStrategiesAgree(hay, ascii("the"));
        assertAllStrategiesAgree(hay, ascii("dog"));
        assertAllStrategiesAgree(hay, hay);
        assertEquals(-1, NeedleFinder.of(ascii("x".repeat(200))).find(seg(hay)));
    }

    @Test
    void needleAtBothEdges() {
        byte[] needle = ascii("NEEDLE" + "q".repeat(60));
        byte[] filler = ascii("f".repeat(3000));

        byte[] atStart = new byte[needle.length + filler.length];
        System.arraycopy(needle, 0, atStart, 0, needle.length);
        System.arraycopy(filler, 0, atStart, needle.length, filler.length);
        assertAllStrategiesAgree(atStart, needle);

        byte[] atEnd = new byte[needle.length + filler.length];
        System.arraycopy(filler, 0, atEnd, 0, filler.length);
        System.arraycopy(needle, 0, atEnd, filler.length, needle.length);
        assertAllStrategiesAgree(atEnd, needle);
    }

    @Test
    void budgetExhaustionFallsBackToTwoWayAndStaysCorrect() {
        // Every 4-gram-aligned window ends in the needle's q-gram, but the needle never
        // matches: maximal filter pressure with zero real hits.
        int m = 512;
        byte[] needle = new byte[m];
        for (int i = 0; i < m; i++) {
            needle[i] = 'a';
        }
        needle[0] = 'Q';

        byte[] hay = new byte[200_000];
        for (int i = 0; i < hay.length; i++) {
            hay[i] = 'a';
        }
        var finder = NeedleFinder.of(needle);
        assertEquals(-1, finder.find(seg(hay)), "no occurrence, and must not hang");

        // Place a real occurrence and confirm the fallback still finds it.
        System.arraycopy(needle, 0, hay, 150_000, m);
        assertEquals(150_000, NeedleFinder.of(needle).find(seg(hay)));
    }

    @Test
    void agreesWithStringIndexOfOnAsciiText() {
        var rnd = new Random(99);
        var sb = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            sb.append((char) ('a' + rnd.nextInt(4)));
        }
        String text = sb.toString();
        byte[] hay = ascii(text);

        for (int m : new int[]{1, 2, 8, 16, 33, 64, 512, 4096}) {
            for (int trial = 0; trial < 20; trial++) {
                int at = rnd.nextInt(text.length() - m);
                String n = text.substring(at, at + m);
                assertEquals(text.indexOf(n), NeedleFinder.of(ascii(n)).find(seg(hay)),
                        "m=" + m + " trial=" + trial);
            }
        }
    }

    @Test
    void randomizedFuzzAcrossAllStrategies() {
        var rnd = new Random(20260822L);
        for (int round = 0; round < 400; round++) {
            int n = 1 + rnd.nextInt(4000);
            int alphabet = 1 + rnd.nextInt(6);
            byte[] hay = new byte[n];
            for (int i = 0; i < n; i++) {
                hay[i] = (byte) ('a' + rnd.nextInt(alphabet));
            }
            int m = 1 + rnd.nextInt(Math.min(n, 300));
            byte[] needle;
            if (rnd.nextBoolean()) {
                int at = rnd.nextInt(n - m + 1);
                needle = java.util.Arrays.copyOfRange(hay, at, at + m);
            } else {
                needle = new byte[m];
                for (int i = 0; i < m; i++) {
                    needle[i] = (byte) ('a' + rnd.nextInt(alphabet + 1));
                }
            }
            assertAllStrategiesAgree(hay, needle);
        }
    }

    @Test
    void handlesFullByteRangeIncludingNegativeBytes() {
        var rnd = new Random(7);
        byte[] hay = new byte[20_000];
        rnd.nextBytes(hay);
        for (int m : new int[]{1, 9, 40, 300}) {
            int at = rnd.nextInt(hay.length - m);
            byte[] needle = java.util.Arrays.copyOfRange(hay, at, at + m);
            assertAllStrategiesAgree(hay, needle);
        }
    }
}
