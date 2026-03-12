package org.knownhosts.libfindchars.generator;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;
import org.knownhosts.libfindchars.api.EngineKernel;
import org.knownhosts.libfindchars.api.FindEngine;
import org.knownhosts.libfindchars.api.Utf8Kernel;
import org.knownhosts.libfindchars.compiler.inline.BytecodeInliner;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessFlag;

/**
 * Generates a specialized FindEngine implementation for UTF-8 using the ClassFile API.
 * <p>
 * Parallel to {@link EngineCodeGen} but for the UTF-8 engine. The generated class stores
 * all vectors as individual final fields (no arrays in the hot path) and delegates SIMD
 * logic to {@link Utf8Kernel} and {@link EngineKernel} static methods via invokestatic.
 * The {@link BytecodeInliner} then transplants the method bodies at the bytecode level,
 * producing flat inlined bytecodes without method calls in the hot path.
 * <p>
 * VectorSpecies and derived constants (vectorByteSize, intSpecies, intBatchSize) are emitted
 * as {@code getstatic} / {@code iconst} rather than instance field loads, enabling C2 to
 * recognize them as compile-time constants and intrinsify Vector API operations.
 *
 * <h2>Loop structure</h2>
 * <p>The generated {@code find()} method uses a carry-forward chunk pattern:
 * <ol>
 *   <li>Pre-load the first chunk via {@code fromMemorySegment(data, 0)}</li>
 *   <li>Main loop ({@code i + 2*vectorByteSize <= dataSize}): use the carried-forward
 *       chunk, load round inputs at {@code i+1..i+(maxRounds-1)} via unaligned
 *       {@code fromMemorySegment} (single {@code vmovdqu} each, no {@code slice()}),
 *       then pre-load the next chunk at {@code i+vectorByteSize}</li>
 *   <li>Tail (unconditional, at most once): chunk already loaded from pre-load,
 *       round inputs from a zero-padded {@code byte[]} buffer</li>
 * </ol>
 * <p>This avoids {@code ByteVector.slice(int, Vector)} which is NOT intrinsified on
 * x86 AVX2 and falls back to scalar {@code bOpTemplate} element-by-element loops.
 */
public final class Utf8EngineCodeGen {

    // --- Type descriptors ---
    private static final ClassDesc CD_OBJECT = ConstantDescs.CD_Object;
    private static final ClassDesc CD_FIND_ENGINE = ClassDesc.of("org.knownhosts.libfindchars.api.FindEngine");
    private static final ClassDesc CD_MATCH_VIEW = ClassDesc.of("org.knownhosts.libfindchars.api.MatchView");
    private static final ClassDesc CD_MATCH_STORAGE = ClassDesc.of("org.knownhosts.libfindchars.api.MatchStorage");
    private static final ClassDesc CD_ENGINE_KERNEL = ClassDesc.of("org.knownhosts.libfindchars.api.EngineKernel");
    private static final ClassDesc CD_UTF8_KERNEL = ClassDesc.of("org.knownhosts.libfindchars.api.Utf8Kernel");
    private static final ClassDesc CD_BYTE_VECTOR = ClassDesc.of("jdk.incubator.vector.ByteVector");
    private static final ClassDesc CD_INT_VECTOR = ClassDesc.of("jdk.incubator.vector.IntVector");
    private static final ClassDesc CD_VECTOR_SPECIES = ClassDesc.of("jdk.incubator.vector.VectorSpecies");
    private static final ClassDesc CD_MEMORY_SEGMENT = ClassDesc.of("java.lang.foreign.MemorySegment");
    private static final ClassDesc CD_VALUE_LAYOUT = ClassDesc.of("java.lang.foreign.ValueLayout");
    private static final ClassDesc CD_BYTE_ORDER = ClassDesc.of("java.nio.ByteOrder");
    private static final ClassDesc CD_BYTE_VECTOR_ARR = CD_BYTE_VECTOR.arrayType();
    private static final ClassDesc CD_BYTE_VECTOR_ARR2 = CD_BYTE_VECTOR_ARR.arrayType();
    private static final ClassDesc CD_MATH = ClassDesc.of("java.lang.Math");

    // --- Field names ---
    private static final String F_ZERO = "zero";
    private static final String F_CLASSIFY_VEC = "classifyVec";
    private static final String F_LOW_MASK = "lowMask";
    private static final String F_LIT_CACHE = "literalCacheSparse";
    private static final String F_POS_CACHE = "positionCache";

