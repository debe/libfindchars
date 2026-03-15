package org.knownhosts.libfindchars.bench;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.generator.Utf8EngineBuilder;

import org.openjdk.jmh.annotations.*;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 3, jvmArgs = {
        "--enable-preview",
        "--add-modules=jdk.incubator.vector",
        "--add-modules=ALL-SYSTEM"
})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@State(Scope.Benchmark)
public class Utf8Benchmark {

    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

    // ASCII targets: whitespaces(5) + punctuation(6) + star + plus + digits(10) + range <=> (3)
    private static final String ASCII_REGEX = "\\r\\n\\t\\f :;{}\\[\\]*+0-9<=>";

    // Target characters for random data generation (matching the regex/engine targets)
    private static final byte[] TARGET_CHARS = {
            '\r', '\n', '\t', '\f', ' ', ':', ';', '{', '}', '[', ']',
            '*', '+', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '<', '=', '>'
    };

    @Param({"3", "10", "50"})
    private int sizeMb;

    @Param({"file", "random"})
    private String dataSource;

    private byte[] data;
    private MemorySegment dataSegment;
    private Arena arena;
    private String preAllocatedString;
    private Pattern regex;

    // File-mode multi-byte codepoints (matching 3mb.txt content)
    private static final int[] FILE_MB_CODEPOINTS = {0xA3, 0x153, 0xF4, 0xE9, 0x2122};

    // ASCII-only compiled (TemplateTransformer + BytecodeInliner, no multi-byte codepoints)
    private FindEngine asciiEngine;
    private MatchStorage asciiMatchStorage;

    // UTF-8 compiled hidden class (TemplateTransformer + BytecodeInliner, with multi-byte)
    private FindEngine utf8Engine;
    private MatchStorage utf8MatchStorage;

    // UTF-8 C2 JIT engine (Utf8EngineTemplate used directly, with multi-byte)
    private FindEngine c2jitUtf8Engine;
    private MatchStorage c2jitUtf8Storage;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        int targetSize = sizeMb * 1024 * 1024;
        arena = Arena.ofAuto();

        int[] mbCodepoints;
        if ("random".equals(dataSource)) {
            var rng = new SplittableRandom(42);
            Set<Integer> used = new HashSet<>();
            mbCodepoints = BenchDataGenerator.pickMultiByteCodepoints(rng, used, 5);
            data = generateRandomData(targetSize, mbCodepoints, rng);
        } else {
            mbCodepoints = FILE_MB_CODEPOINTS;
            data = loadFileData(targetSize);
        }

        dataSegment = arena.allocate(data.length);
        MemorySegment.copy(MemorySegment.ofArray(data), 0, dataSegment, 0, data.length);

        preAllocatedString = new String(data, StandardCharsets.UTF_8);

        // Build regex pattern including multi-byte codepoints
        var regexBuilder = new StringBuilder("[");
        regexBuilder.append(ASCII_REGEX);
        for (int cp : mbCodepoints) {
            regexBuilder.append(Character.toString(cp));
        }
        regexBuilder.append("]");
        regex = Pattern.compile(regexBuilder.toString());

        // ASCII-only compiled engine: 26 ASCII chars via UTF-8 pipeline
        var asciiResult = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoints("whitespaces", '\r', '\n', '\t', '\f', ' ')
                .codepoints("punctuation", ':', ';', '{', '}', '[', ']')
                .codepoints("star", '*')
                .codepoints("plus", '+')
                .codepoints("nums", '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
                .range("comparison", (byte) 0x3c, (byte) 0x3e)
                .build();
        asciiEngine = asciiResult.engine();
        asciiMatchStorage = new MatchStorage(data.length / 7 << 2, SPECIES.vectorByteSize());

        // UTF-8 compiled engine: ASCII chars + multi-byte chars + range
        utf8Engine = buildUtf8Engine(mbCodepoints, true);
        utf8MatchStorage = new MatchStorage(data.length / 7 << 2, SPECIES.vectorByteSize());

        // UTF-8 C2 JIT engine: uses Utf8EngineTemplate directly (not compiled)
        c2jitUtf8Engine = buildUtf8Engine(mbCodepoints, false);
        c2jitUtf8Storage = new MatchStorage(data.length / 7 << 2, SPECIES.vectorByteSize());
    }

