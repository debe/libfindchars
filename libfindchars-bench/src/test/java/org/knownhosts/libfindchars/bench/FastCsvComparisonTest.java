package org.knownhosts.libfindchars.bench;

import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.csv.CsvParser;
import org.knownhosts.libfindchars.csv.CsvToken;

import java.io.ByteArrayInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.SplittableRandom;

/**
 * Head-to-head: libfindchars SIMD CSV vs FastCSV.
 */
class FastCsvComparisonTest {

    @Test
    void compareAtVariousSizes() {
        // Build parser ONCE — Z3 compilation is a one-time cost, not per-benchmark
        var simdParser = CsvParser.builder().delimiter(',').quote('"').hasHeader(true).build();
        var arena = Arena.ofAuto();

        for (int sizeMb : new int[]{1, 10, 50}) {
            var rng = new SplittableRandom(42);
            var config = new CsvDataGenerator.CsvConfig(10, 20, 0.1, 0.3, false);
            byte[] csvData = CsvDataGenerator.generateCsvData(rng, sizeMb * 1024 * 1024, config);

            // libfindchars: MemorySegment (zero-copy, no String conversion)
            var segment = arena.allocate(csvData.length);
            MemorySegment.copy(MemorySegment.ofArray(csvData), 0, segment, 0, csvData.length);

            // Warmup both
            for (int i = 0; i < 10; i++) {
                simdParser.scan(segment);
                countRowsFastCsv(csvData);
            }

            int iters = Math.max(10, 200 / sizeMb);

            // libfindchars scan + rowCount (litBuf scan)
            long start = System.nanoTime();
            int simdRows = 0;
            for (int i = 0; i < iters; i++) {
                simdRows = simdParser.scan(segment).rowCount();
            }
            double countMbps = (csvData.length / (1024.0 * 1024.0) * iters) / ((System.nanoTime() - start) / 1e9);

            // libfindchars scan (zero-alloc token iteration)
            start = System.nanoTime();
            for (int i = 0; i < iters; i++) {
                var view = simdParser.scan(segment);
                int rows = 0;
                for (int j = 0; j < view.size(); j++) {
                    if (view.tokenAt(j) instanceof CsvToken.Newline) rows++;
                }
                simdRows = rows;
            }
            double simdMbps = (csvData.length / (1024.0 * 1024.0) * iters) / ((System.nanoTime() - start) / 1e9);

            // FastCSV — byte[] via InputStream (no String copy, fair comparison)
            start = System.nanoTime();
            int fastRows = 0;
            for (int i = 0; i < iters; i++) {
                fastRows = countRowsFastCsv(csvData);
            }
            double fastMbps = (csvData.length / (1024.0 * 1024.0) * iters) / ((System.nanoTime() - start) / 1e9);

            System.out.printf("%n=== %d MB CSV (%d rows) ===%n", sizeMb, simdRows);
            System.out.printf("  libfindchars rowCount:  %7.1f MB/s%n", countMbps);
            System.out.printf("  libfindchars scan:      %7.1f MB/s%n", simdMbps);
            System.out.printf("  FastCSV:                %7.1f MB/s%n", fastMbps);
        }
    }

    private int countRowsFastCsv(byte[] data) {
        int rows = 0;
        try (var reader = CsvReader.builder().ofCsvRecord(new ByteArrayInputStream(data))) {
            for (CsvRecord record : reader) {
                rows++;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return rows;
    }
}
