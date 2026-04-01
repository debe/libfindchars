//! Scalar fallback backend — reference implementation with no SIMD.
//!
//! Processes one byte at a time. Used for correctness verification and
//! as a fallback on platforms without SIMD support.

use crate::engine::{EngineData, MatchStorage};

/// Scalar find implementation. Processes one byte at a time.
pub(crate) fn find_scalar(engine: &EngineData, data: &[u8], storage: &mut MatchStorage) -> usize {
    // Pre-allocate estimate: ~25% of input might match (generous)
    storage.ensure_capacity(data.len() / 4 + 64);

    let mut count = 0usize;

    for (offset, &byte) in data.iter().enumerate() {
        let mut result = 0u8;

        // Apply each shuffle group
        for g in 0..engine.group_count {
            let lo = (byte & 0x0F) as usize;
            let hi = ((byte >> 4) & 0x0F) as usize;
            let raw = engine.low_luts[g][lo] & engine.high_luts[g][hi];
            // Clean: map non-literal values to zero via clean LUT
            let cleaned = engine.clean_luts[g][raw as usize];
            result |= cleaned;
        }

        // Apply range operations
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
