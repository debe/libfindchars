package org.knownhosts.libfindchars.bench;

import org.knownhosts.libfindchars.csv.CsvParser;
import org.knownhosts.libfindchars.generator.Utf8EngineBuilder;
import org.knownhosts.libfindchars.csv.CsvQuoteFilter;
import org.knownhosts.libfindchars.api.MatchStorage;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.SplittableRandom;

/**
 * Manual profiling harness for the full CSV parse path.
 * Uses {@code System.nanoTime()} for throughput measurement after C2 warmup.
 */
public class CsvManualProfile {

    public static void main(String[] args) throws Exception {
        var rng = new SplittableRandom(42);
        var config = new CsvDataGenerator.CsvConfig(10, 20, 0.1, 0.3, false);
        byte[] csvData = CsvDataGenerator.generateCsvData(rng, 50 * 1024 * 1024, config);

        var arena = Arena.ofAuto();
        var segment = arena.allocate(csvData.length);
        MemorySegment.copy(MemorySegment.ofArray(csvData), 0, segment, 0, csvData.length);

        // 1. Scan with filter only (no parse)
        var withFilter = Utf8EngineBuilder.builder()
                .codepoints("quote", '"').codepoints("delim", ',')
                .codepoints("lf", '\n').codepoints("cr", '\r')
                .chunkFilter(CsvQuoteFilter.class, "quote", "delim", "lf", "cr")
                .build();
        var filterEngine = withFilter.engine();
        var storage = new MatchStorage(csvData.length / 4, 64);

        // 2. Full CSV parser
        var parser = CsvParser.builder().delimiter(',').quote('"').hasHeader(true).build();

        // Warmup: 50 iters ensures C2 compiles the full engine + filter inlining chain.
        // Verify with -XX:+PrintCompilation.
        System.out.println("Warming up...");
        for (int i = 0; i < 50; i++) {
            filterEngine.find(segment, storage);
            parser.parse(segment);
        }

        double dataMb = csvData.length / (1024.0 * 1024.0);

        // Phase A: scan+filter only
        System.out.println("Phase A: scan+filter...");
        long start = System.nanoTime();
        int iters = 100;
        for (int i = 0; i < iters; i++) filterEngine.find(segment, storage);
        double filterMbps = (dataMb * iters) / ((System.nanoTime() - start) / 1e9);

        // Phase B: full CSV parse (the hot target)
        System.out.println("Phase B: full CSV parse...");
        start = System.nanoTime();
        int totalRows = 0;
        for (int i = 0; i < iters; i++) totalRows += parser.parse(segment).rowCount();
        double parseMbps = (dataMb * iters) / ((System.nanoTime() - start) / 1e9);

        System.out.printf("Scan+filter:  %.1f MB/s%n", filterMbps);
        System.out.printf("Full parse:   %.1f MB/s (%d rows/iter)%n", parseMbps, totalRows / iters);
        System.out.println("Done.");
    }
}
