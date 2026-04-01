//! AVX-512 backend — 512-bit (64 bytes per chunk) SIMD detection.
//!
//! Uses pre-broadcast 512-bit LUTs loaded once per chunk (not per group),
//! AVX-512 VBMI `vpermb` for both shuffle and clean steps (single instruction each),
//! and `vpcompressb` for single-instruction position extraction.

#[cfg(target_arch = "x86_64")]
use std::arch::x86_64::*;

use crate::engine::{EngineData, InlineFilter, MatchStorage};
use crate::vpa;

const VBS: usize = 64;

/// AVX-512 find implementation. Processes 64 bytes per chunk.
///
/// # Safety
/// Caller must ensure AVX-512BW + AVX-512VBMI + AVX-512VBMI2 are available.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx512f,avx512bw,avx512vbmi,avx512vbmi2,pclmulqdq")]
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
#[target_feature(enable = "avx512f,avx512bw,avx512vbmi,avx512vbmi2,pclmulqdq")]
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
    count: usize,
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

        // Apply chunk filter — inline SIMD path for known filters
        match engine.inline_filter {
            InlineFilter::CsvQuote { quote_lit } => {
                accumulator = csv_quote_filter_avx512(
                    accumulator, quote_lit, filter_state,
                );
            }
            InlineFilter::None if has_filter => {
                // Generic scalar callback fallback
                let mut acc_bytes = [0u8; VBS];
                _mm512_storeu_si512(acc_bytes.as_mut_ptr() as *mut _, accumulator);
                (engine.filter_fn)(&mut acc_bytes[..chunk_len], filter_state, &engine.filter_literals, chunk_len);
                accumulator = _mm512_loadu_si512(acc_bytes.as_ptr() as *const _);
            }
            InlineFilter::None => {}
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

// --- Inline CSV quote filter using PCLMULQDQ (carryless multiply) ---
//
// CLMUL computes prefix XOR on a bitmask in a single instruction:
//   PCLMULQDQ(quote_bitmask, 0xFFFFFFFFFFFFFFFF) = prefix XOR
// This replaces the 6-step Hillis-Steele shift-XOR chain.
// Everything stays in the bitmask domain — no byte vectors needed.

/// Inline CSV quote filter using PCLMULQDQ for prefix XOR.
///
/// Algorithm (entirely in bitmask domain):
/// 1. Extract quote positions as 64-bit bitmask (from cmpeq kmask)
/// 2. PCLMULQDQ × all-ones = prefix XOR in 1 instruction
/// 3. Apply carry from previous chunk (XOR with ~0)
/// 4. Kill structural chars inside quotes (bitmask AND)
///
/// Total: ~8 instructions (cmpeq + clmul + carry + test + and + maskz_mov).
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx512f,avx512bw,pclmulqdq")]
#[inline]
unsafe fn csv_quote_filter_avx512(
    accumulator: __m512i,
    quote_lit: u8,
    filter_state: &mut vpa::FilterState,
) -> __m512i {
    unsafe {
        let quote_v = _mm512_set1_epi8(quote_lit as i8);

        // 1. Quote positions as 64-bit bitmask
        let is_quote: u64 = _mm512_cmpeq_epi8_mask(accumulator, quote_v);

        // Fast path: no quotes and no carry
        if is_quote == 0 && filter_state[0] == 0 {
            return accumulator;
        }

        // 2. Prefix XOR via PCLMULQDQ: clmul(quote_mask, 0xFFFF...) = prefix XOR
        let qm = _mm_set_epi64x(0, is_quote as i64);
        let all_ones_128 = _mm_set1_epi8(-1);
        let prefix = _mm_clmulepi64_si128(qm, all_ones_128, 0x00);
        let mut inside_mask: u64 = _mm_extract_epi64(prefix, 0) as u64;

        // 3. Apply carry from previous chunk
        if filter_state[0] != 0 {
            inside_mask = !inside_mask;
        }

        // 4. Update carry: bit 63 = last position's inside state
        filter_state[0] = ((inside_mask >> 63) & 1) as i64;

        // 5. Kill structural chars inside quotes (all in bitmask domain)
        let is_nonzero: u64 = _mm512_test_epi8_mask(accumulator, accumulator);
        let is_structural = is_nonzero & !is_quote;
        let kill = is_structural & inside_mask;

        // Zero killed lanes
        _mm512_maskz_mov_epi8(!kill, accumulator)
    }
}

