//! Scalar fallback backend — reference implementation with no SIMD.

use crate::engine::{EngineData, MatchStorage};
use crate::utf8;

/// Scalar find implementation. Processes one byte at a time.
pub(crate) fn find_scalar(engine: &EngineData, data: &[u8], storage: &mut MatchStorage) -> usize {
    storage.ensure_capacity(data.len() / 4 + 64);
    let mut count = 0usize;
    let len = data.len();

    for offset in 0..len {
        let byte = data[offset];
        let mut result = 0u8;

        if engine.max_rounds == 1 {
            // ASCII-only fast path: single round, no gating
            result = apply_round_scalar(engine, byte, 0);
        } else {
            // Multi-round: apply round 0, then gate with classification
            let r0 = apply_round_scalar(engine, byte, 0);
            let classify = utf8::classify_byte(byte);

            if classify == utf8::CLASSIFY_ASCII {
                // ASCII position: r0 result is valid
                if r0 != 0 {
                    result = r0;
                }
            } else {
                // Multi-byte lead position: gate each charspec
                for s in 0..engine.charspec_byte_lens.len() {
                    let byte_len = engine.charspec_byte_lens[s];
                    if classify != byte_len as u8 {
                        continue;
                    }
                    // Check all rounds match expected literals
                    let mut all_match = true;
                    for r in 0..byte_len {
                        let round_byte = if r == 0 {
                            byte
                        } else if offset + r < len {
                            data[offset + r]
                        } else {
                            0 // past end
                        };
                        let round_result = apply_round_scalar(engine, round_byte, r);
                        if round_result != engine.charspec_round_lits[s][r] {
                            all_match = false;
                            break;
                        }
                    }
                    if all_match {
                        result = engine.charspec_final_lits[s];
                        break;
                    }
                }
            }
        }

        // Range operations (always applied)
        for &(lower, upper, lit) in &engine.ranges {
            if byte >= lower && byte <= upper {
                result |= lit;
            }
        }

        if result != 0 {
            storage.push(offset as u32, result);
            count += 1;
        }
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
