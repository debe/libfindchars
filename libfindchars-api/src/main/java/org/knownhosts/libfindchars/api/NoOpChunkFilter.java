package org.knownhosts.libfindchars.api;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Default no-op chunk filter. Returns the accumulator unchanged.
 *
 * <p>Used as the static dispatch target in the engine template bytecode.
 * When a user provides a custom filter, {@code TemplateTransformer.rewriteFilterOwner()}
 * replaces all {@code invokestatic NoOpChunkFilter.apply()} calls with the
 * user's filter class before inlining. When no filter is configured,
 * {@code filterEnabled == 0} causes the filter call to be dead-code-eliminated.
 */
public final class NoOpChunkFilter implements ChunkFilter {

    private NoOpChunkFilter() {}

    @Inline
    public static ByteVector apply(ByteVector accumulator, ByteVector zero,
                                    VectorSpecies<Byte> species,
                                    long[] state, byte[] scratchpad,
                                    ByteVector[] literals) {
        return accumulator;
    }
}
