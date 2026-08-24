package org.knownhosts.libfindchars.bench.needle;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;

/**
 * {@code String.indexOf} vs. the strategies in this package, on Lemire's adversarial input
 * and on ordinary data.
 *
 * <p>The {@code simdScan} arm is the one that matters for judging the q-gram filter: it is a
 * competent SIMD full scan (memchr's rare-byte-pair approach), so it isolates the value of
 * <em>skipping</em> from the value of <em>vectorizing</em>.</p>
 *
 * <p>Run: {@code java --add-modules=jdk.incubator.vector -jar target/libfindchars-bench-*.jar
 * NeedleBenchmark}</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgs = {"--add-modules=jdk.incubator.vector"})
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class NeedleBenchmark {

    @Param({"lemire", "random", "text"})
    public String kind;

    @Param({"16", "256", "4096"})
    public int m;

    private MemorySegment data;
    private String hayString;
    private String needleString;
    private long expected;

    private NeedleFinder finder;
    private TwoWay twoWay;
    private SimdScan simdScan;

    @Setup(Level.Trial)
    public void setup() {
        var c = Datasets.of(kind, m);
        data = MemorySegment.ofArray(c.hay());
        hayString = new String(c.hay(), StandardCharsets.US_ASCII);
        needleString = new String(c.needle(), StandardCharsets.US_ASCII);
        expected = c.expected();

        finder = NeedleFinder.of(c.needle());
        twoWay = NeedleFinder.twoWayFor(c.needle());
        simdScan = NeedleFinder.simdScanFor(c.needle());

        // Every arm must agree before any of them is timed.
        check(finder.find(data), "needleFinder");
        check(twoWay.find(data, 0, data.byteSize()), "twoWay");
        check(simdScan.find(data, data.byteSize()), "simdScan");
        check(hayString.indexOf(needleString), "indexOf");
    }

    private void check(long got, String who) {
        if (got != expected) {
            throw new IllegalStateException(who + " returned " + got + ", expected " + expected);
        }
    }

    @Benchmark
    public long indexOf() {
        return hayString.indexOf(needleString);
    }

    @Benchmark
    public long twoWay() {
        return twoWay.find(data, 0, data.byteSize());
    }

    @Benchmark
    public long simdScan() {
        return simdScan.find(data, data.byteSize());
    }

    @Benchmark
    public long needleFinder() {
        return finder.find(data);
    }
}
