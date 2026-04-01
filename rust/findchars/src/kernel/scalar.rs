//! Scalar fallback backend — reference implementation with no SIMD.
//!
//! Processes in logical chunks of 16 bytes to support chunk filters.
//! Within each chunk: detect → filter → decode.

use crate::engine::{EngineData, InlineFilter, MatchStorage};
use crate::utf8;
use crate::vpa;

/// Logical chunk size for scalar processing (matches 128-bit SIMD).
const CHUNK_SIZE: usize = 16;

/// Scalar find implementation.
pub(crate) fn find_scalar(engine: &EngineData, data: &[u8], storage: &mut MatchStorage) -> usize {
    storage.ensure_capacity(data.len() / 4 + 64);
    let mut count = 0usize;
    let len = data.len();
    let has_filter = (engine.filter_fn as *const ()) != (vpa::no_op_filter as *const ());
    let mut filter_state: vpa::FilterState = [0i64; 8];

    // Process in logical chunks for filter support
    let mut chunk_start = 0;
    while chunk_start < len {
        let chunk_end = (chunk_start + CHUNK_SIZE).min(len);
        let chunk_len = chunk_end - chunk_start;

        // Phase 1: Detect into a temporary accumulator buffer
        let mut acc = [0u8; CHUNK_SIZE];
        for i in 0..chunk_len {
            let offset = chunk_start + i;
            let byte = data[offset];

            if engine.max_rounds == 1 {
                acc[i] = apply_round_scalar(engine, byte, 0);
            } else {
                let r0 = apply_round_scalar(engine, byte, 0);
                let classify = utf8::classify_byte(byte);

                if classify == utf8::CLASSIFY_ASCII {
                    if r0 != 0 {
                        acc[i] = r0;
                    }
                } else {
                    for s in 0..engine.charspec_byte_lens.len() {
                        let byte_len = engine.charspec_byte_lens[s];
                        if classify != byte_len as u8 {
                            continue;
                        }
                        let mut all_match = true;
                        for r in 0..byte_len {
                            let round_byte = if r == 0 {
                                byte
                            } else if offset + r < len {
                                data[offset + r]
                            } else {
                                0
                            };
                            let round_result = apply_round_scalar(engine, round_byte, r);
                            if round_result != engine.charspec_round_lits[s][r] {
                                all_match = false;
                                break;
                            }
                        }
                        if all_match {
                            acc[i] = engine.charspec_final_lits[s];
                            break;
                        }
                    }
                }
            }

            // Range operations
            for &(lower, upper, lit) in &engine.ranges {
                if byte >= lower && byte <= upper {
                    acc[i] |= lit;
                }
            }
        }

        // Phase 2: Apply chunk filter
        match engine.inline_filter {
            InlineFilter::CsvQuote { quote_lit } => {
                // Fused single-pass toggle — no callback overhead
                csv_quote_filter_scalar(&mut acc[..chunk_len], quote_lit, &mut filter_state);
            }
            InlineFilter::None if has_filter => {
                (engine.filter_fn)(&mut acc[..chunk_len], &mut filter_state, &engine.filter_literals, chunk_len);
            }
            InlineFilter::None => {}
        }

        // Phase 3: Decode non-zero positions
        for i in 0..chunk_len {
            if acc[i] != 0 {
                storage.push((chunk_start + i) as u32, acc[i]);
                count += 1;
            }
        }

        chunk_start = chunk_end;
    }

    count
}

/// Apply all groups in a single round to one byte, returning the cleaned result.
fn apply_round_scalar(engine: &EngineData, byte: u8, round: usize) -> u8 {
    if round >= engine.round_group_count.len() || engine.round_group_count[round] == 0 {
        return 0;
    }

    let start = engine.round_group_start[round];
    let count = engine.round_group_count[round];
    let mut result = 0u8;

    for g in start..start + count {
        let lo = (byte & 0x0F) as usize;
        let hi = ((byte >> 4) & 0x0F) as usize;
        let raw = engine.low_luts[g][lo] & engine.high_luts[g][hi];
        let cleaned = engine.clean_luts[g][raw as usize];
        result |= cleaned;
    }

    result
}

/// Inline scalar CSV quote filter — fused single-pass toggle.
#[inline(always)]
fn csv_quote_filter_scalar(acc: &mut [u8], quote_lit: u8, state: &mut vpa::FilterState) {
    // Fast path: scan for any quotes
    if state[0] == 0 && !acc.contains(&quote_lit) {
        return;
    }

    let mut inside = state[0] != 0;
    for b in acc.iter_mut() {
        if *b == quote_lit {
            inside = !inside;
        } else if *b != 0 && inside {
            *b = 0;
        }
    }
    state[0] = if inside { 1 } else { 0 };
}
