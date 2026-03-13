package zz.customname;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.generator.Utf8EngineBuilder;

/**
 * Demonstrates detecting multi-byte UTF-8 characters alongside ASCII.
 */
class FindUtf8Characters {

    public static void main(String[] args) {

        var result = Utf8EngineBuilder.builder()
                .codepoints("whitespace", ' ', '\n')
                .codepoint("eacute", 0xE9)       // é  2-byte
                .codepoint("trademark", 0x2122)   // ™  3-byte
                .codepoint("grin", 0x1F600)       // 😀 4-byte
                .build();
        var engine = result.engine();
        var literals = result.literals();

        byte wsLit = literals.get("whitespace");
        byte eLit = literals.get("eacute");
        byte tmLit = literals.get("trademark");
        byte grinLit = literals.get("grin");

        // UTF-8 test string: "café ™ 😀 fin\n"
        String input = "caf\u00E9 \u2122 \uD83D\uDE00 fin\n";
        byte[] utf8 = input.getBytes(StandardCharsets.UTF_8);

        // Pad to at least vector size
        byte[] data = new byte[Math.max(64, utf8.length)];
        System.arraycopy(utf8, 0, data, 0, utf8.length);

        var segment = MemorySegment.ofArray(data);
        var matchStorage = new MatchStorage(64, 32);
        var match = engine.find(segment, matchStorage);

        System.out.println("Input: " + input.trim());
        System.out.println("UTF-8 bytes: " + utf8.length);
        System.out.println("Matches found: " + match.size());

        for (int i = 0; i < match.size(); i++) {
            byte lit = match.getLiteralAt(matchStorage, i);
            int pos = match.getPositionAt(matchStorage, i);

            if (lit == wsLit) {
                System.out.println("  whitespace at byte " + pos);
            } else if (lit == eLit) {
                System.out.println("  é (U+00E9) at byte " + pos);
            } else if (lit == tmLit) {
                System.out.println("  ™ (U+2122) at byte " + pos);
            } else if (lit == grinLit) {
                System.out.println("  😀 (U+1F600) at byte " + pos);
            }
        }
    }
}