    private static String lowLutField(int r) { return "lowLUT_" + r; }
    private static String highLutField(int r) { return "highLUT_" + r; }
    private static String litField(int r, int l) { return "lit_" + r + "_" + l; }
    private static String roundLitVecField(int s, int r) { return "roundLitVec_" + s + "_" + r; }
    private static String finalLitVecField(int s) { return "finalLitVec_" + s; }

    // --- Constructor parameter slots (generated class) ---
    private static final int CTOR_ZERO = 1;
    private static final int CTOR_CLASSIFY_VEC = 2;
    private static final int CTOR_LOW_MASK = 3;
    private static final int CTOR_LOW_LUTS = 4;
    private static final int CTOR_HIGH_LUTS = 5;
    private static final int CTOR_LITERALS = 6;
    private static final int CTOR_CHAR_SPEC_BYTE_LENGTHS = 7;
    private static final int CTOR_CHAR_SPEC_ROUND_LIT_VECS = 8;
    private static final int CTOR_CHAR_SPEC_FINAL_LIT_VECS = 9;

    // --- find() local variable slots ---
    private static final int FIND_DATA = 1;
    private static final int FIND_MATCH_STORAGE = 2;
    private static final int FIND_FILE_OFFSET = 3;
    private static final int FIND_GLOBAL_COUNT = 4;
    private static final int FIND_DATA_SIZE = 5;
    private static final int FIND_I = 6;
    private static final int FIND_CHUNK = 7;
    private static final int FIND_ACCUMULATOR = 8;
    private static final int FIND_CLASSIFY = 9;
    private static final int FIND_R0 = 10;
    private static final int FIND_R1 = 11;
    private static final int FIND_R2 = 12;
    private static final int FIND_R3 = 13;
    private static final int FIND_PADDED = 14;
    private static final int FIND_TEMP = 15;

    // --- Code-gen-time constants ---
    private final int maxRounds;
    private final int[] literalCounts; // per round
    private final int charSpecCount;
    private final int[] charSpecByteLengths;
    private final ClassDesc classDesc;
    private final String speciesFieldName;
    private final String intSpeciesFieldName;
    private final int vectorByteSize;
    private final int intBatchSize;

    private Utf8EngineCodeGen(int maxRounds, int[] literalCounts, int charSpecCount,
                              int[] charSpecByteLengths,
                              String speciesFieldName, String intSpeciesFieldName,
                              int vectorByteSize, int intBatchSize) {
        this.maxRounds = maxRounds;
        this.literalCounts = literalCounts;
        this.charSpecCount = charSpecCount;
        this.charSpecByteLengths = charSpecByteLengths;
        this.classDesc = ClassDesc.of("org.knownhosts.libfindchars.generator.CompiledUtf8Engine");
        this.speciesFieldName = speciesFieldName;
        this.intSpeciesFieldName = intSpeciesFieldName;
        this.vectorByteSize = vectorByteSize;
        this.intBatchSize = intBatchSize;
    }

    private static String byteSpeciesField(VectorSpecies<Byte> species) {
        if (species == ByteVector.SPECIES_64)  return "SPECIES_64";
        if (species == ByteVector.SPECIES_128) return "SPECIES_128";
        if (species == ByteVector.SPECIES_256) return "SPECIES_256";
        if (species == ByteVector.SPECIES_512) return "SPECIES_512";
        if (species == ByteVector.SPECIES_PREFERRED) return "SPECIES_PREFERRED";
        throw new IllegalArgumentException("Unknown byte species: " + species);
    }

