//! AVX-512 backend — 512-bit (64 bytes per chunk) SIMD detection.
//!
//! Uses pre-broadcast 512-bit LUTs loaded once per chunk (not per group),
//! AVX-512 VBMI `vpermb` for both shuffle and clean steps (single instruction each),
//! and `vpcompressb` for single-instruction position extraction.

#[cfg(target_arch = "x86_64")]
use std::arch::x86_64::*;

use crate::engine::{EngineData, MatchStorage};
use crate::vpa;

const VBS: usize = 64;

/// AVX-512 find implementation. Processes 64 bytes per chunk.
///
/// # Safety
/// Caller must ensure AVX-512BW + AVX-512VBMI + AVX-512VBMI2 are available.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx512f,avx512bw,avx512vbmi,avx512vbmi2")]
pub(crate) unsafe fn find_avx512(
    engine: &EngineData,
    data: &[u8],
    storage: &mut MatchStorage,
) -> usize {
    unsafe {
        let len = data.len();
        if len == 0 {
            return 0;
        }

        // Pre-size storage for worst case: every byte matches
        storage.ensure_capacity(len + 64);

        let low_mask = _mm512_set1_epi8(0x0F);
        let has_filter = (engine.filter_fn as *const ()) != (vpa::no_op_filter as *const ());
        let mut filter_state: vpa::FilterState = [0i64; 8];
        let mut count = 0usize;
        let mut offset = 0usize;

        // Main loop: full 64-byte chunks
        while offset + VBS <= len {
            let chunk = _mm512_loadu_si512(data.as_ptr().add(offset) as *const _);
            count = process_chunk(
                engine, chunk, low_mask, offset, VBS,
                has_filter, &mut filter_state, storage, count,
            );
            offset += VBS;
        }

        // Tail: zero-padded
        if offset < len {
            let remaining = len - offset;
            let mut buf = [0u8; VBS];
            buf[..remaining].copy_from_slice(&data[offset..]);
            let chunk = _mm512_loadu_si512(buf.as_ptr() as *const _);
            let prev_count = storage.len();
            count = process_chunk(
                engine, chunk, low_mask, offset, remaining,
                has_filter, &mut filter_state, storage, count,
            );
            let valid_end = len as u32;
            while storage.len() > prev_count && *storage.positions.last().unwrap() >= valid_end {
                storage.positions.pop();
                storage.literals.pop();
                count -= 1;
            }
        }

        count
    }
}

/// Process one 64-byte chunk: detect → filter → decode.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx512f,avx512bw,avx512vbmi,avx512vbmi2")]
#[inline]
unsafe fn process_chunk(
    engine: &EngineData,
    chunk: __m512i,
    low_mask: __m512i,
    base_offset: usize,
    chunk_len: usize,
    has_filter: bool,
    filter_state: &mut vpa::FilterState,
    storage: &mut MatchStorage,
    mut count: usize,
) -> usize {
    unsafe {
        let mut accumulator = _mm512_setzero_si512();

        // Apply round 0 groups using pre-broadcast LUTs + vpermb clean
        if !engine.round_group_count.is_empty() {
            let r0_start = engine.round_group_start[0];
            let r0_count = engine.round_group_count[0];
            for g in r0_start..r0_start + r0_count {
                // Load pre-broadcast 512-bit LUTs (single load each, no broadcast)
                let lo_lut = _mm512_loadu_si512(engine.low_luts_512[g].as_ptr() as *const _);
                let hi_lut = _mm512_loadu_si512(engine.high_luts_512[g].as_ptr() as *const _);
                let clean_lut = _mm512_loadu_si512(engine.clean_luts_512[g].as_ptr() as *const _);

                // Shuffle: nibble split → vpermb → AND
                let lo_nibble = _mm512_and_si512(chunk, low_mask);
                let lo_result = _mm512_permutexvar_epi8(lo_nibble, lo_lut);
                let hi_nibble = _mm512_and_si512(_mm512_srli_epi16(chunk, 4), low_mask);
                let hi_result = _mm512_permutexvar_epi8(hi_nibble, hi_lut);
                let raw = _mm512_and_si512(lo_result, hi_result);

                // Clean: single vpermb maps non-literal values to zero
                let cleaned = _mm512_permutexvar_epi8(raw, clean_lut);

                accumulator = _mm512_or_si512(accumulator, cleaned);
            }
        }

        // Range operations using pre-broadcast vectors
        for (i, &(_, _, _)) in engine.ranges.iter().enumerate() {
            let lower_v = _mm512_loadu_si512(engine.ranges_512[i].0.as_ptr() as *const _);
            let upper_v = _mm512_loadu_si512(engine.ranges_512[i].1.as_ptr() as *const _);
            let lit_v = _mm512_loadu_si512(engine.ranges_512[i].2.as_ptr() as *const _);

            let above_lower = _mm512_cmpeq_epi8_mask(_mm512_max_epu8(chunk, lower_v), chunk);
            let below_upper = _mm512_cmpeq_epi8_mask(_mm512_min_epu8(chunk, upper_v), chunk);
            let in_range = above_lower & below_upper;
            accumulator = _mm512_or_si512(accumulator, _mm512_maskz_mov_epi8(in_range, lit_v));
        }

        // Apply chunk filter
        if has_filter {
            let mut acc_bytes = [0u8; VBS];
            _mm512_storeu_si512(acc_bytes.as_mut_ptr() as *mut _, accumulator);
            (engine.filter_fn)(&mut acc_bytes[..chunk_len], filter_state, &engine.filter_literals, chunk_len);
            accumulator = _mm512_loadu_si512(acc_bytes.as_ptr() as *const _);
        }

        // Fast rejection
        let nonzero = _mm512_test_epi8_mask(accumulator, accumulator);
        if nonzero == 0 {
            return count;
        }

        // Decode: vpcompressb + bulk write
        let compressed = _mm512_maskz_compress_epi8(nonzero, accumulator);
        let match_count = nonzero.count_ones() as usize;

        // Bulk store compressed literals directly into storage
        let lit_start = storage.literals.len();
        let pos_start = storage.positions.len();

        // Extend vecs by match_count (no per-element bounds check)
        storage.literals.reserve(match_count);
        storage.positions.reserve(match_count);

        // Write compressed literals in bulk
        let lit_ptr = storage.literals.as_mut_ptr().add(lit_start);
        _mm512_storeu_si512(lit_ptr as *mut _, compressed);
        storage.literals.set_len(lit_start + match_count);

        // Write positions from bitmask
        let pos_ptr = storage.positions.as_mut_ptr().add(pos_start);
        let mut bits = nonzero;
        let mut i = 0;
        while bits != 0 {
            let lane = bits.trailing_zeros() as u32;
            *pos_ptr.add(i) = base_offset as u32 + lane;
            i += 1;
            bits &= bits - 1;
        }
        storage.positions.set_len(pos_start + match_count);

        count + match_count
    }
}
