package org.knownhosts.libfindchars.bench;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.knownhosts.libfindchars.api.FindCharsEngine;
import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.FindMask;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.api.RangeOp;
import org.knownhosts.libfindchars.api.ShuffleMaskOp;
import org.knownhosts.libfindchars.api.Utf8FindCharsEngine;
import org.knownhosts.libfindchars.api.Utf8ShuffleMaskOp;
import org.knownhosts.libfindchars.api.Utf8ShuffleMaskOp.CharSpec;
import org.knownhosts.libfindchars.compiler.AsciiLiteral;
import org.knownhosts.libfindchars.compiler.AsciiLiteralGroup;
import org.knownhosts.libfindchars.compiler.LiteralCompiler;
import org.knownhosts.libfindchars.generator.EngineBuilder;
import org.knownhosts.libfindchars.generator.EngineConfiguration;
import org.knownhosts.libfindchars.generator.RangeOperation;
import org.knownhosts.libfindchars.generator.ShuffleOperation;
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

    // Same targets as Benchie.java / BenchController:
    // whitespaces(5) + punctuation(6) + star + plus + digits(10) + range <=> (3)
    // = 26 distinct ASCII chars, 2 shuffle groups + 1 range op
    private static final Pattern REGEX = Pattern.compile("[\\r\\n\\t\\f :;{}\\[\\]*+0-9<=>]");

    private byte[] data;
    private MemorySegment dataSegment;
    private long fileSize;
    private Arena arena;

    // Pure ASCII engine — compiled hidden class (EngineCodeGen + BytecodeInliner)
    private FindEngine asciiEngine;
    private MatchStorage asciiMatchStorage;

    // C2 JIT ASCII engine (FindCharsEngine w/ EngineKernel delegation + C2 JIT)
    private FindCharsEngine c2jitAsciiEngine;
    private MatchStorage c2jitAsciiStorage;

    // UTF-8 compiled hidden class (Utf8EngineCodeGen + BytecodeInliner)
    private FindEngine utf8Engine;
    private MatchStorage utf8MatchStorage;

    // UTF-8 C2 JIT engine (Utf8FindCharsEngine w/ Utf8ShuffleMaskOp)
    private Utf8FindCharsEngine c2jitUtf8Engine;
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

        // ASCII engine: exact same config as Benchie.java
        // whitespaces(5) + punctuation(6) + star(1) + plus(1) + digits(10) + range <=> (3) = 26
        var asciiConfig = EngineConfiguration.builder()
                .species(SPECIES)
                .shuffleOperation(new ShuffleOperation(
                        new AsciiLiteralGroup("structurals",
                                new AsciiLiteral("whitespaces", "\r\n\t\f ".toCharArray()),
                                new AsciiLiteral("punctiations", ":;{}[]".toCharArray()),
                                new AsciiLiteral("star", "*".toCharArray()),
                                new AsciiLiteral("plus", "+".toCharArray())),
                        new AsciiLiteralGroup("numbers",
                                new AsciiLiteral("nums", "0123456789".toCharArray()))))
                .rangeOperations(new RangeOperation("comparison", 0x3c, 0x3e))
                .build();
        var asciiResult = EngineBuilder.build(asciiConfig);
        asciiEngine = asciiResult.engine();
        asciiMatchStorage = new MatchStorage((int) fileSize / 7 << 2, SPECIES.vectorByteSize());

        // C2 JIT ASCII engine: solve separately, build ShuffleMaskOp + RangeOp
        try (var compiler = new LiteralCompiler()) {
            var masks = compiler.solve(
                    new AsciiLiteralGroup("structurals",
                            new AsciiLiteral("whitespaces", "\r\n\t\f ".toCharArray()),
                            new AsciiLiteral("punctiations", ":;{}[]".toCharArray()),
                            new AsciiLiteral("star", "*".toCharArray()),
                            new AsciiLiteral("plus", "+".toCharArray())),
                    new AsciiLiteralGroup("numbers",
                            new AsciiLiteral("nums", "0123456789".toCharArray())));
            var shuffleOp = new ShuffleMaskOp(SPECIES, masks);
            java.util.Set<Byte> used = new java.util.HashSet<>();
            for (FindMask m : masks) used.addAll(m.literals().values());
            byte rangeLit = 1;
            while (used.contains(rangeLit)) rangeLit++;
            var rangeOp = new RangeOp(SPECIES, (byte) 0x3c, (byte) 0x3e, rangeLit);
            c2jitAsciiEngine = new FindCharsEngine(SPECIES, shuffleOp, rangeOp);
        }
        c2jitAsciiStorage = new MatchStorage((int) fileSize / 7 << 2, SPECIES.vectorByteSize());

        // UTF-8 compiled engine: same 21 ASCII chars + 5 multi-byte chars = 26 total
        // Swaps *, +, <, =, > for £(U+00A3), œ(U+0153), ô(U+00F4), é(U+00E9), ™(U+2122)
        var utf8Result = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoints("whitespaces", '\r', '\n', '\t', '\f', ' ')
                .codepoints("punctiations", ':', ';', '{', '}', '[', ']')
                .codepoints("nums", '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
                .codepoint("pound", 0xA3)        // £  2-byte, 675 occurrences
                .codepoint("oe", 0x153)           // œ  2-byte, 306 occurrences
                .codepoint("ocirc", 0xF4)         // ô  2-byte, 99 occurrences
                .codepoint("eacute", 0xE9)        // é  2-byte, 45 occurrences
                .codepoint("trademark", 0x2122)   // ™  3-byte, 513 occurrences
                .build();
        utf8Engine = utf8Result.engine();
        utf8MatchStorage = new MatchStorage((int) fileSize / 7 << 2, SPECIES.vectorByteSize());

        // UTF-8 array-dispatch engine: same config, manually build Utf8FindCharsEngine
        var utf8AdResult = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoints("whitespaces", '\r', '\n', '\t', '\f', ' ')
                .codepoints("punctiations", ':', ';', '{', '}', '[', ']')
                .codepoints("nums", '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
                .codepoint("pound", 0xA3)
                .codepoint("oe", 0x153)
                .codepoint("ocirc", 0xF4)
                .codepoint("eacute", 0xE9)
                .codepoint("trademark", 0x2122)
                .build();
        // Utf8EngineBuilder now returns FindEngine (compiled). For C2 JIT comparison,
        // build Utf8FindCharsEngine directly from the same solved masks.
        c2jitUtf8Engine = buildC2JitUtf8Engine();
        c2jitUtf8Storage = new MatchStorage((int) fileSize / 7 << 2, SPECIES.vectorByteSize());
    }

    private Utf8FindCharsEngine buildC2JitUtf8Engine() throws Exception {
        // Re-solve with same config to get masks, then construct Utf8ShuffleMaskOp directly
        var builder = Utf8EngineBuilder.builder()
                .species(SPECIES)
                .codepoints("whitespaces", '\r', '\n', '\t', '\f', ' ')
                .codepoints("punctiations", ':', ';', '{', '}', '[', ']')
                .codepoints("nums", '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
                .codepoint("pound", 0xA3)
                .codepoint("oe", 0x153)
                .codepoint("ocirc", 0xF4)
                .codepoint("eacute", 0xE9)
                .codepoint("trademark", 0x2122);
        // Use a separate helper that builds the old-style engine
        return buildUtf8C2JitFromBuilder(builder);
    }

    /**
     * Build a Utf8FindCharsEngine (C2 JIT, non-compiled) using the same
     * solving logic as Utf8EngineBuilder but returning the old Utf8FindCharsEngine.
     */
    private static Utf8FindCharsEngine buildUtf8C2JitFromBuilder(Utf8EngineBuilder.Builder builder) {
        // We can't easily reuse the builder without duplicating solve logic.
        // Instead, solve fresh and construct manually.
        var sp = SPECIES;

        var entries = java.util.List.of(
            new Object[]{"whitespaces", new byte[]{'\r', '\n', '\t', '\f', ' '}, true},
            new Object[]{"punctiations", new byte[]{':', ';', '{', '}', '[', ']'}, true},
            new Object[]{"nums", new byte[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'}, true},
            new Object[]{"pound", "\u00A3".getBytes(java.nio.charset.StandardCharsets.UTF_8), false},
            new Object[]{"oe", "\u0153".getBytes(java.nio.charset.StandardCharsets.UTF_8), false},
            new Object[]{"ocirc", "\u00F4".getBytes(java.nio.charset.StandardCharsets.UTF_8), false},
            new Object[]{"eacute", "\u00E9".getBytes(java.nio.charset.StandardCharsets.UTF_8), false},
            new Object[]{"trademark", "\u2122".getBytes(java.nio.charset.StandardCharsets.UTF_8), false}
        );

        int maxRounds = 1;
        for (var e : entries) {
            if (!(boolean) e[2]) {
                maxRounds = Math.max(maxRounds, ((byte[]) e[1]).length);
            }
        }

        String[][] literalNames = new String[entries.size()][maxRounds];
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            if ((boolean) e[2]) {
                literalNames[i][0] = e[0] + "_r0";
            } else {
                for (int r = 0; r < ((byte[]) e[1]).length; r++) {
                    literalNames[i][r] = e[0] + "_r" + r;
                }
            }
        }

        var perRoundLiterals = new java.util.ArrayList<java.util.List<org.knownhosts.libfindchars.compiler.Literal>>();
        for (int r = 0; r < maxRounds; r++) {
            perRoundLiterals.add(new java.util.ArrayList<>());
        }

        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            byte[] bytes = (byte[]) e[1];
            if ((boolean) e[2]) {
                char[] chars = new char[bytes.length];
                for (int j = 0; j < bytes.length; j++) chars[j] = (char) (bytes[j] & 0xFF);
                perRoundLiterals.get(0).add(new org.knownhosts.libfindchars.compiler.ByteLiteral(literalNames[i][0], chars));
            } else {
                for (int r = 0; r < bytes.length; r++) {
                    char[] chars = {(char) (bytes[r] & 0xFF)};
                    perRoundLiterals.get(r).add(new org.knownhosts.libfindchars.compiler.ByteLiteral(literalNames[i][r], chars));
                }
            }
        }

        var roundMasks = new java.util.ArrayList<org.knownhosts.libfindchars.api.FindMask>();
        var usedLiterals = new java.util.ArrayList<Byte>();

        try (var compiler = new LiteralCompiler()) {
            for (int r = 0; r < maxRounds; r++) {
                var lits = perRoundLiterals.get(r);
                var group = new AsciiLiteralGroup("round" + r, lits);
                var masks = compiler.solve(usedLiterals, group);
                var mask = masks.getFirst();
                roundMasks.add(mask);
                usedLiterals.addAll(mask.literals().values());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Z3 solver error", ex);
        }

        var charSpecList = new java.util.ArrayList<CharSpec>();
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            byte[] bytes = (byte[]) e[1];
            if (!(boolean) e[2]) {
                ByteVector[] rlVecs = new ByteVector[bytes.length];
                byte finalLitByte = 0;
                for (int r = 0; r < bytes.length; r++) {
                    byte litByte = roundMasks.get(r).literalOf(literalNames[i][r]);
                    rlVecs[r] = ByteVector.broadcast(sp, litByte);
                    finalLitByte = litByte;
                }
                charSpecList.add(new CharSpec(bytes.length, rlVecs, ByteVector.broadcast(sp, finalLitByte)));
            }
        }

        var op = new Utf8ShuffleMaskOp(sp, roundMasks, charSpecList);
        return new Utf8FindCharsEngine(sp, op);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private int findAscii() {
        var view = asciiEngine.find(dataSegment, asciiMatchStorage);
        return asciiMatchStorage.getPositionsBuffer()[view.size() - 1];
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private int findAsciiC2Jit() {
        var view = c2jitAsciiEngine.find(dataSegment, c2jitAsciiStorage);
        return c2jitAsciiStorage.getPositionsBuffer()[view.size() - 1];
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
    public int simdAscii() {
        return findAscii();
    }

    @Benchmark
    public int simdAsciiC2Jit() {
        return findAsciiC2Jit();
    }

    @Benchmark
    public int simdUtf8() {
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
