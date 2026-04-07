package org.knownhosts.libfindchars.api;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Default no-op chunk filter. Returns the accumulator unchanged.
 *
 * <p>In BYTECODE_INLINE mode, {@code applyStatic()} is the static dispatch target
 * in the engine template bytecode. When a user provides a custom filter,
 * {@code TemplateTransformer} replaces all {@code invokestatic NoOpChunkFilter.applyStatic()}
 * calls with the user's filter class before inlining. When no filter is configured,
 * {@code filterEnabled == 0} causes the filter call to be dead-code-eliminated.
 *
 * <p>In JIT and AOT modes, the engine calls {@link #apply} via virtual dispatch.
 */
public final class NoOpChunkFilter implements ChunkFilter {

    public static final NoOpChunkFilter INSTANCE = new NoOpChunkFilter();

    private NoOpChunkFilter() {}

    @Inline
    public static ByteVector applyStatic(ByteVector accumulator, ByteVector zero,
                                          VectorSpecies<Byte> species,
                                          long[] state, byte[] scratchpad,
                                          ByteVector[] literals) {
        return accumulator;
    }

    @Override
    public ByteVector apply(ByteVector accumulator, ByteVector zero,
                            VectorSpecies<Byte> species,
                            long[] state, byte[] scratchpad,
                            ByteVector[] literals) {
        return accumulator;
    }
}
