package org.knownhosts.libfindchars.bench;

import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import org.knownhosts.libfindchars.csv.CsvParser;
import org.openjdk.jmh.annotations.*;

import java.io.ByteArrayInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Parameterized sweep benchmark for SIMD CSV parser vs FastCSV across three dimensions:
 * column count, quote percentage, and average field length.
 *
 * <p>Uses a single composite {@code @Param} string to avoid Cartesian product explosion.
 * Format: {@code columns-quotePercent-avgFieldLen}.</p>
 *
 * <p>Baseline: 10 columns, 5% quotes, avg field length 16, 100 MB data.</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = {
        "--enable-preview",
        "--add-modules=jdk.incubator.vector",
        "--add-modules=ALL-SYSTEM"
})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class CsvSweepBenchmark {

    static final int DATA_SIZE = 100 * 1024 * 1024; // 100 MB
    private static final long SEED = 42L;

    // Composite param: columns-quotePercent-avgFieldLen
    // Sweep A: vary columns (quote=5, fieldLen=16)
    // Sweep B: vary quote % (columns=10, fieldLen=16)
    // Sweep C: vary field length (columns=10, quote=5)
    @Param({
            "5-5-16",     // A: columns=5
            "10-5-16",    // A/B/C baseline
            "25-5-16",    // A: columns=25
            "50-5-16",    // A: columns=50
            "100-5-16",   // A: columns=100
            "10-0-16",    // B: quote=0
            "10-25-16",   // B: quote=25
            "10-50-16",   // B: quote=50
            "10-5-10",    // C: fieldLen=10
            "10-5-25",    // C: fieldLen=25
            "10-5-50"     // C: fieldLen=50
    })
    private String config;

    private byte[] csvData;
    private MemorySegment csvSegment;
    private Arena arena;
    private CsvParser simdParser;

    @Setup(Level.Trial)
    public void setup() {
        var parts = config.split("-");
        int columns = Integer.parseInt(parts[0]);
        int quotePct = Integer.parseInt(parts[1]);
        int avgFieldLen = Integer.parseInt(parts[2]);

        var rng = new SplittableRandom(SEED);
        var csvConfig = new CsvDataGenerator.CsvConfig(
                columns, avgFieldLen, quotePct / 100.0, 0.3, false);
        csvData = CsvDataGenerator.generateCsvData(rng, DATA_SIZE, csvConfig);

        arena = Arena.ofAuto();
        // Pad to next 64-byte boundary + 64 so vector reads don't go OOB
        int paddedLen = ((csvData.length + 63) & ~63) + 64;
        csvSegment = arena.allocate(paddedLen);
        MemorySegment.copy(MemorySegment.ofArray(csvData), 0, csvSegment, 0, csvData.length);
        csvSegment = csvSegment.asSlice(0, csvData.length);

        simdParser = CsvParser.builder()
                .delimiter(',')
                .quote('"')
                .hasHeader(true)
                .build();
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long simdParse() {
        var result = simdParser.newInstance().parse(csvSegment);
        long bytes = 0;
        for (int r = 0; r < result.rowCount(); r++) {
            var row = result.row(r);
            for (int c = 0; c < row.fieldCount(); c++) {
                bytes += row.rawField(c).byteSize();
            }
        }
        return bytes;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int fastCsv() {
        int rows = 0;
        try (var reader = CsvReader.builder().ofCsvRecord(new ByteArrayInputStream(csvData))) {
            for (CsvRecord record : reader) {
                int fields = record.getFieldCount();
                for (int f = 0; f < fields; f++) {
                    record.getField(f);
                }
                rows++;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return rows;
    }
}
