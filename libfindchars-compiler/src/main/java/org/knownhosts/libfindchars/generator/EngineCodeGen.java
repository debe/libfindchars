package org.knownhosts.libfindchars.generator;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;
import org.knownhosts.libfindchars.api.EngineKernel;
import org.knownhosts.libfindchars.api.FindEngine;
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
 * Generates a specialized FindEngine implementation using the ClassFile API.
 * <p>
 * The generated class stores all vectors as individual final fields (no arrays in the hot path)
 * and delegates SIMD logic to {@link EngineKernel} static methods via invokestatic.
 * The {@link BytecodeInliner} then transplants the method bodies at the bytecode level,
 * producing flat inlined bytecodes without method calls in the hot path.
 * <p>
 * VectorSpecies and derived constants (vectorByteSize, intSpecies, intBatchSize) are emitted
 * as {@code getstatic} / {@code iconst} rather than instance field loads, enabling C2 to
 * recognize them as compile-time constants and intrinsify Vector API operations.
 */
public final class EngineCodeGen {

    // --- Type descriptors ---
    private static final ClassDesc CD_OBJECT = ConstantDescs.CD_Object;
    private static final ClassDesc CD_FIND_ENGINE = ClassDesc.of("org.knownhosts.libfindchars.api.FindEngine");
    private static final ClassDesc CD_MATCH_VIEW = ClassDesc.of("org.knownhosts.libfindchars.api.MatchView");
    private static final ClassDesc CD_MATCH_STORAGE = ClassDesc.of("org.knownhosts.libfindchars.api.MatchStorage");
    private static final ClassDesc CD_ENGINE_KERNEL = ClassDesc.of("org.knownhosts.libfindchars.api.EngineKernel");
    private static final ClassDesc CD_BYTE_VECTOR = ClassDesc.of("jdk.incubator.vector.ByteVector");
    private static final ClassDesc CD_INT_VECTOR = ClassDesc.of("jdk.incubator.vector.IntVector");
    private static final ClassDesc CD_VECTOR_SPECIES = ClassDesc.of("jdk.incubator.vector.VectorSpecies");
    private static final ClassDesc CD_MEMORY_SEGMENT = ClassDesc.of("java.lang.foreign.MemorySegment");
    private static final ClassDesc CD_VALUE_LAYOUT = ClassDesc.of("java.lang.foreign.ValueLayout");
    private static final ClassDesc CD_BYTE_ORDER = ClassDesc.of("java.nio.ByteOrder");
    private static final ClassDesc CD_BYTE_VECTOR_ARR = CD_BYTE_VECTOR.arrayType();
    private static final ClassDesc CD_BYTE_VECTOR_ARR2 = CD_BYTE_VECTOR_ARR.arrayType();

    // --- Field names ---
    private static final String F_ZERO = "zero";
    private static final String F_LOW_MASK = "lowMask";
    private static final String F_HIGH_MASK = "highMask";
    private static final String F_LIT_CACHE = "literalCacheSparse";
    private static final String F_POS_CACHE = "positionCache";

    private static String lowLutField(int g) { return "lowLUT_" + g; }
    private static String highLutField(int g) { return "highLUT_" + g; }
    private static String litField(int g, int l) { return "lit_" + g + "_" + l; }
    private static String rangeLowerField(int r) { return "rangeLower_" + r; }
    private static String rangeUpperField(int r) { return "rangeUpper_" + r; }
    private static String rangeLitField(int r) { return "rangeLit_" + r; }

    // --- Constructor parameter slots (generated class) ---
    private static final int CTOR_ZERO = 1;
    private static final int CTOR_LOW_MASK = 2;
    private static final int CTOR_HIGH_MASK = 3;
    private static final int CTOR_LOW_LUTS = 4;
    private static final int CTOR_HIGH_LUTS = 5;
    private static final int CTOR_LITERALS = 6;
    private static final int CTOR_RANGE_LOWER = 7;
    private static final int CTOR_RANGE_UPPER = 8;
    private static final int CTOR_RANGE_LIT = 9;

