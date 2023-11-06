package zz.customname;


import java.lang.foreign.Arena;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.knownhosts.libfindchars.api.MatchStorage;

class FindLiteralsAndPositions {

    public static void main(String[] args) throws Exception {

        var findCharsEngine = new FindCharsEngine();
        var fileURI = FindLiteralsAndPositions.class.getClassLoader().getResource("dummy.txt").toURI();

        try (Arena arena = Arena.ofConfined();
             var channel = FileChannel.open(Path.of(fileURI), StandardOpenOption.READ)) {
            var mappedFile = channel.map(MapMode.READ_ONLY, 0, channel.size(), arena);
            var matchStorage = new MatchStorage((int) channel.size() / 4, 32);
            var match = findCharsEngine.find(mappedFile, matchStorage);

            for (int i = 0; i < match.size(); i++) {

                switch (match.getLiteralAt(matchStorage, i)) {
                    case FindCharsLiterals.STAR -> System.out.println("* at: " + match.getPositionAt(matchStorage, i));
                    case FindCharsLiterals.WHITESPACES ->
                            System.out.println("\\w at: " + match.getPositionAt(matchStorage, i));
                    case FindCharsLiterals.PUNCTIATIONS ->
                            System.out.println("punctuations at: " + match.getPositionAt(matchStorage, i));
                    case FindCharsLiterals.PLUS -> System.out.println("+ at: " + match.getPositionAt(matchStorage, i));
                    case FindCharsLiterals.NUMS ->
                            System.out.println("numbers at: " + match.getPositionAt(matchStorage, i));
                    case FindCharsLiterals.COMPARISON ->
                            System.out.println("<>= at: " + match.getPositionAt(matchStorage, i));
                }
            }
        }
    }

}