    /**
     * Compile a specialized FindEngine for UTF-8 from pre-computed vectors.
     */
    public static FindEngine compile(
            VectorSpecies<Byte> species,
            ByteVector zero,
            ByteVector classifyVec,
            ByteVector lowMask,
            ByteVector[] lowLUTs,
            ByteVector[] highLUTs,
            ByteVector[][] literalVecs,
            int[] charSpecByteLengths,
            ByteVector[][] charSpecRoundLitVecs,
            ByteVector[] charSpecFinalLitVecs,
            VectorSpecies<Integer> intSpecies,
            int intBatchSize) {

        int rounds = lowLUTs.length;
        int[] litCounts = new int[rounds];
        for (int r = 0; r < rounds; r++) {
            litCounts[r] = literalVecs[r].length;
        }

        var speciesField = byteSpeciesField(species);
        // IntVector species share the same SPECIES_XXX field names for the same bit width
        var intSpeciesField = speciesField;

        var gen = new Utf8EngineCodeGen(rounds, litCounts, charSpecByteLengths.length,
                charSpecByteLengths, speciesField, intSpeciesField,
                species.vectorByteSize(), intBatchSize);
        byte[] classBytes = gen.generateClassBytes();

        // Inline @Inline-annotated methods from both kernel classes
        classBytes = BytecodeInliner.inline(classBytes, Utf8Kernel.class);
        classBytes = BytecodeInliner.inline(classBytes, EngineKernel.class);

        try {
            var lookup = MethodHandles.lookup();
            var hidden = lookup.defineHiddenClass(classBytes, true);
            var clazz = hidden.lookupClass();

            var ctor = clazz.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return (FindEngine) ctor.newInstance(
                    zero, classifyVec, lowMask,
                    lowLUTs, highLUTs, literalVecs,
                    charSpecByteLengths, charSpecRoundLitVecs, charSpecFinalLitVecs);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate compiled UTF-8 engine", e);
        }
    }

    // ========================================================================
    // Class generation
    // ========================================================================

