package org.knownhosts.libfindchars.compiler;

import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.api.ChunkFilter;
import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.Inline;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.api.MatchView;
import org.knownhosts.libfindchars.api.Utf8EngineTemplate;
import org.knownhosts.libfindchars.api.VpaKernel;
import org.knownhosts.libfindchars.generator.CompilationMode;
import org.knownhosts.libfindchars.generator.Utf8EngineBuilder;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CompiledEngineTest {

    /**
     * Minimal quote-toggle filter for parity testing. Same algorithm as CsvQuoteFilter
     * but lives in the compiler test module to avoid a cyclic dependency on csv.
     */
    public static final class TestQuoteFilter implements ChunkFilter {

        public static final TestQuoteFilter INSTANCE = new TestQuoteFilter();

        private TestQuoteFilter() {}

        @Inline
        public static ByteVector applyStatic(ByteVector accumulator, ByteVector zero,
                                              VectorSpecies<Byte> species,
                                              long[] state, byte[] scratchpad,
                                              ByteVector[] literals) {
            var quoteLit = literals[0];
            var quoteMask = accumulator.compare(VectorOperators.EQ, quoteLit);
            var quoteMarkers = (ByteVector) quoteMask.toVector();
            if (!quoteMask.anyTrue() && state[0] == 0) return accumulator;
            var insideQuotes = VpaKernel.prefixXor(quoteMarkers, zero, species);
            if (state[0] != 0) insideQuotes = insideQuotes.not();
            state[0] = (insideQuotes.lane(species.length() - 1) & 0xFF) != 0 ? 1 : 0;
            var isStructural = accumulator.compare(VectorOperators.NE, 0)
                    .and(accumulator.compare(VectorOperators.NE, quoteLit));
            var killMask = isStructural.and(insideQuotes.compare(VectorOperators.NE, 0));
            return accumulator.blend(zero, killMask);
        }

        @Override
        public ByteVector apply(ByteVector accumulator, ByteVector zero,
                                VectorSpecies<Byte> species,
                                long[] state, byte[] scratchpad,
                                ByteVector[] literals) {
            return applyStatic(accumulator, zero, species, state, scratchpad, literals);
        }
    }

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

    @Test
    void jitModeFindsAllMatches() {
        assertNonCompiledMode(CompilationMode.JIT);
    }

    @Test
    void aotModeFindsAllMatches() {
        assertNonCompiledMode(CompilationMode.AOT);
    }

    private void assertNonCompiledMode(CompilationMode mode) {
        var result = Utf8EngineBuilder.builder()
                .compilationMode(mode)
                .codepoints("whitespaces", '\r', '\n', '\t', '\f', ' ')
                .codepoints("star", '*')
                .codepoints("plus", '+')
                .range("comparison", (byte) 0x3c, (byte) 0x3e)
                .build();

        var engine = result.engine();
        assertInstanceOf(Utf8EngineTemplate.class, engine,
                mode + " mode should return Utf8EngineTemplate directly");

        String input = "hello world * foo+bar <=> end\n";
        byte[] data = new byte[64];
        byte[] inputBytes = input.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(inputBytes, 0, data, 0, inputBytes.length);

        var segment = MemorySegment.ofArray(data);
        var storage = new MatchStorage(64, 32);
        var view = engine.find(segment, storage);

        assertEquals(11, view.size(), mode + ": Expected 11 matches");

        var literals = result.literals();
        byte wsLit = literals.get("whitespaces");
        byte starLit = literals.get("star");
        byte plusLit = literals.get("plus");
        byte cmpLit = literals.get("comparison");

        int[] expectedPositions = {5, 11, 12, 13, 17, 21, 22, 23, 24, 25, 29};
        byte[] expectedLiterals = {wsLit, wsLit, starLit, wsLit, plusLit, wsLit, cmpLit, cmpLit, cmpLit, wsLit, wsLit};

        for (int i = 0; i < view.size(); i++) {
            assertEquals(expectedPositions[i], view.getPositionAt(storage, i),
                    mode + ": Position mismatch at index " + i);
            assertEquals(expectedLiterals[i], view.getLiteralAt(storage, i),
                    mode + ": Literal mismatch at index " + i);
        }
    }

    @Test
    void filteredEngineParityAcrossAllModes() {
        // COMP-005: byte-for-byte identical output across BYTECODE_INLINE, JIT, AOT
        // with a real chunk filter exercising cross-chunk carry via prefix XOR.
        String csv = "a,\"b,c\",d\n" + "\"" + ",".repeat(200) + "\",x\n";
        byte[] data = csv.getBytes(StandardCharsets.UTF_8);
        var segment = MemorySegment.ofArray(data);

        record ModeResult(CompilationMode mode, int size, long[] positions, byte[] literals) {}

        ModeResult[] results = new ModeResult[3];
        int i = 0;
        for (var mode : CompilationMode.values()) {
            var builder = Utf8EngineBuilder.builder()
                    .compilationMode(mode)
                    .codepoints("quote", '"')
                    .codepoints("delim", ',')
                    .codepoints("lf", '\n')
                    .codepoints("cr", '\r');
            if (mode == CompilationMode.AOT) {
                builder.chunkFilter(TestQuoteFilter.INSTANCE, "quote");
            } else {
                builder.chunkFilter(TestQuoteFilter.class, "quote");
            }
            var result = builder.build();
            var engine = result.engine();

            var storage = new MatchStorage(512, 64);
            var view = engine.find(segment, storage);

            long[] positions = new long[view.size()];
            byte[] lits = new byte[view.size()];
            for (int j = 0; j < view.size(); j++) {
                positions[j] = view.getPositionAt(storage, j);
                lits[j] = view.getLiteralAt(storage, j);
            }
            results[i++] = new ModeResult(mode, view.size(), positions, lits);
        }

        // All three modes must produce identical match count, positions, and literals
        for (int m = 1; m < results.length; m++) {
            assertEquals(results[0].size(), results[m].size(),
                    results[0].mode() + " vs " + results[m].mode() + ": match count differs");
            for (int j = 0; j < results[0].size(); j++) {
                assertEquals(results[0].positions()[j], results[m].positions()[j],
                        results[0].mode() + " vs " + results[m].mode() + ": position mismatch at " + j);
                assertEquals(results[0].literals()[j], results[m].literals()[j],
                        results[0].mode() + " vs " + results[m].mode() + ": literal mismatch at " + j);
            }
        }

        // Sanity: the filter should have masked commas inside quotes
        assertTrue(results[0].size() > 0, "Should have matches");
    }

    @Test
    void deprecatedCompiledFalseStillWorks() {
        @SuppressWarnings("deprecation")
        var result = Utf8EngineBuilder.builder()
                .compiled(false)
                .codepoints("ws", ' ', '\n')
                .build();

        assertInstanceOf(Utf8EngineTemplate.class, result.engine());

        var storage = new MatchStorage(64, 32);
        var view = result.engine().find(
                MemorySegment.ofArray("a b\n".getBytes(StandardCharsets.US_ASCII)), storage);
        assertEquals(2, view.size());
    }
}
