//! CSV quote filter: suppresses structural chars inside quoted regions.
//!
//! Uses prefix XOR to compute a running quote-toggle state, then zeros
//! non-quote structural characters that fall inside quoted regions.
//!
//! Literal bindings: `literals[0]` = quote literal byte.

use findchars::vpa::prefix::prefix_xor_scalar_with_carry;
use findchars::vpa::{FilterLiterals, FilterState};

/// CSV quote filter function.
///
/// Algorithm:
/// 1. Compare accumulator against quote literal → 0xFF at quote positions
/// 2. Prefix XOR (Hillis-Steele) → toggle state: 0xFF inside quotes
/// 3. Apply cross-chunk carry from `state[0]`
/// 4. Update carry for next chunk
/// 5. Zero structural (non-zero, non-quote) chars inside quotes
pub fn csv_quote_filter(
    acc: &mut [u8],
    state: &mut FilterState,
    literals: &FilterLiterals,
    len: usize,
) {
    if literals.is_empty() || len == 0 {
        return;
    }
    let quote_lit = literals[0];

    // Build quote markers: 0xFF at quote positions, 0x00 elsewhere
    let mut quote_markers = vec![0u8; len];
    let mut has_quotes = false;
    for i in 0..len {
        if acc[i] == quote_lit {
            quote_markers[i] = 0xFF;
            has_quotes = true;
        }
    }

    // Fast path: no quotes and no carry from previous chunk (VPA-010)
    if !has_quotes && state[0] == 0 {
        return;
    }

    // Prefix XOR with carry propagation (VPA-003, VPA-005)
    let carry_in = if state[0] != 0 { 0xFF } else { 0x00 };
    let carry_out = prefix_xor_scalar_with_carry(&mut quote_markers, carry_in);

    // Update carry for next chunk
    state[0] = if carry_out != 0 { 1 } else { 0 };

    // Zero out structural (non-zero, non-quote) chars inside quotes (VPA-006)
    for i in 0..len {
        let inside = quote_markers[i] != 0;
        let is_structural = acc[i] != 0 && acc[i] != quote_lit;
        if inside && is_structural {
            acc[i] = 0;
        }
    }
}
