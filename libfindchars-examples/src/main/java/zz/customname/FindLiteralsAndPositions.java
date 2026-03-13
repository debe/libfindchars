package zz.customname;

import java.lang.foreign.Arena;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.generator.Utf8EngineBuilder;

class FindLiteralsAndPositions {

    public static void main(String[] args) throws Exception {

        var result = Utf8EngineBuilder.builder()
                .codepoints("whitespaces", '\r', '\n', '\t', '\f', ' ')
                .codepoints("punctuation", ':', ';', '{', '}', '[', ']')
                .codepoints("star", '*')
                .codepoints("plus", '+')
                .codepoints("nums", '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
                .range("comparison", (byte) 0x3c, (byte) 0x3e)
                .build();
        var findCharsEngine = result.engine();
        var literals = result.literals();

        byte STAR = literals.get("star");
        byte WHITESPACES = literals.get("whitespaces");
        byte PUNCTUATION = literals.get("punctuation");
        byte PLUS = literals.get("plus");
        byte NUMS = literals.get("nums");
        byte COMPARISON = literals.get("comparison");

        var fileURI = FindLiteralsAndPositions.class.getClassLoader().getResource("dummy.txt").toURI();

        try (Arena arena = Arena.ofConfined();
             var channel = FileChannel.open(Path.of(fileURI), StandardOpenOption.READ)) {
            var mappedFile = channel.map(MapMode.READ_ONLY, 0, channel.size(), arena);
            var matchStorage = new MatchStorage((int) channel.size() / 4, 32);
            var match = findCharsEngine.find(mappedFile, matchStorage);

            for (int i = 0; i < match.size(); i++) {
                byte lit = match.getLiteralAt(matchStorage, i);
                int pos = match.getPositionAt(matchStorage, i);

                if (lit == STAR) {
                    System.out.println("* at: " + pos);
                } else if (lit == WHITESPACES) {
                    System.out.println("\\w at: " + pos);
                } else if (lit == PUNCTUATION) {
                    System.out.println("punctuations at: " + pos);
                } else if (lit == PLUS) {
                    System.out.println("+ at: " + pos);
                } else if (lit == NUMS) {
                    System.out.println("numbers at: " + pos);
                } else if (lit == COMPARISON) {
                    System.out.println("<>= at: " + pos);
                }
            }
        }
    }
}
