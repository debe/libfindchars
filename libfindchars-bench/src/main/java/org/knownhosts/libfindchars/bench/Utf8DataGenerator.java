package org.knownhosts.libfindchars.bench;

import java.util.Random;

/**
 * Generates a mixed UTF-8 test buffer with controlled character density.
 * Approximately:
 * - 84% ASCII printable text
 * - 10% 2-byte chars (é, ü, ñ, ö, à, etc.)
 * - 5% 3-byte chars (日, 中, 本, 語, 大)
 * - 1% 4-byte chars (😀, 🎉, 🚀, 🌍)
 */
public final class Utf8DataGenerator {

    private static final char[] TWO_BYTE_CHARS = {'é', 'ü', 'ñ', 'ö', 'à', 'ß', 'ç', 'ê'};
    private static final int[] THREE_BYTE_CODEPOINTS = {0x65E5, 0x4E2D, 0x672C, 0x8A9E, 0x5927}; // 日中本語大
    private static final int[] FOUR_BYTE_CODEPOINTS = {0x1F600, 0x1F389, 0x1F680, 0x1F30D}; // 😀🎉🚀🌍

    public static byte[] generate(int targetSize, long seed) {
        var rng = new Random(seed);
        var buf = new byte[targetSize];
        int pos = 0;

        while (pos < targetSize) {
            double roll = rng.nextDouble();

            if (roll < 0.84) {
                // ASCII printable (0x20 - 0x7E)
                if (pos < targetSize) {
                    buf[pos++] = (byte) (0x20 + rng.nextInt(0x5F));
                }
            } else if (roll < 0.94) {
                // 2-byte char
                char c = TWO_BYTE_CHARS[rng.nextInt(TWO_BYTE_CHARS.length)];
                byte b0 = (byte) (0xC0 | (c >> 6));
                byte b1 = (byte) (0x80 | (c & 0x3F));
                if (pos + 1 < targetSize) {
                    buf[pos++] = b0;
                    buf[pos++] = b1;
                } else {
                    buf[pos++] = 0x20; // pad
                }
            } else if (roll < 0.99) {
                // 3-byte char
                int cp = THREE_BYTE_CODEPOINTS[rng.nextInt(THREE_BYTE_CODEPOINTS.length)];
                byte b0 = (byte) (0xE0 | (cp >> 12));
                byte b1 = (byte) (0x80 | ((cp >> 6) & 0x3F));
                byte b2 = (byte) (0x80 | (cp & 0x3F));
                if (pos + 2 < targetSize) {
                    buf[pos++] = b0;
                    buf[pos++] = b1;
                    buf[pos++] = b2;
                } else {
                    buf[pos++] = 0x20;
                }
            } else {
                // 4-byte char
                int cp = FOUR_BYTE_CODEPOINTS[rng.nextInt(FOUR_BYTE_CODEPOINTS.length)];
                byte b0 = (byte) (0xF0 | (cp >> 18));
                byte b1 = (byte) (0x80 | ((cp >> 12) & 0x3F));
                byte b2 = (byte) (0x80 | ((cp >> 6) & 0x3F));
                byte b3 = (byte) (0x80 | (cp & 0x3F));
                if (pos + 3 < targetSize) {
                    buf[pos++] = b0;
                    buf[pos++] = b1;
                    buf[pos++] = b2;
                    buf[pos++] = b3;
                } else {
                    buf[pos++] = 0x20;
                }
            }
        }

        return buf;
    }
}
