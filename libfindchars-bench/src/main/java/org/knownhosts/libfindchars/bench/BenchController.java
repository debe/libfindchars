package org.knownhosts.libfindchars.bench;

import org.knownhosts.libfindchars.api.MatchStorage;
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

    private final FindCharsEngine findCharsEngine;
    private final URI testDataURI;
    private final Random random = new Random();
    public BenchController() {
        try {
            testDataURI = Objects.requireNonNull(FindCharsEngine.class.getClassLoader()
                    .getResource("3mb.txt")).toURI();
            findCharsEngine = new FindCharsEngine();

        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

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
            System.out.println("Size is: "+match.size());
            var stop = Instant.now();
                switch(match.getLiteralAt(matchStorage, index)) {
                    case FindCharsLiterals.STAR -> System.out.println("* at: "+ match.getPositionAt(matchStorage, index));
                    case FindCharsLiterals.WHITESPACES -> System.out.println("\\w at: "+ match.getPositionAt(matchStorage, index));
                    case FindCharsLiterals.PUNCTIATIONS -> System.out.println("punctuations at: "+ match.getPositionAt(matchStorage, index));
                    case FindCharsLiterals.PLUS -> System.out.println("+ at: "+ match.getPositionAt(matchStorage, index));
            }

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
            System.out.println("something found: "+ res.get(index));
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
            System.out.println("something found at: "+ res.get(index));
            return start.until(stop, ChronoUnit.NANOS);

        }
    }
}
