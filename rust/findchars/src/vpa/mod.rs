//! VPA (Visibly Pushdown Automaton) chunk filter framework.
//!
//! Chunk filters transform the detection result between SIMD detection
//! and position decode. The canonical use case is CSV quote handling.

pub mod prefix;

/// Filter state: mutable carry across chunks within a single `find()` call.
/// Reset to zero at the start of each `find()`.
pub type FilterState = [i64; 8];

/// Filter literal bindings: pre-computed literal byte values for the filter.
pub type FilterLiterals = Vec<u8>;

/// Filter function signature.
///
/// - `acc`: mutable detection accumulator (non-zero = match). Zero lanes to suppress.
/// - `state`: mutable carry state across chunks (reset per `find()` call).
/// - `literals`: filter literal byte values.
/// - `chunk_len`: number of valid bytes in this chunk.
///
/// Returns: nothing (modifies `acc` in place).
pub type FilterFn = fn(&mut [u8], &mut FilterState, &FilterLiterals, usize);

/// No-op filter: passes detection results through unchanged.
pub fn no_op_filter(_acc: &mut [u8], _state: &mut FilterState, _literals: &FilterLiterals, _len: usize) {
    // intentionally empty
}
