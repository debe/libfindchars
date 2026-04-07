package org.knownhosts.libfindchars.api;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Functional interface for chunk filters used in VPA processing.
 *
 * <p>Implementations run between SIMD detection and position decode, transforming
 * the accumulator ByteVector per chunk to zero out lanes that should not be
 * emitted as matches.
 *
 * <p><b>Implementation contract:</b> Classes implementing this interface must provide:
 * <ol>
 *   <li>An instance {@link #apply} method (used in JIT and AOT compilation modes).</li>
 *   <li>An {@code @Inline public static ByteVector applyStatic(...)} method with the
 *       same signature (used by the bytecode inliner in BYTECODE_INLINE mode).</li>
 *   <li>A {@code public static final INSTANCE} singleton field.</li>
 * </ol>
 *
 * <p>In BYTECODE_INLINE mode, the engine inlines the static method at build time via
 * {@code BytecodeInliner}, eliminating virtual dispatch. In JIT and AOT modes, the
 * engine calls {@link #apply} via virtual dispatch, which C2 or Graal can devirtualize.
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

    ByteVector apply(ByteVector accumulator, ByteVector zero,
                     VectorSpecies<Byte> species,
                     long[] state, byte[] scratchpad,
                     ByteVector[] literals);
}
