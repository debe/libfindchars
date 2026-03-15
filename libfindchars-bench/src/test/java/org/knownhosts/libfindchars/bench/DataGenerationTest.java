package org.knownhosts.libfindchars.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates statistical properties of benchmark data generation:
 * byte distribution, entropy, autocorrelation, density accuracy, and UTF-8 validity.
 */
class DataGenerationTest {

    private static final int SIZE = 1_000_000; // 1 MB — enough for statistical significance
    private static final long SEED = 42;

    // Config: asciiCount, density%, multiByteCount, groups (groups unused here but matches sweep format)
    @ParameterizedTest(name = "config={0}")
    @CsvSource({
            "8-15-0-2",
            "8-50-0-2",
            "8-15-3-2"
    })
    void generatedDataHasExpectedStatisticalProperties(String config) {
        var parts = config.split("-");
        int asciiCount = Integer.parseInt(parts[0]);
        int density = Integer.parseInt(parts[1]);
        int multiByteCount = Integer.parseInt(parts[2]);

        var rng = new SplittableRandom(SEED);
        Set<Integer> usedAscii = new LinkedHashSet<>();
        int[] asciiChars = BenchDataGenerator.pickUnusedAsciiChars(rng, usedAscii, asciiCount);
        Set<Integer> usedCodepoints = new HashSet<>();
        int[] mbCodepoints = BenchDataGenerator.pickMultiByteCodepoints(rng, usedCodepoints, multiByteCount);

        double asciiDensity = density / 100.0;
        double multiDensity = multiByteCount > 0 ? 0.005 : 0.0;
        boolean emitMbFiller = multiByteCount > 0;
        byte[] data = BenchDataGenerator.generateData(rng, SIZE, asciiChars, mbCodepoints,
                asciiDensity, multiDensity, emitMbFiller);

        assertEquals(SIZE, data.length, "Output size must match requested size");

        // --- Byte frequency histogram ---
        int[] histogram = new int[256];
        for (byte b : data) histogram[b & 0xFF]++;

        // No bytes above 0x7F should appear unless multi-byte sequences are present
        if (multiByteCount == 0 && !emitMbFiller) {
            for (int i = 0x80; i < 256; i++) {
                assertEquals(0, histogram[i],
                        "Byte 0x" + Integer.toHexString(i) + " should not appear in ASCII-only data");
            }
        }

        // --- Chi-squared goodness-of-fit for ASCII filler bytes ---
        // Test that filler bytes are approximately uniformly distributed among themselves.
        // We derive expected count from the actual total filler count (not from density params)
        // because multi-byte sequences consume multiple byte positions per roll.
        Set<Integer> targetSet = new HashSet<>();
        for (int t : asciiChars) targetSet.add(t);
        List<Integer> fillerBytes = new ArrayList<>();
        for (int b = 0x00; b <= 0x7F; b++) {
            if (!targetSet.contains(b)) fillerBytes.add(b);
        }
        int totalFillerCount = 0;
        for (int b : fillerBytes) totalFillerCount += histogram[b];
        if (fillerBytes.size() > 10 && totalFillerCount > fillerBytes.size() * 50) {
            double expectedPerFiller = (double) totalFillerCount / fillerBytes.size();
            double chiSquared = 0;
            for (int b : fillerBytes) {
                double diff = histogram[b] - expectedPerFiller;
                chiSquared += (diff * diff) / expectedPerFiller;
            }
            int df = fillerBytes.size() - 1;
            // Chi-squared critical value at p=0.001 for df~120 is ~170
            // Use a generous threshold: 2*df as rough upper bound
            double threshold = 2.0 * df;
            assertTrue(chiSquared < threshold,
                    "Chi-squared too high (" + chiSquared + " > " + threshold +
                            "), filler distribution is not uniform enough (df=" + df + ")");
        }

        // --- Actual vs expected density for ASCII targets ---
        int asciiTargetCount = 0;
        for (int t : asciiChars) asciiTargetCount += histogram[t];
        double actualAsciiDensity = (double) asciiTargetCount / SIZE;
        assertEquals(asciiDensity, actualAsciiDensity, 0.01,
                "ASCII target density should be within ±1% of configured value");

        // --- Shannon entropy ---
        double entropy = 0;
        for (int count : histogram) {
            if (count > 0) {
                double p = (double) count / SIZE;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        // For high density configs (50% to 8 targets), theoretical max is ~5.9
        double entropyThreshold = asciiDensity >= 0.4 ? 5.5 : 6.0;
        assertTrue(entropy > entropyThreshold,
                "Shannon entropy should be > " + entropyThreshold + " bits/byte, was " + entropy);

        // --- Autocorrelation at lag 1 ---
        // Multi-byte UTF-8 sequences inherently produce correlated adjacent bytes
        // (continuation bytes 0x80-0xBF follow lead bytes), so relax threshold for MB configs.
        double mean = 0;
        for (byte b : data) mean += (b & 0xFF);
        mean /= data.length;

        double variance = 0;
        for (byte b : data) {
            double diff = (b & 0xFF) - mean;
            variance += diff * diff;
        }
        variance /= data.length;

        double autoCorr = 0;
        for (int i = 0; i < data.length - 1; i++) {
            autoCorr += ((data[i] & 0xFF) - mean) * ((data[i + 1] & 0xFF) - mean);
        }
        autoCorr /= (data.length - 1) * variance;

        double autoCorrThreshold = (multiByteCount > 0 || emitMbFiller) ? 0.30 : 0.01;
        assertTrue(Math.abs(autoCorr) < autoCorrThreshold,
                "Autocorrelation at lag 1 should be < " + autoCorrThreshold + ", was " + autoCorr);

        // --- Valid UTF-8 ---
        assertValidUtf8(data);
    }

    @Test
    void multiByteFillerProducesNonAsciiBytes() {
        var rng = new SplittableRandom(SEED);
        Set<Integer> usedAscii = new LinkedHashSet<>();
        int[] asciiChars = BenchDataGenerator.pickUnusedAsciiChars(rng, usedAscii, 8);
        Set<Integer> usedCodepoints = new HashSet<>();
        int[] mbCodepoints = BenchDataGenerator.pickMultiByteCodepoints(rng, usedCodepoints, 3);

        byte[] data = BenchDataGenerator.generateData(rng, SIZE, asciiChars, mbCodepoints,
                0.15, 0.005, true);

        // Count high bytes (>= 0x80) — should be present from both targets and filler
        int highByteCount = 0;
        for (byte b : data) {
            if ((b & 0xFF) >= 0x80) highByteCount++;
        }
        assertTrue(highByteCount > SIZE * 0.01,
                "Multi-byte filler should produce >1% high bytes, got " +
                        (100.0 * highByteCount / SIZE) + "%");

        assertValidUtf8(data);
    }

    @Test
    void asciiOnlyConfigProducesNoHighBytes() {
        var rng = new SplittableRandom(SEED);
        Set<Integer> usedAscii = new LinkedHashSet<>();
        int[] asciiChars = BenchDataGenerator.pickUnusedAsciiChars(rng, usedAscii, 8);

        byte[] data = BenchDataGenerator.generateData(rng, SIZE, asciiChars, new int[0],
                0.15, 0.0, false);

        for (int i = 0; i < data.length; i++) {
            assertTrue((data[i] & 0xFF) <= 0x7F,
                    "Byte at position " + i + " is 0x" + Integer.toHexString(data[i] & 0xFF) +
                            " — expected ASCII only");
        }
    }

    private static void assertValidUtf8(byte[] data) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(data));
        } catch (Exception e) {
            fail("Generated data is not valid UTF-8: " + e.getMessage());
        }
    }
}
