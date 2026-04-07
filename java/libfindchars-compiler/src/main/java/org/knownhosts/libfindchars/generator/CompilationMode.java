package org.knownhosts.libfindchars.generator;

/**
 * Controls how the engine template is compiled into a ready-to-use
 * {@link org.knownhosts.libfindchars.api.FindEngine}.
 */
public enum CompilationMode {

    /**
     * Full bytecode specialization: constant folding, dead code elimination,
     * {@code @Inline} method inlining via {@code java.lang.classfile}, loaded
     * as a hidden class via {@code defineHiddenClass}. Maximum performance.
     * <b>HotSpot only</b> — requires JDK ClassFile API at runtime.
     */
    BYTECODE_INLINE,

    /**
     * Direct instantiation of {@code Utf8EngineTemplate}. No bytecode
     * manipulation at runtime. Relies on C2 JIT for optimization.
     * Works on any standard JVM.
     */
    JIT,

    /**
     * Direct instantiation of {@code Utf8EngineTemplate}. No bytecode
     * manipulation, no hidden classes, no runtime reflection beyond
     * engine construction. Compatible with GraalVM Native Image and
     * other AOT compilers.
     *
     * <p>When using AOT mode, consider specifying an explicit
     * {@code VectorSpecies} (e.g., {@code ByteVector.SPECIES_256} or
     * {@code SPECIES_512}) rather than {@code SPECIES_PREFERRED}, which
     * is evaluated at native-image build time and may not match the
     * deployment target.
     *
     * <p>For chunk filters, prefer the instance-based
     * {@code chunkFilter(ChunkFilter, String...)} overload to avoid
     * the reflection that the class-based overload uses to resolve
     * the {@code INSTANCE} field (which requires native-image
     * reflection configuration).
     */
    AOT
}
