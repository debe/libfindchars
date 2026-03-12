package org.knownhosts.libfindchars.experiments;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import org.knownhosts.libfindchars.api.FindCharsEngine;
import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.FindMask;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.api.RangeOp;
import org.knownhosts.libfindchars.api.ShuffleMaskOp;
import org.knownhosts.libfindchars.compiler.AsciiLiteral;
import org.knownhosts.libfindchars.compiler.AsciiLiteralGroup;
import org.knownhosts.libfindchars.compiler.LiteralCompiler;
import org.knownhosts.libfindchars.generator.EngineBuilder;
import org.knownhosts.libfindchars.generator.EngineConfiguration;
import org.knownhosts.libfindchars.generator.RangeOperation;
import org.knownhosts.libfindchars.generator.ShuffleOperation;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;


@BenchmarkMode(Mode.SampleTime)
@Fork(value = 1, warmups = 1)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 10)
public class Benchie {


    @State(Scope.Thread)
    public static class StateObj {
        public MemorySegment segment;
        public MatchStorage tapeStorage;

        // old generated engine (hardcoded masks, 2 groups + comparison range)
        public org.knownhosts.libfindchars.experiments.FindCharsEngine generatedEngine;

        // new runtime engine (same config: 2 groups + comparison range)
        public FindEngine runtimeEngine;

        // C2 JIT engine (FindCharsEngine w/ EngineKernel delegation + C2 JIT)
        public FindCharsEngine c2jitEngine;
        public MatchStorage c2jitStorage;

        private Arena arena;

        @TearDown
        public void tearDown() {
            arena.close();
        }

        @Setup
        public void init() throws Exception {
            // old generated engine with hardcoded masks
            generatedEngine = new org.knownhosts.libfindchars.experiments.FindCharsEngine();

            // new runtime engine — same character classes as the generated one:
            // 2 literal groups (structurals + numbers) + comparison range (0x3c-0x3e)
            var config = EngineConfiguration.builder()
                    .shuffleOperation(
                            new ShuffleOperation(
                                    new AsciiLiteralGroup(
                                            "structurals",
                                            new AsciiLiteral("whitespaces", "\r\n\t\f ".toCharArray()),
                                            new AsciiLiteral("punctiations", ":;{}[]".toCharArray()),
                                            new AsciiLiteral("star", "*".toCharArray()),
                                            new AsciiLiteral("plus", "+".toCharArray())
                                    ),
                                    new AsciiLiteralGroup(
                                            "numbers",
                                            new AsciiLiteral("nums", "0123456789".toCharArray())
                                    )
                            ))
                    .rangeOperations(new RangeOperation("comparison", 0x3c, 0x3e))
                    .build();
            var result = EngineBuilder.build(config);
            runtimeEngine = result.engine();

            // C2 JIT engine: solve separately, build ShuffleMaskOp + RangeOp
            try (var compiler = new LiteralCompiler()) {
                var masks = compiler.solve(
                        new AsciiLiteralGroup("structurals",
                                new AsciiLiteral("whitespaces", "\r\n\t\f ".toCharArray()),
                                new AsciiLiteral("punctiations", ":;{}[]".toCharArray()),
                                new AsciiLiteral("star", "*".toCharArray()),
                                new AsciiLiteral("plus", "+".toCharArray())),
                        new AsciiLiteralGroup("numbers",
                                new AsciiLiteral("nums", "0123456789".toCharArray())));
                var shuffleOp = new ShuffleMaskOp(masks);
                java.util.Set<Byte> used = new java.util.HashSet<>();
                for (FindMask m : masks) used.addAll(m.literals().values());
                byte rangeLit = 1;
                while (used.contains(rangeLit)) rangeLit++;
                var rangeOp = new RangeOp((byte) 0x3c, (byte) 0x3e, rangeLit);
                c2jitEngine = new FindCharsEngine(shuffleOp, rangeOp);
            }

            var channel = FileChannel.open(
                    Path.of(Benchie.class.getClassLoader().getResource("3mb.txt").toURI()),
                    StandardOpenOption.READ);
            arena = Arena.ofAuto();
            segment = channel.map(MapMode.READ_ONLY, 0, channel.size(), arena);
            tapeStorage = new MatchStorage((int) channel.size() / 7 << 2, 32);
            c2jitStorage = new MatchStorage((int) channel.size() / 7 << 2, 32);
        }
    }

    @Benchmark
    public void generatedEngine(Blackhole bh, StateObj stateObj) throws IOException, URISyntaxException {
        var tape = stateObj.generatedEngine.find(stateObj.segment, stateObj.tapeStorage);
        bh.consume(tape.getPositionAt(stateObj.tapeStorage, 5));
    }

    @Benchmark
    public void runtimeEngine(Blackhole bh, StateObj stateObj) throws IOException, URISyntaxException {
        var tape = stateObj.runtimeEngine.find(stateObj.segment, stateObj.tapeStorage);
        bh.consume(tape.getPositionAt(stateObj.tapeStorage, 5));
    }

    @Benchmark
    public void c2jitEngine(Blackhole bh, StateObj stateObj) throws IOException, URISyntaxException {
        var tape = stateObj.c2jitEngine.find(stateObj.segment, stateObj.c2jitStorage);
        bh.consume(tape.getPositionAt(stateObj.c2jitStorage, 5));
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
