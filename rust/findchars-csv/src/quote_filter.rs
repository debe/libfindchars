//! CSV quote filter: suppresses structural chars inside quoted regions.
//!
//! Uses prefix XOR to compute a running quote-toggle state, then zeros
//! non-quote structural characters that fall inside quoted regions.
//!
//! Literal bindings: `literals[0]` = quote literal byte.

use findchars::vpa::{FilterLiterals, FilterState};

/// Maximum chunk size (AVX-512 = 64 bytes). Stack-allocated, no heap.
const MAX_CHUNK: usize = 64;

/// CSV quote filter function.
///
/// Algorithm:
/// 1. Compare accumulator against quote literal → 0xFF at quote positions
/// 2. Prefix XOR scan → toggle state: 0xFF inside quotes
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

    // Build quote markers on the stack — no heap allocation
    let mut markers = [0u8; MAX_CHUNK];
    let mut has_quotes = false;

    // Fused scan: build markers and check for quotes in one pass
    for i in 0..len {
        if acc[i] == quote_lit {
            markers[i] = 0xFF;
            has_quotes = true;
        }
    }

    // Fast path: no quotes and no carry from previous chunk (VPA-010)
    if !has_quotes && state[0] == 0 {
        return;
    }

    // Prefix XOR with carry propagation (VPA-003, VPA-005)
    // Inline the scan to avoid function call overhead
    let mut toggle = if state[0] != 0 { 0xFFu8 } else { 0x00u8 };
    for i in 0..len {
        toggle ^= markers[i];
        markers[i] = toggle;
    }

    // Update carry for next chunk
    state[0] = if toggle != 0 { 1 } else { 0 };

    // Zero out structural (non-zero, non-quote) chars inside quotes (VPA-006)
    // Branchless: acc[i] &= !(inside & structural)
    for i in 0..len {
        let inside = markers[i]; // 0xFF or 0x00
        let is_structural = if acc[i] != 0 && acc[i] != quote_lit { 0xFF } else { 0x00 };
        // Zero the byte if both inside AND structural
        acc[i] &= !(inside & is_structural);
    }
}
