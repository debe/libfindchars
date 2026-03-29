package org.knownhosts.libfindchars.bench;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.knownhosts.libfindchars.bench.BenchDataGenerator.*;

import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.generator.Utf8EngineBuilder;

import org.openjdk.jmh.annotations.*;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Parameterized sweep benchmark measuring SIMD vs regex across four dimensions:
 * ASCII literal count, target density, multi-byte codepoint count, and group count.
 *
 * <p>Uses a single composite {@code @Param} string to avoid Cartesian product explosion.
 * Format: {@code asciiCount-density-multiByteCount-groups}.</p>
 *
 * <p>Baseline: 8 ASCII literals, 15% density, 0 multi-byte, 2 groups, 10 MB data.</p>
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
public class SweepBenchmark {

    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    private static final int DATA_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final long SEED = 42;

    // Composite param: asciiCount-density-multiByteCount-groups
    // Sweep A: vary ASCII count (density=15, mb=0, groups=2)
    // Sweep B: vary density (ascii=8, mb=0, groups=2)
    // Sweep C: vary multi-byte count (ascii=8, density=15, groups=2)
    // Sweep D: vary group count (ascii=8, density=15, mb=0)
    @Param({
            "2-15-0-2",   // A: ascii=2
            "4-15-0-2",   // A: ascii=4
            "8-15-0-2",   // A/B/C/D baseline
            "12-15-0-2",  // A: ascii=12
            "20-15-0-2",  // A: ascii=20
            "8-5-0-2",    // B: density=5
            "8-30-0-2",   // B: density=30
            "8-50-0-2",   // B: density=50
            "8-15-1-2",   // C: mb=1
            "8-15-2-2",   // C: mb=2
            "8-15-3-2",   // C: mb=3
            "8-15-0-1",   // D: groups=1
            "8-15-0-4",   // D: groups=4
            "8-15-0-8"    // D: groups=8
    })
    private String config;

    private int asciiCount;
    private int density;
    private int multiByteCount;
    private int groups;

    private byte[] data;
    private MemorySegment dataSegment;
    private Arena arena;
    private String preAllocatedString;
    private Pattern regex;
    private FindEngine engine;
    private MatchStorage matchStorage;
    private FindEngine c2jitEngine;
    private MatchStorage c2jitStorage;

    @Setup(Level.Trial)
    public void setup() {
        var parts = config.split("-");
        asciiCount = Integer.parseInt(parts[0]);
        density = Integer.parseInt(parts[1]);
        multiByteCount = Integer.parseInt(parts[2]);
        groups = Integer.parseInt(parts[3]);

        var rng = new SplittableRandom(SEED);

        // Pick random ASCII characters (0x21–0x7E)
        Set<Integer> usedAscii = new LinkedHashSet<>();
        int[] asciiChars = pickUnusedAsciiChars(rng, usedAscii, asciiCount);

        // Pick random multi-byte codepoints
        Set<Integer> usedCodepoints = new HashSet<>();
        int[] mbCodepoints = pickMultiByteCodepoints(rng, usedCodepoints, multiByteCount);

        // Generate data with target density
        double asciiDensity = density / 100.0;
        double multiDensity = multiByteCount > 0 ? 0.005 : 0.0;
        boolean emitMbFiller = multiByteCount > 0;
        data = generateData(rng, DATA_SIZE, asciiChars, mbCodepoints, asciiDensity, multiDensity, emitMbFiller);

        arena = Arena.ofAuto();
        // Pad to next 64-byte boundary + 64 so vector reads don't go OOB
        int paddedLen = ((data.length + 63) & ~63) + 64;
        dataSegment = arena.allocate(paddedLen);
        MemorySegment.copy(MemorySegment.ofArray(data), 0, dataSegment, 0, data.length);
        preAllocatedString = new String(data, StandardCharsets.UTF_8);

        // Build regex
        regex = Pattern.compile(buildRegexPattern(asciiChars, mbCodepoints));

        // Build SIMD compiled engine: distribute ASCII chars across groups round-robin
        engine = buildEngine(asciiChars, mbCodepoints, true);
        matchStorage = new MatchStorage(data.length / 4, SPECIES.vectorByteSize());

        // Build C2 JIT engine (same config, not compiled)
        c2jitEngine = buildEngine(asciiChars, mbCodepoints, false);
        c2jitStorage = new MatchStorage(data.length / 4, SPECIES.vectorByteSize());
    }

    private FindEngine buildEngine(int[] asciiChars, int[] mbCodepoints, boolean compiled) {
        var builder = Utf8EngineBuilder.builder().species(SPECIES).compiled(compiled);
        int[][] groupedChars = distributeRoundRobin(asciiChars, groups);
        for (int g = 0; g < groupedChars.length; g++) {
            if (groupedChars[g].length > 0) {
                builder.codepoints("g" + g, groupedChars[g]);
            }
        }
        for (int m = 0; m < mbCodepoints.length; m++) {
            builder.codepoint("mb" + m, mbCodepoints[m]);
        }
        return builder.build().engine();
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long simdCompiled() {
        var view = engine.find(dataSegment, matchStorage);
        return matchStorage.getPositionsBuffer()[view.size() - 1];
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long simdC2Jit() {
        var view = c2jitEngine.find(dataSegment, c2jitStorage);
        return c2jitStorage.getPositionsBuffer()[view.size() - 1];
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int regex() {
        var matcher = regex.matcher(preAllocatedString);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public int regexWithConversion() {
        var str = new String(data);
        var matcher = regex.matcher(str);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    // --- Helper methods ---

    private static String buildRegexPattern(int[] asciiChars, int[] mbCodepoints) {
        var sb = new StringBuilder("[");
        for (int ch : asciiChars) {
            sb.append(escapeForCharClass((char) ch));
        }
        for (int cp : mbCodepoints) {
            sb.append(Character.toString(cp));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escapeForCharClass(char ch) {
        return switch (ch) {
            case ']' -> "\\]";
            case '\\' -> "\\\\";
            case '^' -> "\\^";
            case '-' -> "\\-";
            case '[' -> "\\[";
            default -> String.valueOf(ch);
        };
    }

    private static int[][] distributeRoundRobin(int[] chars, int groupCount) {
        int actualGroups = Math.min(groupCount, chars.length);
        List<List<Integer>> groups = new ArrayList<>();
        for (int g = 0; g < actualGroups; g++) {
            groups.add(new ArrayList<>());
        }
        for (int i = 0; i < chars.length; i++) {
            groups.get(i % actualGroups).add(chars[i]);
        }
        int[][] result = new int[actualGroups][];
        for (int g = 0; g < actualGroups; g++) {
            result[g] = groups.get(g).stream().mapToInt(Integer::intValue).toArray();
        }
        return result;
    }
}
