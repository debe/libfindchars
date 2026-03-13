package org.knownhosts.libfindchars.compiler;

import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.generator.Utf8EngineBuilder;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CompiledEngineTest {

    @Test
    void compiledEngineFindsAllMatches() {
        var result = Utf8EngineBuilder.builder()
                .codepoints("whitespaces", '\r', '\n', '\t', '\f', ' ')
                .codepoints("star", '*')
                .codepoints("plus", '+')
                .range("comparison", (byte) 0x3c, (byte) 0x3e)
                .build();

        var engine = result.engine();
        var literals = result.literals();

        assertNotNull(engine);
        assertInstanceOf(FindEngine.class, engine);

        // Test data: "hello world * foo+bar <=> end\n" padded to 64 bytes
        String input = "hello world * foo+bar <=> end\n";
        byte[] data = new byte[64];
        byte[] inputBytes = input.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(inputBytes, 0, data, 0, inputBytes.length);

        var segment = MemorySegment.ofArray(data);
        var storage = new MatchStorage(64, 32);
        var view = engine.find(segment, storage);

        // Expected: 5(ws) 11(ws) 12(*) 13(ws) 17(+) 21(ws) 22(<) 23(=) 24(>) 25(ws) 29(\n)
        assertEquals(11, view.size(), "Expected 11 matches");

        byte wsLit = literals.get("whitespaces");
        byte starLit = literals.get("star");
        byte plusLit = literals.get("plus");
        byte cmpLit = literals.get("comparison");

        // Verify each match position and literal
        int[] expectedPositions = {5, 11, 12, 13, 17, 21, 22, 23, 24, 25, 29};
        byte[] expectedLiterals = {wsLit, wsLit, starLit, wsLit, plusLit, wsLit, cmpLit, cmpLit, cmpLit, wsLit, wsLit};

        for (int i = 0; i < view.size(); i++) {
            assertEquals(expectedPositions[i], view.getPositionAt(storage, i),
                    "Position mismatch at index " + i);
            assertEquals(expectedLiterals[i], view.getLiteralAt(storage, i),
                    "Literal mismatch at index " + i);
        }
    }

    @Test
    void compiledEngineWithMultipleGroups() {
        var result = Utf8EngineBuilder.builder()
                .codepoints("whitespaces", '\r', '\n', '\t', '\f', ' ')
                .codepoints("punctuation", ':', ';', '{', '}', '[', ']')
                .codepoints("star", '*')
                .codepoints("plus", '+')
                .codepoints("nums", '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
                .range("comparison", (byte) 0x3c, (byte) 0x3e)
                .build();

        var engine = result.engine();
        var literals = result.literals();

        assertEquals(6, literals.size(), "Should have 6 literals");
        assertTrue(literals.containsKey("whitespaces"));
        assertTrue(literals.containsKey("punctuation"));
        assertTrue(literals.containsKey("star"));
        assertTrue(literals.containsKey("plus"));
        assertTrue(literals.containsKey("nums"));
        assertTrue(literals.containsKey("comparison"));

        // Test with data containing digits and punctuation
        String input = "x:1 y;2 z{3}";
        byte[] data = new byte[64];
        System.arraycopy(input.getBytes(StandardCharsets.US_ASCII), 0, data, 0, input.length());

        var segment = MemorySegment.ofArray(data);
        var storage = new MatchStorage(64, 32);
        var view = engine.find(segment, storage);

        assertTrue(view.size() > 0, "Should find matches");

        // Verify colon at position 1 has punctuation literal
        byte punctLit = literals.get("punctuation");
        assertEquals(1, view.getPositionAt(storage, 0));
        assertEquals(punctLit, view.getLiteralAt(storage, 0));
    }
}
