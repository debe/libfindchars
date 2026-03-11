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
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.compiler.AsciiLiteral;
import org.knownhosts.libfindchars.compiler.AsciiLiteralGroup;
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
        public FindCharsEngine runtimeEngine;

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

            var channel = FileChannel.open(
                    Path.of(Benchie.class.getClassLoader().getResource("3mb.txt").toURI()),
                    StandardOpenOption.READ);
            arena = Arena.ofAuto();
            segment = channel.map(MapMode.READ_ONLY, 0, channel.size(), arena);
            tapeStorage = new MatchStorage((int) channel.size() / 7 << 2, 32);
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

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