    private byte[] generateClassBytes() {
        return ClassFile.of().build(classDesc, cb -> {
            cb.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL, AccessFlag.SYNTHETIC);
            cb.withInterfaceSymbols(CD_FIND_ENGINE);

            generateFields(cb);
            generateConstructor(cb);
            generateFindMethod(cb);
        });
    }

    // ========================================================================
    // Fields
    // ========================================================================

    private void generateFields(java.lang.classfile.ClassBuilder cb) {
        // Common fields
        field(cb, F_ZERO, CD_BYTE_VECTOR);
        field(cb, F_CLASSIFY_VEC, CD_BYTE_VECTOR);
        field(cb, F_LOW_MASK, CD_BYTE_VECTOR);

        // Per-round fields
        for (int r = 0; r < maxRounds; r++) {
            field(cb, lowLutField(r), CD_BYTE_VECTOR);
            field(cb, highLutField(r), CD_BYTE_VECTOR);
            for (int l = 0; l < literalCounts[r]; l++) {
                field(cb, litField(r, l), CD_BYTE_VECTOR);
            }
        }

        // Per-charSpec fields
        for (int s = 0; s < charSpecCount; s++) {
            for (int r = 0; r < charSpecByteLengths[s]; r++) {
                field(cb, roundLitVecField(s, r), CD_BYTE_VECTOR);
            }
            field(cb, finalLitVecField(s), CD_BYTE_VECTOR);
        }

        // Decode state
        field(cb, F_LIT_CACHE, ConstantDescs.CD_byte.arrayType());
        field(cb, F_POS_CACHE, ConstantDescs.CD_int.arrayType());
    }

    private void field(java.lang.classfile.ClassBuilder cb, String name, ClassDesc type) {
        cb.withField(name, type, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
    }

    // ========================================================================
    // Constructor
    // ========================================================================

    private void generateConstructor(java.lang.classfile.ClassBuilder cb) {
        var ctorDesc = MethodTypeDesc.of(ConstantDescs.CD_void,
                CD_BYTE_VECTOR,             // zero
                CD_BYTE_VECTOR,             // classifyVec
                CD_BYTE_VECTOR,             // lowMask
                CD_BYTE_VECTOR_ARR,         // lowLUTs
                CD_BYTE_VECTOR_ARR,         // highLUTs
                CD_BYTE_VECTOR_ARR2,        // literalVecs[][]
                ConstantDescs.CD_int.arrayType(), // charSpecByteLengths
                CD_BYTE_VECTOR_ARR2,        // charSpecRoundLitVecs[][]
                CD_BYTE_VECTOR_ARR);        // charSpecFinalLitVecs

        cb.withMethodBody(ConstantDescs.INIT_NAME, ctorDesc, ClassFile.ACC_PUBLIC, code -> {
            // super()
            code.aload(0);
            code.invokespecial(CD_OBJECT, ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void));

            // this.zero = zero
            storeField(code, F_ZERO, CD_BYTE_VECTOR, () -> code.aload(CTOR_ZERO));
            storeField(code, F_CLASSIFY_VEC, CD_BYTE_VECTOR, () -> code.aload(CTOR_CLASSIFY_VEC));
            storeField(code, F_LOW_MASK, CD_BYTE_VECTOR, () -> code.aload(CTOR_LOW_MASK));

            // Per-round: lowLUT_r = lowLUTs[r], highLUT_r = highLUTs[r], lit_r_l = literalVecs[r][l]
            for (int r = 0; r < maxRounds; r++) {
                final int ri = r;
                storeField(code, lowLutField(r), CD_BYTE_VECTOR, () -> {
                    code.aload(CTOR_LOW_LUTS);
                    emitIconst(code, ri);
                    code.aaload();
                });
                storeField(code, highLutField(r), CD_BYTE_VECTOR, () -> {
                    code.aload(CTOR_HIGH_LUTS);
                    emitIconst(code, ri);
                    code.aaload();
                });
                for (int l = 0; l < literalCounts[r]; l++) {
                    final int li = l;
                    storeField(code, litField(r, l), CD_BYTE_VECTOR, () -> {
                        code.aload(CTOR_LITERALS);
                        emitIconst(code, ri);
                        code.aaload(); // literalVecs[r]
                        emitIconst(code, li);
                        code.aaload(); // literalVecs[r][l]
                    });
                }
            }

            // Per-charSpec: roundLitVec_s_r = charSpecRoundLitVecs[s][r], finalLitVec_s = charSpecFinalLitVecs[s]
            for (int s = 0; s < charSpecCount; s++) {
                final int si = s;
                for (int r = 0; r < charSpecByteLengths[s]; r++) {
                    final int ri = r;
                    storeField(code, roundLitVecField(s, r), CD_BYTE_VECTOR, () -> {
                        code.aload(CTOR_CHAR_SPEC_ROUND_LIT_VECS);
                        emitIconst(code, si);
                        code.aaload(); // charSpecRoundLitVecs[s]
                        emitIconst(code, ri);
                        code.aaload(); // charSpecRoundLitVecs[s][r]
                    });
                }
                storeField(code, finalLitVecField(s), CD_BYTE_VECTOR, () -> {
                    code.aload(CTOR_CHAR_SPEC_FINAL_LIT_VECS);
                    emitIconst(code, si);
                    code.aaload();
                });
            }

            // Decode caches (sizes known at code-gen time)
            storeField(code, F_LIT_CACHE, ConstantDescs.CD_byte.arrayType(), () -> {
                emitIconst(code, vectorByteSize);
                code.newarray(java.lang.classfile.TypeKind.BYTE);
            });
            storeField(code, F_POS_CACHE, ConstantDescs.CD_int.arrayType(), () -> {
                emitIconst(code, intBatchSize);
                code.newarray(java.lang.classfile.TypeKind.INT);
            });

            code.return_();
        });
    }

    // ========================================================================
    // find() method
    // ========================================================================

    private void generateFindMethod(java.lang.classfile.ClassBuilder cb) {
        var findDesc = MethodTypeDesc.of(CD_MATCH_VIEW, CD_MEMORY_SEGMENT, CD_MATCH_STORAGE);

        cb.withMethodBody("find", findDesc, ClassFile.ACC_PUBLIC, code -> {
            // int fileOffset = 0
            code.iconst_0();
            code.istore(FIND_FILE_OFFSET);

            // int globalCount = 0
            code.iconst_0();
            code.istore(FIND_GLOBAL_COUNT);

            // int dataSize = (int) data.byteSize()
            code.aload(FIND_DATA);
            code.invokeinterface(CD_MEMORY_SEGMENT, "byteSize",
                    MethodTypeDesc.of(ConstantDescs.CD_long));
            code.l2i();
            code.istore(FIND_DATA_SIZE);

            // int i = 0
            code.iconst_0();
            code.istore(FIND_I);

            // Guard: if (vectorByteSize > dataSize) skip everything
            Label noData = code.newLabel();
            emitIconst(code, vectorByteSize);
            code.iload(FIND_DATA_SIZE);
            code.if_icmpgt(noData);

            // Pre-load first chunk — carried forward across iterations
            emitLoadFromMemorySegment(code, FIND_CHUNK);

            // === Main loop: i + 2*vectorByteSize <= dataSize ===
            Label mainLoopStart = code.newLabel();
            Label mainLoopEnd = code.newLabel();

            code.labelBinding(mainLoopStart);

            // if (i + 2*vectorByteSize > dataSize) goto mainLoopEnd
            code.iload(FIND_I);
            emitIconst(code, vectorByteSize * 2);
            code.iadd();
            code.iload(FIND_DATA_SIZE);
            code.if_icmpgt(mainLoopEnd);

            // matchStorage.ensureSize(vectorByteSize, globalCount)
            emitEnsureSize(code);

            // [emit body: chunk already in FIND_CHUNK, round offset loads from memory]
            emitMainLoopBody(code);

            // Pre-load next chunk: chunk = fromMemorySegment(species, data, i + vectorByteSize, nativeOrder)
            emitPreloadNextChunk(code);

            // fileOffset += vectorByteSize; i += vectorByteSize
            emitAdvance(code);

            code.goto_(mainLoopStart);
            code.labelBinding(mainLoopEnd);

            // === Tail: chunk already loaded (from pre-load or initial load) ===
            // Always executes when outer guard passed (at most 1 iteration)
            emitEnsureSize(code);

            if (maxRounds > 1) {
                // padded = new byte[vectorByteSize + maxRounds - 1]
                emitIconst(code, vectorByteSize + maxRounds - 1);
                code.newarray(java.lang.classfile.TypeKind.BYTE);
                code.astore(FIND_PADDED);

                // copyLen = Math.min(dataSize - i, vectorByteSize + maxRounds - 1)
                code.iload(FIND_DATA_SIZE);
                code.iload(FIND_I);
                code.isub();
                emitIconst(code, vectorByteSize + maxRounds - 1);
                code.invokestatic(CD_MATH, "min",
                        MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int, ConstantDescs.CD_int));
                code.istore(FIND_TEMP);

                // MemorySegment.copy(data, JAVA_BYTE, (long)i, padded, 0, copyLen)
                code.aload(FIND_DATA);
                code.getstatic(CD_VALUE_LAYOUT, "JAVA_BYTE",
                        ClassDesc.of("java.lang.foreign.ValueLayout$OfByte"));
                code.iload(FIND_I);
                code.i2l();
                code.aload(FIND_PADDED);
                code.iconst_0();
                code.iload(FIND_TEMP);
                code.invokestatic(CD_MEMORY_SEGMENT, "copy",
                        MethodTypeDesc.of(ConstantDescs.CD_void,
                                CD_MEMORY_SEGMENT,
                                CD_VALUE_LAYOUT,
                                ConstantDescs.CD_long,
                                ConstantDescs.CD_Object,
                                ConstantDescs.CD_int,
                                ConstantDescs.CD_int),
                        true);
            }

            emitTailBody(code);

            code.labelBinding(noData);

            // return new MatchView(globalCount)
            code.new_(CD_MATCH_VIEW);
            code.dup();
            code.iload(FIND_GLOBAL_COUNT);
            code.invokespecial(CD_MATCH_VIEW, ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int));
            code.areturn();
        });
    }

    // ========================================================================
    // Loop body emission
    // ========================================================================

    /** Main loop body: round inputs via unaligned {@code fromMemorySegment(data, i+r)}. */
    private void emitMainLoopBody(CodeBuilder code) {
        loadField(code, F_ZERO, CD_BYTE_VECTOR);
        code.astore(FIND_ACCUMULATOR);

        emitApplyRoundMask(code, 0, FIND_CHUNK, FIND_R0);

        if (maxRounds > 1 || charSpecCount > 0) {
            Label multiPath = code.newLabel();
            Label decode = code.newLabel();

            // High-bit test: cheaper than classify shuffle, no classify needed on ASCII path
            code.aload(FIND_CHUNK);
            code.invokestatic(CD_UTF8_KERNEL, "hasNonAscii",
                    MethodTypeDesc.of(ConstantDescs.CD_boolean, CD_BYTE_VECTOR));
            code.ifne(multiPath);

            // --- ASCII fast path (fall-through, no classify) ---
            emitGateAsciiSimple(code);
            code.goto_(decode);

            // --- Multi-byte path: classify only computed here ---
            code.labelBinding(multiPath);
            emitClassify(code);
            for (int r = 1; r < maxRounds; r++) {
                emitLoadFromMemorySegmentAtOffset(code, r, FIND_TEMP);
                emitApplyRoundMask(code, r, FIND_TEMP, FIND_R0 + r);
            }
            emitGateAsciiOnly(code);
            for (int s = 0; s < charSpecCount; s++) {
                emitGateMultiByte(code, s);
            }

            code.labelBinding(decode);
        } else {
            emitGateAsciiSimple(code);
        }

        emitDecodeCall(code);
    }

    /** Tail body: round inputs via {@code fromArray(padded, r)} on a zero-padded buffer. */
    private void emitTailBody(CodeBuilder code) {
        loadField(code, F_ZERO, CD_BYTE_VECTOR);
        code.astore(FIND_ACCUMULATOR);

        emitApplyRoundMask(code, 0, FIND_CHUNK, FIND_R0);

        if (maxRounds > 1 || charSpecCount > 0) {
            Label multiPath = code.newLabel();
            Label decode = code.newLabel();

            code.aload(FIND_CHUNK);
            code.invokestatic(CD_UTF8_KERNEL, "hasNonAscii",
                    MethodTypeDesc.of(ConstantDescs.CD_boolean, CD_BYTE_VECTOR));
            code.ifne(multiPath);

            // --- ASCII fast path ---
            emitGateAsciiSimple(code);
            code.goto_(decode);

            // --- Multi-byte slow path ---
            code.labelBinding(multiPath);
            emitClassify(code);
            for (int r = 1; r < maxRounds; r++) {
                code.getstatic(CD_BYTE_VECTOR, speciesFieldName, CD_VECTOR_SPECIES);
                code.aload(FIND_PADDED);
                emitIconst(code, r);
                code.invokestatic(CD_BYTE_VECTOR, "fromArray",
                        MethodTypeDesc.of(CD_BYTE_VECTOR, CD_VECTOR_SPECIES,
                                ConstantDescs.CD_byte.arrayType(), ConstantDescs.CD_int));
                code.astore(FIND_TEMP);
                emitApplyRoundMask(code, r, FIND_TEMP, FIND_R0 + r);
            }
            emitGateAsciiOnly(code);
            for (int s = 0; s < charSpecCount; s++) {
                emitGateMultiByte(code, s);
            }

            code.labelBinding(decode);
        } else {
            emitGateAsciiSimple(code);
        }

        emitDecodeCall(code);
    }

    /** Emit: classify = Utf8Kernel.classify(chunk, classifyVec, lowMask) */
    private void emitClassify(CodeBuilder code) {
        code.aload(FIND_CHUNK);
        loadField(code, F_CLASSIFY_VEC, CD_BYTE_VECTOR);
        loadField(code, F_LOW_MASK, CD_BYTE_VECTOR);
        code.invokestatic(CD_UTF8_KERNEL, "classify",
                MethodTypeDesc.of(CD_BYTE_VECTOR, CD_BYTE_VECTOR, CD_BYTE_VECTOR, CD_BYTE_VECTOR));
        code.astore(FIND_CLASSIFY);
    }

    /** Emit: full ASCII gate with classify check (for multi-byte path where classify is available). */
    private void emitGateAsciiOnly(CodeBuilder code) {
        code.aload(FIND_ACCUMULATOR);
        code.aload(FIND_R0);
        code.aload(FIND_CLASSIFY);
        code.invokestatic(CD_UTF8_KERNEL, "gateAscii",
                MethodTypeDesc.of(CD_BYTE_VECTOR, CD_BYTE_VECTOR, CD_BYTE_VECTOR, CD_BYTE_VECTOR));
        code.astore(FIND_ACCUMULATOR);
    }

    /** Emit: simplified ASCII gate without classify (for pure-ASCII fast path). */
    private void emitGateAsciiSimple(CodeBuilder code) {
        code.aload(FIND_ACCUMULATOR);
        code.aload(FIND_R0);
        code.invokestatic(CD_UTF8_KERNEL, "gateAsciiOnly",
                MethodTypeDesc.of(CD_BYTE_VECTOR, CD_BYTE_VECTOR, CD_BYTE_VECTOR));
        code.astore(FIND_ACCUMULATOR);
    }


    // ========================================================================
    // Emit applyRoundMaskN: invokestatic Utf8Kernel.applyRoundMaskN(input, lowLUT, highLUT, lowMask, zero, lit...)
    // ========================================================================

    private void emitApplyRoundMask(CodeBuilder code, int round, int inputSlot, int resultSlot) {
        int litCount = literalCounts[round];

        // Push args
        code.aload(inputSlot);
        loadField(code, lowLutField(round), CD_BYTE_VECTOR);
        loadField(code, highLutField(round), CD_BYTE_VECTOR);
        loadField(code, F_LOW_MASK, CD_BYTE_VECTOR);
        loadField(code, F_ZERO, CD_BYTE_VECTOR);
        for (int l = 0; l < litCount; l++) {
            loadField(code, litField(round, l), CD_BYTE_VECTOR);
        }

        // Build method type: (ByteVector * (5 + litCount)) -> ByteVector
        var paramTypes = new ClassDesc[5 + litCount];
        for (int i = 0; i < paramTypes.length; i++) {
            paramTypes[i] = CD_BYTE_VECTOR;
        }
        var mtd = MethodTypeDesc.of(CD_BYTE_VECTOR, paramTypes);

        code.invokestatic(CD_UTF8_KERNEL, "applyRoundMask" + litCount, mtd);
        code.astore(resultSlot);
    }

    // ========================================================================
    // Emit gateMultiByteN: invokestatic Utf8Kernel.gateMultiByteN(accumulator, classify, finalLit, r0..rN, rl0..rlN)
    // ========================================================================

    private void emitGateMultiByte(CodeBuilder code, int charSpecIdx) {
        int byteLen = charSpecByteLengths[charSpecIdx];

        // Push: accumulator, classify, finalLitVec
        code.aload(FIND_ACCUMULATOR);
        code.aload(FIND_CLASSIFY);
        loadField(code, finalLitVecField(charSpecIdx), CD_BYTE_VECTOR);

        // Push round results: r0..r(byteLen-1)
        for (int r = 0; r < byteLen; r++) {
            code.aload(FIND_R0 + r);
        }

        // Push round literal vecs: roundLitVec_s_0..roundLitVec_s_(byteLen-1)
        for (int r = 0; r < byteLen; r++) {
            loadField(code, roundLitVecField(charSpecIdx, r), CD_BYTE_VECTOR);
        }

        // Method type: (ByteVector * (3 + 2*byteLen)) -> ByteVector
        int paramCount = 3 + 2 * byteLen;
        var paramTypes = new ClassDesc[paramCount];
        for (int i = 0; i < paramCount; i++) {
            paramTypes[i] = CD_BYTE_VECTOR;
        }
        var mtd = MethodTypeDesc.of(CD_BYTE_VECTOR, paramTypes);

        code.invokestatic(CD_UTF8_KERNEL, "gateMultiByte" + byteLen, mtd);
        code.astore(FIND_ACCUMULATOR);
    }

    // ========================================================================
    // Emit decode call (reuse EngineKernel.decode pattern from EngineCodeGen)
    // ========================================================================

    private void emitDecodeCall(CodeBuilder code) {
        code.aload(FIND_MATCH_STORAGE);
        code.aload(FIND_ACCUMULATOR);
        loadField(code, F_LIT_CACHE, ConstantDescs.CD_byte.arrayType());
        loadField(code, F_POS_CACHE, ConstantDescs.CD_int.arrayType());
        code.getstatic(CD_INT_VECTOR, intSpeciesFieldName, CD_VECTOR_SPECIES);
        emitIconst(code, intBatchSize);
        code.iload(FIND_GLOBAL_COUNT);
        code.iload(FIND_FILE_OFFSET);

        var mtd = MethodTypeDesc.of(ConstantDescs.CD_int,
                CD_MATCH_STORAGE, CD_BYTE_VECTOR,
                ConstantDescs.CD_byte.arrayType(),
                ConstantDescs.CD_int.arrayType(),
                CD_VECTOR_SPECIES, ConstantDescs.CD_int,
                ConstantDescs.CD_int, ConstantDescs.CD_int);
        code.invokestatic(CD_ENGINE_KERNEL, "decode", mtd);
        code.istore(FIND_GLOBAL_COUNT);
    }

    // ========================================================================
    // Helper: pre-load next chunk at i + vectorByteSize into FIND_CHUNK
    // ========================================================================

    private void emitPreloadNextChunk(CodeBuilder code) {
        code.getstatic(CD_BYTE_VECTOR, speciesFieldName, CD_VECTOR_SPECIES);
        code.aload(FIND_DATA);
        code.iload(FIND_I);
        emitIconst(code, vectorByteSize);
        code.iadd();
        code.i2l();
        code.invokestatic(CD_BYTE_ORDER, "nativeOrder",
                MethodTypeDesc.of(CD_BYTE_ORDER));
        code.invokestatic(CD_BYTE_VECTOR, "fromMemorySegment",
                MethodTypeDesc.of(CD_BYTE_VECTOR, CD_VECTOR_SPECIES,
                        CD_MEMORY_SEGMENT, ConstantDescs.CD_long, CD_BYTE_ORDER));
        code.astore(FIND_CHUNK);
    }

    // ========================================================================
    // Helper: load chunk from memory segment at offset i+r
    // ========================================================================

    private void emitLoadFromMemorySegmentAtOffset(CodeBuilder code, int offset, int targetSlot) {
        code.getstatic(CD_BYTE_VECTOR, speciesFieldName, CD_VECTOR_SPECIES);
        code.aload(FIND_DATA);
        code.iload(FIND_I);
        emitIconst(code, offset);
        code.iadd();
        code.i2l();
        code.invokestatic(CD_BYTE_ORDER, "nativeOrder",
                MethodTypeDesc.of(CD_BYTE_ORDER));
        code.invokestatic(CD_BYTE_VECTOR, "fromMemorySegment",
                MethodTypeDesc.of(CD_BYTE_VECTOR, CD_VECTOR_SPECIES,
                        CD_MEMORY_SEGMENT, ConstantDescs.CD_long, CD_BYTE_ORDER));
        code.astore(targetSlot);
    }

    // ========================================================================
    // Helper: load chunk from memory segment at i
    // ========================================================================

    private void emitLoadFromMemorySegment(CodeBuilder code, int targetSlot) {
        code.getstatic(CD_BYTE_VECTOR, speciesFieldName, CD_VECTOR_SPECIES);
        code.aload(FIND_DATA);
        code.iload(FIND_I);
        code.i2l();
        code.invokestatic(CD_BYTE_ORDER, "nativeOrder",
                MethodTypeDesc.of(CD_BYTE_ORDER));
        code.invokestatic(CD_BYTE_VECTOR, "fromMemorySegment",
                MethodTypeDesc.of(CD_BYTE_VECTOR, CD_VECTOR_SPECIES,
                        CD_MEMORY_SEGMENT, ConstantDescs.CD_long, CD_BYTE_ORDER));
        code.astore(targetSlot);
    }

    // ========================================================================
    // Helper: ensureSize
    // ========================================================================

    private void emitEnsureSize(CodeBuilder code) {
        code.aload(FIND_MATCH_STORAGE);
        emitIconst(code, vectorByteSize);
        code.iload(FIND_GLOBAL_COUNT);
        code.invokevirtual(CD_MATCH_STORAGE, "ensureSize",
                MethodTypeDesc.of(ConstantDescs.CD_int,
                        ConstantDescs.CD_int, ConstantDescs.CD_int));
        code.pop();
    }

    // ========================================================================
    // Helper: advance fileOffset and i
    // ========================================================================

    private void emitAdvance(CodeBuilder code) {
        // fileOffset += vectorByteSize
        code.iload(FIND_FILE_OFFSET);
        emitIconst(code, vectorByteSize);
        code.iadd();
        code.istore(FIND_FILE_OFFSET);

        // i += vectorByteSize
        code.iload(FIND_I);
        emitIconst(code, vectorByteSize);
        code.iadd();
        code.istore(FIND_I);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void storeField(CodeBuilder code, String name, ClassDesc type, Runnable valueLoader) {
        code.aload(0); // this
        valueLoader.run();
        code.putfield(classDesc, name, type);
    }

    private void loadField(CodeBuilder code, String name, ClassDesc type) {
        code.aload(0); // this
        code.getfield(classDesc, name, type);
    }

    private static void emitIconst(CodeBuilder code, int value) {
        switch (value) {
            case 0 -> code.iconst_0();
            case 1 -> code.iconst_1();
            case 2 -> code.iconst_2();
            case 3 -> code.iconst_3();
            case 4 -> code.iconst_4();
            case 5 -> code.iconst_5();
            default -> {
                if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                    code.bipush(value);
                } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                    code.sipush(value);
                } else {
                    code.ldc(value);
                }
            }
        }
    }
}
