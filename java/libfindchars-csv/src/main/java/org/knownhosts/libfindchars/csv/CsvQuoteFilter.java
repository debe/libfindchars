package org.knownhosts.libfindchars.csv;

import org.knownhosts.libfindchars.api.ChunkFilter;
import org.knownhosts.libfindchars.api.Inline;
import org.knownhosts.libfindchars.api.VpaKernel;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD chunk filter for CSV quote-aware parsing — fully vectorized.
 *
 * <p>Algorithm (all in ByteVector domain, no scalar bitmask escapes):
 * <ol>
 *   <li>Compare accumulator against quote literal → 0xFF at quote positions</li>
 *   <li>{@link VpaKernel#prefixXor} → Hillis-Steele parallel prefix toggle
 *       producing 0xFF inside quoted regions, 0x00 outside</li>
 *   <li>XOR with cross-chunk carry broadcast (state[0]) to handle quotes
 *       spanning chunk boundaries</li>
 *   <li>Update carry from last lane of the prefix XOR result</li>
 *   <li>Blend structural characters inside quotes to zero — single vector op</li>
 * </ol>
 *
 * <p>Literal binding order: {@code literals[0]}=quote.
 */
public final class CsvQuoteFilter implements ChunkFilter {

    public static final CsvQuoteFilter INSTANCE = new CsvQuoteFilter();

    private CsvQuoteFilter() {}

    @Inline
    public static ByteVector applyStatic(ByteVector accumulator, ByteVector zero,
                                          VectorSpecies<Byte> species,
                                          long[] state, byte[] scratchpad,
                                          ByteVector[] literals) {
        var quoteLit = literals[0];

        // Quote markers: 0xFF at quote positions, 0x00 elsewhere
        var quoteMask = accumulator.compare(VectorOperators.EQ, quoteLit);
        var quoteMarkers = (ByteVector) quoteMask.toVector();

        // Fast path: no quotes in this chunk and no carry from previous chunk
        if (!quoteMask.anyTrue() && state[0] == 0) {
            return accumulator;
        }

        // Parallel prefix XOR in vector domain (Hillis-Steele, log₂(V) steps)
        var insideQuotes = VpaKernel.prefixXor(quoteMarkers, zero, species);

        // Apply cross-chunk carry: if previous chunk ended inside quotes,
        // flip the entire mask (XOR with all-ones)
        if (state[0] != 0) {
            insideQuotes = insideQuotes.not();
        }

        // Update carry state from last lane for next chunk
        state[0] = (insideQuotes.lane(species.length() - 1) & 0xFF) != 0 ? 1 : 0;

        // Kill mask: structural (non-zero, non-quote) lanes that fall inside quotes
        var isStructural = accumulator.compare(VectorOperators.NE, 0)
                .and(accumulator.compare(VectorOperators.NE, quoteLit));
        var isInsideQuote = insideQuotes.compare(VectorOperators.NE, 0);
        var killMask = isStructural.and(isInsideQuote);

        // Zero out killed lanes — single vector blend
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