    // --- find() local variable slots ---
    private static final int FIND_THIS = 0;
    private static final int FIND_MAPPED_FILE = 1;
    private static final int FIND_MATCH_STORAGE = 2;
    private static final int FIND_FILE_OFFSET = 3;
    private static final int FIND_GLOBAL_COUNT = 4;
    private static final int FIND_DATA_SIZE = 5;
    private static final int FIND_I = 6;
    private static final int FIND_INPUT_VEC = 7;
    private static final int FIND_ACCUMULATOR = 8;
    private static final int FIND_LAST_CHUNK = 9;

    // --- Code-gen-time constants ---
    private final int groupCount;
    private final int[] literalCounts;
    private final int rangeCount;
    private final ClassDesc classDesc;
    private final String speciesFieldName;
    private final String intSpeciesFieldName;
    private final int vectorByteSize;
    private final int intBatchSize;

    private EngineCodeGen(int groupCount, int[] literalCounts, int rangeCount,
                          String speciesFieldName, String intSpeciesFieldName,
                          int vectorByteSize, int intBatchSize) {
        this.groupCount = groupCount;
        this.literalCounts = literalCounts;
        this.rangeCount = rangeCount;
        this.classDesc = ClassDesc.of("org.knownhosts.libfindchars.generator.CompiledEngine");
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
     * Compile a specialized FindEngine from pre-computed vectors.
     */
    public static FindEngine compile(
            VectorSpecies<Byte> species,
            ByteVector zero,
            ByteVector lowMask,
            ByteVector highMask,
            ByteVector[] lowLUTs,
            ByteVector[] highLUTs,
            ByteVector[][] literals,
            ByteVector[] rangeLower,
            ByteVector[] rangeUpper,
            ByteVector[] rangeLit,
            VectorSpecies<Integer> intSpecies,
            int intBatchSize) {

        int gCount = lowLUTs.length;
        int[] litCounts = new int[gCount];
        for (int g = 0; g < gCount; g++) {
            litCounts[g] = literals[g].length;
        }

        var speciesField = byteSpeciesField(species);
        // IntVector and ByteVector share identical SPECIES_XXX field names for the same bit width
        // (e.g., ByteVector.SPECIES_256 and IntVector.SPECIES_256). This is a coupling to the
        // Vector API's naming convention — if it ever changes, this assumption breaks.
        var intSpeciesField = speciesField;

        var gen = new EngineCodeGen(gCount, litCounts, rangeLower.length,
                speciesField, intSpeciesField,
                species.vectorByteSize(), intBatchSize);
        byte[] classBytes = gen.generateClassBytes();

        // Debug: dump pre/post-inline bytecode when -Dlibfindchars.dumpBytecode=<dir> is set
        var dumpDir = System.getProperty("libfindchars.dumpBytecode");
        if (dumpDir != null) {
            try {
                var dir = java.nio.file.Path.of(dumpDir);
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Files.write(dir.resolve("pre_inline.class"), classBytes);
            } catch (java.io.IOException e) {
                System.err.println("Failed to dump pre-inline bytecode: " + e);
            }
        }

        // Inline @Inline-annotated EngineKernel methods at the bytecode level
        classBytes = BytecodeInliner.inline(classBytes, EngineKernel.class);

        if (dumpDir != null) {
            try {
                java.nio.file.Files.write(java.nio.file.Path.of(dumpDir).resolve("post_inline.class"), classBytes);
            } catch (java.io.IOException e) {
                System.err.println("Failed to dump post-inline bytecode: " + e);
            }
        }

        try {
            var lookup = MethodHandles.lookup();
            var hidden = lookup.defineHiddenClass(classBytes, true);
            var clazz = hidden.lookupClass();

            var ctor = clazz.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return (FindEngine) ctor.newInstance(
                    zero, lowMask, highMask,
                    lowLUTs, highLUTs, literals,
                    rangeLower, rangeUpper, rangeLit);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate compiled engine", e);
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
        field(cb, F_LOW_MASK, CD_BYTE_VECTOR);
        field(cb, F_HIGH_MASK, CD_BYTE_VECTOR);

        // Per-group fields
        for (int g = 0; g < groupCount; g++) {
            field(cb, lowLutField(g), CD_BYTE_VECTOR);
            field(cb, highLutField(g), CD_BYTE_VECTOR);
            for (int l = 0; l < literalCounts[g]; l++) {
                field(cb, litField(g, l), CD_BYTE_VECTOR);
            }
        }

        // Per-range fields
        for (int r = 0; r < rangeCount; r++) {
            field(cb, rangeLowerField(r), CD_BYTE_VECTOR);
            field(cb, rangeUpperField(r), CD_BYTE_VECTOR);
            field(cb, rangeLitField(r), CD_BYTE_VECTOR);
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
                CD_BYTE_VECTOR,         // zero
                CD_BYTE_VECTOR,         // lowMask
                CD_BYTE_VECTOR,         // highMask
                CD_BYTE_VECTOR_ARR,     // lowLUTs
                CD_BYTE_VECTOR_ARR,     // highLUTs
                CD_BYTE_VECTOR_ARR2,    // literals[][]
                CD_BYTE_VECTOR_ARR,     // rangeLower
                CD_BYTE_VECTOR_ARR,     // rangeUpper
                CD_BYTE_VECTOR_ARR);    // rangeLit

        cb.withMethodBody(ConstantDescs.INIT_NAME, ctorDesc, ClassFile.ACC_PUBLIC, code -> {
            // super()
            code.aload(0);
            code.invokespecial(CD_OBJECT, ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void));

            // this.zero = zero
            storeField(code, F_ZERO, CD_BYTE_VECTOR, () -> code.aload(CTOR_ZERO));
            storeField(code, F_LOW_MASK, CD_BYTE_VECTOR, () -> code.aload(CTOR_LOW_MASK));
            storeField(code, F_HIGH_MASK, CD_BYTE_VECTOR, () -> code.aload(CTOR_HIGH_MASK));

            // Per-group: this.lowLUT_g = lowLUTs[g], this.highLUT_g = highLUTs[g],
            //            this.lit_g_l = literals[g][l]
            for (int g = 0; g < groupCount; g++) {
                final int gi = g;
                storeField(code, lowLutField(g), CD_BYTE_VECTOR, () -> {
                    code.aload(CTOR_LOW_LUTS);
                    emitIconst(code, gi);
                    code.aaload();
                });
                storeField(code, highLutField(g), CD_BYTE_VECTOR, () -> {
                    code.aload(CTOR_HIGH_LUTS);
                    emitIconst(code, gi);
                    code.aaload();
                });
                for (int l = 0; l < literalCounts[g]; l++) {
                    final int li = l;
                    storeField(code, litField(g, l), CD_BYTE_VECTOR, () -> {
                        code.aload(CTOR_LITERALS);
                        emitIconst(code, gi);
                        code.aaload(); // literals[g]
                        emitIconst(code, li);
                        code.aaload(); // literals[g][l]
                    });
                }
            }

            // Per-range
            for (int r = 0; r < rangeCount; r++) {
                final int ri = r;
                storeField(code, rangeLowerField(r), CD_BYTE_VECTOR, () -> {
                    code.aload(CTOR_RANGE_LOWER);
                    emitIconst(code, ri);
                    code.aaload();
                });
                storeField(code, rangeUpperField(r), CD_BYTE_VECTOR, () -> {
                    code.aload(CTOR_RANGE_UPPER);
                    emitIconst(code, ri);
                    code.aaload();
                });
                storeField(code, rangeLitField(r), CD_BYTE_VECTOR, () -> {
                    code.aload(CTOR_RANGE_LIT);
                    emitIconst(code, ri);
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

            // int dataSize = (int) mappedFile.byteSize()
            code.aload(FIND_MAPPED_FILE);
            code.invokeinterface(CD_MEMORY_SEGMENT, "byteSize",
                    MethodTypeDesc.of(ConstantDescs.CD_long));
            code.l2i();
            code.istore(FIND_DATA_SIZE);

            // int i = 0
            code.iconst_0();
            code.istore(FIND_I);

            // Main loop
            Label loopStart = code.newLabel();
            Label loopEnd = code.newLabel();

            code.labelBinding(loopStart);

            // if (i >= dataSize - vectorByteSize) goto loopEnd
            code.iload(FIND_I);
            code.iload(FIND_DATA_SIZE);
            emitIconst(code, vectorByteSize);
            code.isub();
            code.if_icmpge(loopEnd);

            // matchStorage.ensureSize(vectorByteSize, globalCount)
            code.aload(FIND_MATCH_STORAGE);
            emitIconst(code, vectorByteSize);
            code.iload(FIND_GLOBAL_COUNT);
            code.invokevirtual(CD_MATCH_STORAGE, "ensureSize",
                    MethodTypeDesc.of(ConstantDescs.CD_int,
                            ConstantDescs.CD_int, ConstantDescs.CD_int));
            code.pop();

            // inputVec = ByteVector.fromMemorySegment(species, mappedFile, (long)i, nativeOrder)
            code.getstatic(CD_BYTE_VECTOR, speciesFieldName, CD_VECTOR_SPECIES);
            code.aload(FIND_MAPPED_FILE);
            code.iload(FIND_I);
            code.i2l();
            code.invokestatic(CD_BYTE_ORDER, "nativeOrder",
                    MethodTypeDesc.of(CD_BYTE_ORDER));
            code.invokestatic(CD_BYTE_VECTOR, "fromMemorySegment",
                    MethodTypeDesc.of(CD_BYTE_VECTOR, CD_VECTOR_SPECIES,
                            CD_MEMORY_SEGMENT, ConstantDescs.CD_long, CD_BYTE_ORDER));
            code.astore(FIND_INPUT_VEC);

            // accumulator = zero
            loadField(code, F_ZERO, CD_BYTE_VECTOR);
            code.astore(FIND_ACCUMULATOR);

            // Emit shuffle groups as invokestatic (inliner will transplant bodies)
            for (int g = 0; g < groupCount; g++) {
                emitShuffleGroupCall(code, g);
            }

            // Emit range ops as invokestatic
            for (int r = 0; r < rangeCount; r++) {
                emitRangeCall(code, r);
            }

            // Emit decode call
            emitDecodeCall(code);

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

            code.goto_(loopStart);
            code.labelBinding(loopEnd);

            // --- Last chunk ---
            // byte[] lastChunkPadded = new byte[vectorByteSize]
            emitIconst(code, vectorByteSize);
            code.newarray(java.lang.classfile.TypeKind.BYTE);
            code.astore(FIND_LAST_CHUNK);

            // MemorySegment.copy(mappedFile, JAVA_BYTE, fileOffset, lastChunkPadded, 0, dataSize - fileOffset)
            code.aload(FIND_MAPPED_FILE);
            code.getstatic(CD_VALUE_LAYOUT, "JAVA_BYTE",
                    ClassDesc.of("java.lang.foreign.ValueLayout$OfByte"));
            code.iload(FIND_FILE_OFFSET);
            code.i2l();
            code.aload(FIND_LAST_CHUNK);
            code.iconst_0();
            code.iload(FIND_DATA_SIZE);
            code.iload(FIND_FILE_OFFSET);
            code.isub();
            code.invokestatic(CD_MEMORY_SEGMENT, "copy",
                    MethodTypeDesc.of(ConstantDescs.CD_void,
                            CD_MEMORY_SEGMENT,
                            CD_VALUE_LAYOUT,
                            ConstantDescs.CD_long,
                            ConstantDescs.CD_Object,
                            ConstantDescs.CD_int,
                            ConstantDescs.CD_int),
                    true);

            // matchStorage.ensureSize(vectorByteSize, globalCount)
            code.aload(FIND_MATCH_STORAGE);
            emitIconst(code, vectorByteSize);
            code.iload(FIND_GLOBAL_COUNT);
            code.invokevirtual(CD_MATCH_STORAGE, "ensureSize",
                    MethodTypeDesc.of(ConstantDescs.CD_int,
                            ConstantDescs.CD_int, ConstantDescs.CD_int));
            code.pop();

            // inputVec = ByteVector.fromArray(species, lastChunkPadded, 0)
            code.getstatic(CD_BYTE_VECTOR, speciesFieldName, CD_VECTOR_SPECIES);
            code.aload(FIND_LAST_CHUNK);
            code.iconst_0();
            code.invokestatic(CD_BYTE_VECTOR, "fromArray",
                    MethodTypeDesc.of(CD_BYTE_VECTOR, CD_VECTOR_SPECIES,
                            ConstantDescs.CD_byte.arrayType(), ConstantDescs.CD_int));
            code.astore(FIND_INPUT_VEC);

            // accumulator = zero
            loadField(code, F_ZERO, CD_BYTE_VECTOR);
            code.astore(FIND_ACCUMULATOR);

            // Same ops as main loop
            for (int g = 0; g < groupCount; g++) {
                emitShuffleGroupCall(code, g);
            }
            for (int r = 0; r < rangeCount; r++) {
                emitRangeCall(code, r);
            }
            emitDecodeCall(code);

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
    // Emit shuffle group: invokestatic EngineKernel.shuffleAndMatchN(...)
    // The BytecodeInliner will transplant the method body at the bytecode level.
    // ========================================================================

    private void emitShuffleGroupCall(CodeBuilder code, int g) {
        int litCount = literalCounts[g];

        // Push args: inputVec, accumulator, lowLUT_g, highLUT_g, lowMask, highMask, lit_g_0..N-1
        code.aload(FIND_INPUT_VEC);
        code.aload(FIND_ACCUMULATOR);
        loadField(code, lowLutField(g), CD_BYTE_VECTOR);
        loadField(code, highLutField(g), CD_BYTE_VECTOR);
        loadField(code, F_LOW_MASK, CD_BYTE_VECTOR);
        loadField(code, F_HIGH_MASK, CD_BYTE_VECTOR);
        for (int l = 0; l < litCount; l++) {
            loadField(code, litField(g, l), CD_BYTE_VECTOR);
        }

        // Build method type: (ByteVector * (6 + litCount)) -> ByteVector
        var paramTypes = new ClassDesc[6 + litCount];
        for (int i = 0; i < paramTypes.length; i++) {
            paramTypes[i] = CD_BYTE_VECTOR;
        }
        var mtd = MethodTypeDesc.of(CD_BYTE_VECTOR, paramTypes);

        code.invokestatic(CD_ENGINE_KERNEL, "shuffleAndMatch" + litCount, mtd);
        code.astore(FIND_ACCUMULATOR);
    }

    // ========================================================================
    // Emit range: invokestatic EngineKernel.rangeMatch(...)
    // ========================================================================

    private void emitRangeCall(CodeBuilder code, int r) {
        // Push args: inputVec, accumulator, rangeLower, rangeUpper, rangeLit
        code.aload(FIND_INPUT_VEC);
        code.aload(FIND_ACCUMULATOR);
        loadField(code, rangeLowerField(r), CD_BYTE_VECTOR);
        loadField(code, rangeUpperField(r), CD_BYTE_VECTOR);
        loadField(code, rangeLitField(r), CD_BYTE_VECTOR);

        var mtd = MethodTypeDesc.of(CD_BYTE_VECTOR,
                CD_BYTE_VECTOR, CD_BYTE_VECTOR, CD_BYTE_VECTOR, CD_BYTE_VECTOR, CD_BYTE_VECTOR);
        code.invokestatic(CD_ENGINE_KERNEL, "rangeMatch", mtd);
        code.astore(FIND_ACCUMULATOR);
    }

    // ========================================================================
    // Emit decode call: globalCount = EngineKernel.decode(matchStorage, accumulator,
    //     literalCacheSparse, positionCache, intSpecies, intBatchSize, globalCount, fileOffset)
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
