//! VPA (Visibly Pushdown Automaton) chunk filter framework.
//!
//! Chunk filters transform the detection result between SIMD detection
//! and position decode. The canonical use case is CSV quote handling.

/// Chunk filter trait for transforming detection results per chunk.
///
/// Implementations receive the detection accumulator and may zero out
/// positions that should be excluded from the match output.
pub trait ChunkFilter {
    /// Transform the accumulator between detection and decode.
    ///
    /// - `acc`: mutable detection result (non-zero = match). Zero lanes to suppress.
    /// - `state`: mutable carry state across chunks, reset per `find()` call.
    /// - `scratchpad`: temporary working memory (vector-byte-size bytes).
    /// - `literals`: pre-broadcast literal byte arrays.
    fn apply(
        acc: &mut [u8],
        state: &mut [i64; 8],
        scratchpad: &mut [u8],
        literals: &[&[u8]],
    );
}

/// No-op filter that passes detection results through unchanged.
///
/// Zero-sized type — optimized away entirely by the compiler.
pub struct NoOpFilter;

impl ChunkFilter for NoOpFilter {
    #[inline(always)]
    fn apply(_: &mut [u8], _: &mut [i64; 8], _: &mut [u8], _: &[&[u8]]) {}
}
