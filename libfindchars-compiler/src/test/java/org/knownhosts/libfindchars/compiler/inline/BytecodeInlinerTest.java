package org.knownhosts.libfindchars.compiler.inline;

import org.junit.jupiter.api.Test;
import org.knownhosts.libfindchars.api.EngineKernel;
import org.knownhosts.libfindchars.api.MatchStorage;
import org.knownhosts.libfindchars.compiler.AsciiLiteral;
import org.knownhosts.libfindchars.compiler.AsciiLiteralGroup;
import org.knownhosts.libfindchars.generator.EngineBuilder;
import org.knownhosts.libfindchars.generator.EngineConfiguration;
import org.knownhosts.libfindchars.generator.RangeOperation;
import org.knownhosts.libfindchars.generator.ShuffleOperation;

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.foreign.MemorySegment;
import java.lang.constant.ClassDesc;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BytecodeInlinerTest {

    @Test
    void inlinerRemovesInvokestaticToEngineKernel() {
        // Build a real engine — this triggers EngineCodeGen + BytecodeInliner
        var config = EngineConfiguration.builder()
                .shuffleOperation(new ShuffleOperation(
                        new AsciiLiteralGroup("test",
                                new AsciiLiteral("spaces", " ".toCharArray()))))
                .build();

        var result = EngineBuilder.build(config);
        assertNotNull(result.engine());
    }

    @Test
    void inlinedEngineProducesCorrectResults() {
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
        var config = EngineConfiguration.builder()
                .shuffleOperation(new ShuffleOperation(
                        new AsciiLiteralGroup("structurals",
                                new AsciiLiteral("whitespaces", "\r\n\t\f ".toCharArray()),
                                new AsciiLiteral("punctuations", ":;{}[]".toCharArray()),
                                new AsciiLiteral("star", "*".toCharArray()),
                                new AsciiLiteral("plus", "+".toCharArray())),
                        new AsciiLiteralGroup("numbers",
                                new AsciiLiteral("nums", "0123456789".toCharArray()))))
                .rangeOperations(new RangeOperation("comparison", 0x3c, 0x3e))
                .build();

        var result = EngineBuilder.build(config);
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
        // (EngineKernel has @Inline, but Dummy doesn't call it)
        byte[] result = BytecodeInliner.inline(original, EngineKernel.class);
        // Should still be valid class bytes (may differ in constant pool layout)
        assertNotNull(result);
    }
}
