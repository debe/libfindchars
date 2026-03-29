package org.knownhosts.libfindchars.bench;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

/**
 * Generates deterministic CSV benchmark data.
 */
final class CsvDataGenerator {

    private CsvDataGenerator() {}

    record CsvConfig(
        int columns,
        int avgFieldLength,
        double quoteFrequency,
        double numericFraction,
        boolean crlf
    ) {
        static CsvConfig defaults() {
            return new CsvConfig(10, 20, 0.1, 0.3, false);
        }
    }

    static byte[] generateCsvData(SplittableRandom rng, int sizeBytes, CsvConfig config) {
        var baos = new ByteArrayOutputStream(sizeBytes + 4096);
        var writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
        String lineEnd = config.crlf() ? "\r\n" : "\n";

        try {
            // Header row
            for (int c = 0; c < config.columns(); c++) {
                if (c > 0) writer.write(',');
                writer.write("col_" + c);
            }
            writer.write(lineEnd);

            // Data rows until target size
            while (baos.size() < sizeBytes) {
                for (int c = 0; c < config.columns(); c++) {
                    if (c > 0) writer.write(',');
                    writeField(writer, rng, config);
                }
                writer.write(lineEnd);
                writer.flush();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }

    private static void writeField(Writer writer, SplittableRandom rng, CsvConfig config)
            throws IOException {
        if (rng.nextDouble() < config.numericFraction()) {
            writer.write(String.valueOf(rng.nextInt(1_000_000)));
        } else if (rng.nextDouble() < config.quoteFrequency()) {
            writer.write('"');
            int len = config.avgFieldLength() / 2 + rng.nextInt(config.avgFieldLength());
            for (int i = 0; i < len; i++) {
                double roll = rng.nextDouble();
                if (roll < 0.02) {
                    writer.write("\"\"");
                } else if (roll < 0.04) {
                    writer.write(',');
                } else if (roll < 0.05) {
                    writer.write('\n');
                } else {
                    writer.write('a' + rng.nextInt(26));
                }
            }
            writer.write('"');
        } else {
            int len = config.avgFieldLength() / 2 + rng.nextInt(config.avgFieldLength());
            for (int i = 0; i < len; i++) {
                writer.write('a' + rng.nextInt(26));
            }
        }
    }
}
