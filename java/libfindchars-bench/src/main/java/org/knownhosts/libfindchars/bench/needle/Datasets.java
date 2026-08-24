package org.knownhosts.libfindchars.bench.needle;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Haystack/needle pairs shared by the benchmark and the profiler. */
final class Datasets {

    static final int SIZE = 1024 * 1024;

    private Datasets() {
    }

    record Case(String name, byte[] hay, byte[] needle, long expected) {
    }

    static Case of(String kind, int m) {
        return switch (kind) {
            case "lemire" -> lemire(m);
            case "random" -> planted(randomAscii(), m, "random");
            case "text" -> planted(pseudoEnglish(), m, "text");
            default -> throw new IllegalArgumentException(kind);
        };
    }

    /** Lemire's adversarial pair: haystack of one repeated byte, needle that never occurs. */
    private static Case lemire(int m) {
        byte[] hay = new byte[SIZE];
        java.util.Arrays.fill(hay, (byte) 'a');
        byte[] needle = new byte[m];
        java.util.Arrays.fill(needle, (byte) 'a');
        needle[m - 1] = 'b';
        return new Case("lemire", hay, needle, -1);
    }

    /** Needle planted at 90% so every strategy has to traverse most of the haystack. */
    private static Case planted(byte[] hay, int m, String name) {
        int at = (int) (hay.length * 0.9);
        byte[] needle = java.util.Arrays.copyOfRange(hay, at, at + m);
        // Make sure 90% really is the first occurrence.
        long first = firstOccurrence(hay, needle);
        return new Case(name, hay, needle, first);
    }

    private static long firstOccurrence(byte[] hay, byte[] needle) {
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

    private static byte[] randomAscii() {
        var rnd = new Random(42);
        byte[] b = new byte[SIZE];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (0x20 + rnd.nextInt(0x5F));
        }
        return b;
    }

    private static byte[] pseudoEnglish() {
        String[] words = ("the of and a to in is you that it he was for on are as with his they i at be this "
                + "have from or one had by word but not what all were we when your can said there use an each "
                + "which she do how their if will up other about out many then them these so some her would make")
                .split(" ");
        var rnd = new Random(7);
        var sb = new StringBuilder(SIZE + 64);
        while (sb.length() < SIZE) {
            sb.append(words[rnd.nextInt(words.length)]).append(rnd.nextInt(20) == 0 ? '\n' : ' ');
        }
        return sb.substring(0, SIZE).getBytes(StandardCharsets.US_ASCII);
    }
}
