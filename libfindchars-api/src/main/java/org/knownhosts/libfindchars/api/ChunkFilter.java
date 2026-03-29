package org.knownhosts.libfindchars.api;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Marker interface for chunk filters used in VPA processing.
 *
 * <p>Implementations run between SIMD detection and position decode, transforming
 * the accumulator ByteVector per chunk to zero out lanes that should not be
 * emitted as matches.
 *
 * <p><b>Implementation contract:</b> Classes implementing this marker must provide
 * an {@code @Inline public static ByteVector apply(...)} method with the following
 * signature:
 *
 * <pre>{@code
 * @Inline
 * public static ByteVector apply(ByteVector accumulator, ByteVector zero,
 *                                 VectorSpecies<Byte> species,
 *                                 long[] state, byte[] scratchpad,
 *                                 ByteVector[] literals)
 * }</pre>
 *
 * <p>The engine inlines this static method at build time via {@code BytecodeInliner},
 * eliminating virtual dispatch. The marker interface provides compile-time type safety
 * in the builder API ({@code chunkFilter(Class<? extends ChunkFilter>, ...)}).
 *
 * <p>The engine manages all working memory:
 * <ul>
 *   <li>{@code state} — mutable {@code long[8]}, reset per {@code find()} call.
 *       Use for cross-chunk carry (quote toggle, depth counter, accumulators).</li>
 *   <li>{@code scratchpad} — mutable {@code byte[vectorByteSize]}, engine-owned.
 *       Use for {@code intoArray}/{@code fromArray} round-trips without allocation.</li>
 *   <li>{@code literals} — immutable {@code ByteVector[]}, pre-broadcast from
 *       builder literal bindings. Indexed by binding order.</li>
 * </ul>
 *
 * @see VpaKernel for reusable vector primitives (prefixXor, prefixSum)
 * @see NoOpChunkFilter for the default no-op implementation
 */
public interface ChunkFilter {
}