    private byte[] loadFileData(int targetSize) throws Exception {
        var filePath = Path.of(System.getProperty("bench.file",
                "libfindchars-bench/src/main/resources/3mb.txt"));
        try (var channel = FileChannel.open(filePath, StandardOpenOption.READ)) {
            int fileSize = (int) channel.size();
            var fileSegment = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize, arena);
            byte[] fileBytes = new byte[fileSize];
            MemorySegment.copy(fileSegment, 0, MemorySegment.ofArray(fileBytes), 0, fileSize);

            if (targetSize <= fileSize) {
                byte[] result = new byte[targetSize];
                System.arraycopy(fileBytes, 0, result, 0, targetSize);
                return result;
            }

            // Repeat file content to fill target size
            byte[] result = new byte[targetSize];
            int offset = 0;
            while (offset < targetSize) {
                int chunk = Math.min(fileSize, targetSize - offset);
                System.arraycopy(fileBytes, 0, result, offset, chunk);
                offset += chunk;
            }
            return result;
        }
    }

    private byte[] generateRandomData(int size, int[] mbCodepoints, SplittableRandom rng) {
        // Densities matching the 3mb.txt file:
        //   ASCII targets: 21.3% of codepoints
        //   Multi-byte codepoints: 1935 per 2,925,873 bytes ≈ 0.07% of codepoints
        double asciiTargetRate = 0.213;
        double mbRate = 1935.0 / 2_925_873; // ~0.07% of codepoints

        // Convert TARGET_CHARS to int[] for BenchDataGenerator
        int[] asciiTargets = new int[TARGET_CHARS.length];
        for (int i = 0; i < TARGET_CHARS.length; i++) {
            asciiTargets[i] = TARGET_CHARS[i] & 0xFF;
        }

        return BenchDataGenerator.generateData(rng, size, asciiTargets, mbCodepoints,
                asciiTargetRate, mbRate, true);
    }

    private FindEngine buildUtf8Engine(int[] mbCodepoints, boolean compiled) {
        var builder = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .compiled(compiled)
                .codepoints("whitespaces", '\r', '\n', '\t', '\f', ' ')
                .codepoints("punctuation", ':', ';', '{', '}', '[', ']')
                .codepoints("nums", '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
                .codepoints("star", '*')
                .codepoints("plus", '+');
        for (int i = 0; i < mbCodepoints.length; i++) {
            builder.codepoint("mb" + i, mbCodepoints[i]);
        }
        return builder.range("comparison", (byte) 0x3c, (byte) 0x3e)
                .build()
                .engine();
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private int findAscii() {
        var view = asciiEngine.find(dataSegment, asciiMatchStorage);
        return asciiMatchStorage.getPositionsBuffer()[view.size() - 1];
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private int findUtf8() {
        var view = utf8Engine.find(dataSegment, utf8MatchStorage);
        return utf8MatchStorage.getPositionsBuffer()[view.size() - 1];
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private int findUtf8C2Jit() {
        var view = c2jitUtf8Engine.find(dataSegment, c2jitUtf8Storage);
        return c2jitUtf8Storage.getPositionsBuffer()[view.size() - 1];
    }

    @Benchmark
    public int simdAsciiCompiled() {
        return findAscii();
    }

    @Benchmark
    public int simdUtf8Compiled() {
        return findUtf8();
    }

    @Benchmark
    public int simdUtf8C2Jit() {
        return findUtf8C2Jit();
    }

    @Benchmark
    public int regex() {
        var matcher = regex.matcher(preAllocatedString);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    @Benchmark
    public int regexWithConversion() {
        var str = new String(data);
        var matcher = regex.matcher(str);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
