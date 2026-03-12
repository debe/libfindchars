package org.knownhosts.libfindchars.bench;

import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.compiler.AsciiLiteral;
import org.knownhosts.libfindchars.compiler.AsciiLiteralGroup;
import org.knownhosts.libfindchars.generator.EngineBuilder;
import org.knownhosts.libfindchars.generator.EngineConfiguration;
import org.knownhosts.libfindchars.generator.ShuffleOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Random;
import java.util.regex.Pattern;

@RestController
public class BenchController {

    private final FindEngine findCharsEngine;
    private final URI testDataURI;
    private final Random random = new Random();
    private final byte STAR;
    private final byte WHITESPACES;
    private final byte PUNCTUATIONS;
    private final byte PLUS;

    public BenchController() {
        try {
            testDataURI = Objects.requireNonNull(BenchController.class.getClassLoader()
                    .getResource("3mb.txt")).toURI();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        var config = EngineConfiguration.builder()
                .shuffleOperation(
                        new ShuffleOperation(
                                new AsciiLiteralGroup(
                                        "structurals",
                                        new AsciiLiteral("whitespaces", "\r\n\t\f ".toCharArray()),
                                        new AsciiLiteral("punctiations", ":;{}[]".toCharArray()),
                                        new AsciiLiteral("star", "*".toCharArray()),
                                        new AsciiLiteral("plus", "+".toCharArray())
                                )
                        ))
                .build();
        var result = EngineBuilder.build(config);
        this.findCharsEngine = result.engine();
        var literals = result.literals();
        STAR = literals.get("star");
        WHITESPACES = literals.get("whitespaces");
        PUNCTUATIONS = literals.get("punctiations");
        PLUS = literals.get("plus");
    }

    @GetMapping("findchars")
    public long findchars() throws IOException {
        try(Arena arena = Arena.ofConfined();
            var channel = FileChannel.open(Path.of(testDataURI), StandardOpenOption.READ)) {
            var index = random.nextInt(128);
            var mappedFile = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
            var matchStorage = new MatchStorage((int) (channel.size() / 4), 32);
            var start = Instant.now();
            var match = findCharsEngine.find(mappedFile, matchStorage);
            var stop = Instant.now();
            return start.until(stop, ChronoUnit.NANOS);
        }
    }

    @GetMapping("regex")
    public long regex() throws IOException {
        try(Arena arena = Arena.ofConfined();
            var channel = FileChannel.open(Path.of(testDataURI), StandardOpenOption.READ)) {
            var index = random.nextInt(128);
            var mappedFile = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena).asByteBuffer();
            var scalarEngine = new ScalarEngine(Pattern.compile("[\r\n\t\f :;{}\\[\\]*+]"));
            var start = Instant.now();
            var res = scalarEngine.regex(mappedFile);
            var stop = Instant.now();
            return start.until(stop, ChronoUnit.NANOS);

        }
    }

    @GetMapping("bitset")
    public long bitset() throws IOException {
        try(Arena arena = Arena.ofConfined();
            var channel = FileChannel.open(Path.of(testDataURI), StandardOpenOption.READ)) {
            var index = random.nextInt(128);
            var mappedFile = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena).asByteBuffer();
            var scalarEngine = new ScalarEngine("\r\n\t\f :;{}[]*+");
            var start = Instant.now();
            var res = scalarEngine.bitset(mappedFile);
            var stop = Instant.now();
            return start.until(stop, ChronoUnit.NANOS);

        }
    }
}
