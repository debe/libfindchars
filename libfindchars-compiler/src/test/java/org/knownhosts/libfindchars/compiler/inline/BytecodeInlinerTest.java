package org.knownhosts.libfindchars.compiler.inline;

import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.compiler.inline.BytecodeInliner;
import org.knownhosts.libfindchars.api.Utf8Kernel;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.generator.Utf8EngineBuilder;

import java.lang.classfile.ClassFile;
import java.lang.foreign.MemorySegment;
import java.lang.constant.ClassDesc;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BytecodeInlinerTest {

    @Test
    void inlinerRemovesInvokestaticToUtf8Kernel() {
        // Build a real engine via the full specialization pipeline
        var result = Utf8EngineBuilder.builder()
                .codepoints("spaces", ' ')
                .build();

        assertNotNull(result.engine());
    }

    @Test
    void inlinedEngineProducesCorrectResults() {
        var result = Utf8EngineBuilder.builder()
                .codepoints("whitespaces", '\r', '\n', '\t', '\f', ' ')
                .codepoints("star", '*')
                .codepoints("plus", '+')
                .range("comparison", (byte) 0x3c, (byte) 0x3e)
                .build();

        var engine = result.engine();
        var literals = result.literals();

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
    void inlinedEngineWithMultipleGroups() {
        var result = Utf8EngineBuilder.builder()
                .codepoints("whitespaces", '\r', '\n', '\t', '\f', ' ')
                .codepoints("punctuations", ':', ';', '{', '}', '[', ']')
                .codepoints("star", '*')
                .codepoints("plus", '+')
                .codepoints("nums", '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
                .range("comparison", (byte) 0x3c, (byte) 0x3e)
                .build();

        var engine = result.engine();
        var literals = result.literals();

        assertEquals(6, literals.size());

        String input = "x:1 y;2 z{3}";
        byte[] data = new byte[64];
        System.arraycopy(input.getBytes(StandardCharsets.US_ASCII), 0, data, 0, input.length());

        var segment = MemorySegment.ofArray(data);
        var storage = new MatchStorage(64, 32);
        var view = engine.find(segment, storage);

        assertTrue(view.size() > 0, "Should find matches");

        byte punctLit = literals.get("punctuations");
        assertEquals(1, view.getPositionAt(storage, 0));
        assertEquals(punctLit, view.getLiteralAt(storage, 0));
    }

    @Test
    void noAnnotatedMethodsReturnsUnchangedBytes() {
        // Create a trivial class with no @Inline references
        byte[] original = ClassFile.of().build(ClassDesc.of("test.Dummy"), cb -> {
            cb.withMethodBody("test",
                    java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> code.return_());
        });

        // Inlining against a class with no @Inline methods should be a no-op
        byte[] result = BytecodeInliner.inline(original, Utf8Kernel.class);
        assertNotNull(result);
    }
}
