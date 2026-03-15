package org.knownhosts.libfindchars.bench;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Shared data generation for benchmarks and tests.
 *
 * <p>Generates byte arrays with configurable target density, full ASCII filler (0x00–0x7F),
 * optional multi-byte UTF-8 filler, and deterministic seeding via {@link SplittableRandom}.</p>
 */
final class BenchDataGenerator {

    /** Fraction of filler bytes that become random non-target multi-byte sequences. */
    static final double MB_FILLER_DENSITY = 0.03;

    private BenchDataGenerator() {}

    /**
     * Pick {@code count} random printable ASCII characters (0x21–0x7E) not already in {@code used}.
     */
    static int[] pickUnusedAsciiChars(SplittableRandom rng, Set<Integer> used, int count) {
        List<Integer> available = new ArrayList<>();
        for (int c = 0x21; c <= 0x7E; c++) {
            if (!used.contains(c)) available.add(c);
        }
        // Fisher-Yates shuffle using SplittableRandom
        for (int i = available.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            var tmp = available.get(i);
            available.set(i, available.get(j));
            available.set(j, tmp);
        }
        int[] result = new int[Math.min(count, available.size())];
        for (int i = 0; i < result.length; i++) {
            result[i] = available.get(i);
            used.add(result[i]);
        }
        return result;
    }

    /**
     * Pick {@code count} random multi-byte codepoints (2-byte or 3-byte UTF-8).
     */
    static int[] pickMultiByteCodepoints(SplittableRandom rng, Set<Integer> used, int count) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            int cp;
            do {
                if (rng.nextInt(2) == 0) {
                    cp = 0x80 + rng.nextInt(0x0780); // 2-byte: U+0080–U+07FF
                } else {
                    cp = 0x0800 + rng.nextInt(0xF7FE); // 3-byte: U+0800–U+FFFD
                    if (cp >= 0xD800 && cp <= 0xDFFF) continue;
                    if (cp == 0xFFFE || cp == 0xFFFF) continue;
                }
                if (!used.contains(cp)) break;
            } while (true);
            result[i] = cp;
            used.add(cp);
        }
        return result;
    }

    /**
     * Generate benchmark data with full ASCII filler, optional multi-byte filler,
     * and configurable target density.
     *
     * @param rng             deterministic RNG
     * @param sizeBytes       target output size in bytes
     * @param asciiTargets    ASCII codepoints that count as "needles"
     * @param mbCodepoints    multi-byte codepoints that count as "needles"
     * @param asciiDensity    fraction of bytes that should be ASCII targets (0.0–1.0)
     * @param multiDensity    fraction of bytes that should be multi-byte targets
     * @param emitMbFiller    if true, emit non-target multi-byte filler at {@link #MB_FILLER_DENSITY}
     */
    static byte[] generateData(SplittableRandom rng, int sizeBytes, int[] asciiTargets,
                                int[] mbCodepoints, double asciiDensity, double multiDensity,
                                boolean emitMbFiller) {
        // Build ASCII filler: all of 0x00–0x7F excluding targets
        Set<Integer> targetSet = new HashSet<>();
        for (int t : asciiTargets) targetSet.add(t);
        byte[] filler = buildAsciiFiller(targetSet);

        // Pre-encode multi-byte target codepoints
        byte[][] mbEncoded = new byte[mbCodepoints.length][];
        for (int i = 0; i < mbCodepoints.length; i++) {
            mbEncoded[i] = Character.toString(mbCodepoints[i]).getBytes(StandardCharsets.UTF_8);
        }

        // Pre-encode a pool of non-target multi-byte filler codepoints
        byte[][] mbFillerPool = emitMbFiller ? buildMbFillerPool(rng, targetSet, mbCodepoints) : null;

        byte[] buf = new byte[sizeBytes + 64];
        int pos = 0;
        while (pos < sizeBytes) {
            double roll = rng.nextDouble();
            if (mbCodepoints.length > 0 && roll < multiDensity) {
                byte[] encoded = mbEncoded[rng.nextInt(mbEncoded.length)];
                if (pos + encoded.length <= sizeBytes) {
                    System.arraycopy(encoded, 0, buf, pos, encoded.length);
                    pos += encoded.length;
                } else {
                    buf[pos++] = filler[rng.nextInt(filler.length)];
                }
            } else if (asciiTargets.length > 0 && roll < multiDensity + asciiDensity) {
                buf[pos++] = (byte) asciiTargets[rng.nextInt(asciiTargets.length)];
            } else if (emitMbFiller && mbFillerPool != null && roll < multiDensity + asciiDensity + MB_FILLER_DENSITY) {
                byte[] encoded = mbFillerPool[rng.nextInt(mbFillerPool.length)];
                if (pos + encoded.length <= sizeBytes) {
                    System.arraycopy(encoded, 0, buf, pos, encoded.length);
                    pos += encoded.length;
                } else {
                    buf[pos++] = filler[rng.nextInt(filler.length)];
                }
            } else {
                buf[pos++] = filler[rng.nextInt(filler.length)];
            }
        }
        return Arrays.copyOf(buf, sizeBytes);
    }

    private static byte[] buildAsciiFiller(Set<Integer> excludeTargets) {
        List<Byte> fillerList = new ArrayList<>();
        for (int b = 0x00; b <= 0x7F; b++) {
            if (!excludeTargets.contains(b)) fillerList.add((byte) b);
        }
        // Fallback: if everything excluded (unlikely), use 0x01–0x1A
        if (fillerList.isEmpty()) {
            for (int i = 1; i <= 26; i++) fillerList.add((byte) i);
        }
        byte[] filler = new byte[fillerList.size()];
        for (int i = 0; i < filler.length; i++) filler[i] = fillerList.get(i);
        return filler;
    }

    /**
     * Build a pool of 32 random non-target multi-byte codepoints for filler.
     */
    private static byte[][] buildMbFillerPool(SplittableRandom rng, Set<Integer> asciiTargets, int[] mbTargets) {
        Set<Integer> allTargets = new HashSet<>(asciiTargets);
        for (int cp : mbTargets) allTargets.add(cp);

        byte[][] pool = new byte[32][];
        for (int i = 0; i < pool.length; i++) {
            int cp;
            do {
                if (rng.nextInt(2) == 0) {
                    cp = 0x80 + rng.nextInt(0x0780); // 2-byte
                } else {
                    cp = 0x0800 + rng.nextInt(0xF7FE); // 3-byte
                    if (cp >= 0xD800 && cp <= 0xDFFF) continue;
                    if (cp == 0xFFFE || cp == 0xFFFF) continue;
                }
                if (!allTargets.contains(cp)) break;
            } while (true);
            pool[i] = Character.toString(cp).getBytes(StandardCharsets.UTF_8);
        }
        return pool;
    }
}
