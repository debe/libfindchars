package org.knownhosts.libfindchars.compiler;

import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.generator.EngineBuilder;
import org.knownhosts.libfindchars.generator.EngineConfiguration;
import org.knownhosts.libfindchars.generator.RangeOperation;
import org.knownhosts.libfindchars.generator.ShuffleOperation;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CompiledEngineTest {

    @Test
    void compiledEngineFindsAllMatches() {
        var config = EngineConfiguration.builder()
                .shuffleOperation(new ShuffleOperation(
                        new AsciiLiteralGroup("structurals",
                                new AsciiLiteral("whitespaces", "\r\n\t\f ".toCharArray()),
                                new AsciiLiteral("star", "*".toCharArray()),
                                new AsciiLiteral("plus", "+".toCharArray()))))
                .rangeOperations(new RangeOperation("comparison", 0x3c, 0x3e))
                .build();

        var result = EngineBuilder.build(config);
        var engine = result.engine();
        var literals = result.literals();

        assertNotNull(engine);
        assertInstanceOf(FindEngine.class, engine);
        assertFalse(engine.getClass().getName().contains("FindCharsEngine"),
                "Should be a generated class, not FindCharsEngine");

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
        var config = EngineConfiguration.builder()
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

        var result = EngineBuilder.build(config);
        var engine = result.engine();
        var literals = result.literals();

        assertEquals(6, literals.size(), "Should have 6 literals");
        assertTrue(literals.containsKey("whitespaces"));
        assertTrue(literals.containsKey("punctiations"));
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
        byte punctLit = literals.get("punctiations");
        assertEquals(1, view.getPositionAt(storage, 0));
        assertEquals(punctLit, view.getLiteralAt(storage, 0));
    }
}
