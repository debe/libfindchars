//! CSV quote filter: suppresses structural chars inside quoted regions.
//!
//! Fused single-pass algorithm: scans the accumulator once, maintaining
//! a running XOR toggle. No intermediate marker array needed.

use findchars::vpa::{FilterLiterals, FilterState};

/// CSV quote filter — fused single-pass, zero allocation.
///
/// Instead of three separate passes (build markers → prefix XOR → zero),
/// this processes the accumulator in one pass with a running toggle:
///
/// ```text
/// toggle = carry_in
/// for each byte:
///   if byte == quote_lit: toggle ^= 0xFF (flip inside/outside)
///   elif byte != 0 && toggle != 0: byte = 0 (kill structural inside quotes)
/// carry_out = toggle
/// ```
///
/// This is equivalent to prefix XOR + zeroing but eliminates the intermediate
/// array and reduces from 3 passes to 1.
#[inline(always)]
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

    // Fast path: scan for any quotes or carry. If neither, nothing to do.
    // Use a quick OR-reduction to check if quote_lit appears anywhere.
    if state[0] == 0 {
        let mut has_quote = false;
        // Check in 8-byte chunks for speed
        let mut i = 0;
        while i + 8 <= len {
            let b0 = acc[i] == quote_lit;
            let b1 = acc[i + 1] == quote_lit;
            let b2 = acc[i + 2] == quote_lit;
            let b3 = acc[i + 3] == quote_lit;
            let b4 = acc[i + 4] == quote_lit;
            let b5 = acc[i + 5] == quote_lit;
            let b6 = acc[i + 6] == quote_lit;
            let b7 = acc[i + 7] == quote_lit;
            if b0 | b1 | b2 | b3 | b4 | b5 | b6 | b7 {
                has_quote = true;
                break;
            }
            i += 8;
        }
        if !has_quote {
            while i < len {
                if acc[i] == quote_lit {
                    has_quote = true;
                    break;
                }
                i += 1;
            }
        }
        if !has_quote {
            return;
        }
    }

    // Fused pass: toggle + zero in one scan
    let mut inside = state[0] != 0;

    for i in 0..len {
        let b = acc[i];
        if b == quote_lit {
            inside = !inside;
            // Quote markers stay in output (they're structural)
        } else if b != 0 && inside {
            // Kill structural char inside quoted region
            acc[i] = 0;
        }
    }

    state[0] = if inside { 1 } else { 0 };
}
