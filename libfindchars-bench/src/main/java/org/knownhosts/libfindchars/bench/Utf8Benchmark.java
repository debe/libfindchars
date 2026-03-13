package org.knownhosts.libfindchars.bench;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    // Same targets: whitespaces(5) + punctuation(6) + star + plus + digits(10) + range <=> (3)
    private static final Pattern REGEX = Pattern.compile("[\\r\\n\\t\\f :;{}\\[\\]*+0-9<=>]");

    private byte[] data;
    private MemorySegment dataSegment;
    private long fileSize;
    private Arena arena;

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
        // mmap the 3mb.txt file directly — pass -Dbench.file=<path> to override
        var filePath = Path.of(System.getProperty("bench.file",
                "libfindchars-bench/src/main/resources/3mb.txt"));
        var channel = FileChannel.open(filePath, StandardOpenOption.READ);
        arena = Arena.ofAuto();
        dataSegment = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
        fileSize = channel.size();
        data = new byte[(int) fileSize];
        MemorySegment.copy(dataSegment, 0, MemorySegment.ofArray(data), 0, fileSize);

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
        asciiMatchStorage = new MatchStorage((int) fileSize / 7 << 2, SPECIES.vectorByteSize());

        // UTF-8 compiled engine: ASCII chars + multi-byte chars + range
        var utf8Result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoints("whitespaces", '\r', '\n', '\t', '\f', ' ')
                .codepoints("punctuation", ':', ';', '{', '}', '[', ']')
                .codepoints("nums", '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
                .codepoints("star", '*')
                .codepoints("plus", '+')
                .codepoint("pound", 0xA3)        // £  2-byte, 675 occurrences
                .codepoint("oe", 0x153)           // œ  2-byte, 306 occurrences
                .codepoint("ocirc", 0xF4)         // ô  2-byte, 99 occurrences
                .codepoint("eacute", 0xE9)        // é  2-byte, 45 occurrences
                .codepoint("trademark", 0x2122)   // ™  3-byte, 513 occurrences
                .range("comparison", (byte) 0x3c, (byte) 0x3e)
                .build();
        utf8Engine = utf8Result.engine();
        utf8MatchStorage = new MatchStorage((int) fileSize / 7 << 2, SPECIES.vectorByteSize());

        // UTF-8 C2 JIT engine: uses Utf8EngineTemplate directly (not compiled)
        c2jitUtf8Engine = buildC2JitUtf8Engine();
        c2jitUtf8Storage = new MatchStorage((int) fileSize / 7 << 2, SPECIES.vectorByteSize());
    }

    private FindEngine buildC2JitUtf8Engine() {
        return Utf8EngineBuilder.builder()
                .species(SPECIES)
                .compiled(false)
                .codepoints("whitespaces", '\r', '\n', '\t', '\f', ' ')
                .codepoints("punctuation", ':', ';', '{', '}', '[', ']')
                .codepoints("nums", '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
                .codepoints("star", '*')
                .codepoints("plus", '+')
                .codepoint("pound", 0xA3)
                .codepoint("oe", 0x153)
                .codepoint("ocirc", 0xF4)
                .codepoint("eacute", 0xE9)
                .codepoint("trademark", 0x2122)
                .range("comparison", (byte) 0x3c, (byte) 0x3e)
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
        var str = new String(data);
        var matcher = REGEX.matcher(str);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
