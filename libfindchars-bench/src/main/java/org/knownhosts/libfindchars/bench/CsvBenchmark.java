package org.knownhosts.libfindchars.bench;

import org.knownhosts.libfindchars.csv.CsvParser;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark: SIMD CSV parser throughput.
 *
 * <p>Parameters encode: columns-quotePercent-crlf.
 * Data is generated deterministically at 100 MB.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = {
        "--enable-preview",
        "--add-modules=jdk.incubator.vector",
        "--add-modules=ALL-SYSTEM"
})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class CsvBenchmark {

    private static final long SEED = 42L;

    @Param({
        "10-10-false",
        "10-0-false",
        "10-50-false",
        "50-10-false",
    })
    private String config;

    @Param({"100"})
    private int sizeMb;

    private byte[] csvData;
    private MemorySegment csvSegment;
    private Arena arena;
    private CsvParser simdParser;

    @Setup(Level.Trial)
    public void setup() {
        var parts = config.split("-");
        int columns = Integer.parseInt(parts[0]);
        int quotePct = Integer.parseInt(parts[1]);
        boolean crlf = Boolean.parseBoolean(parts[2]);

        var rng = new SplittableRandom(SEED);
        int targetSize = sizeMb * 1024 * 1024;

        var csvConfig = new CsvDataGenerator.CsvConfig(
                columns, 20, quotePct / 100.0, 0.3, crlf);
        csvData = CsvDataGenerator.generateCsvData(rng, targetSize, csvConfig);

        arena = Arena.ofAuto();
        csvSegment = arena.allocate(csvData.length);
        MemorySegment.copy(MemorySegment.ofArray(csvData), 0, csvSegment, 0, csvData.length);

        simdParser = CsvParser.builder()
                .delimiter(',')
                .quote('"')
                .hasHeader(true)
                .build();
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int simdCsvParser() {
        var result = simdParser.parse(csvSegment);
        return result.rowCount();
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int simdCsvScan() {
        var view = simdParser.scan(csvSegment);
        // Count rows by iterating tokens — zero allocation
        int rows = 0;
        for (int i = 0; i < view.size(); i++) {
            if (view.tokenAt(i) instanceof org.knownhosts.libfindchars.csv.CsvToken.Newline) rows++;
        }
        return rows;
    }

}
