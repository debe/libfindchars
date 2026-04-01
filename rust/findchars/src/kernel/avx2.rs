//! AVX2 backend — 256-bit (32 bytes per chunk) SIMD detection.
//!
//! Uses SSSE3 `vpshufb` for nibble-based shuffle lookup and AVX2 for
//! 256-bit operations. The 16-entry LUTs are duplicated across both
//! 128-bit lanes since `vpshufb` operates per-lane.

#[cfg(target_arch = "x86_64")]
use std::arch::x86_64::*;

use crate::engine::{EngineData, InlineFilter, MatchStorage};
use crate::vpa;

/// Vector byte size for AVX2.
const VBS: usize = 32;

/// AVX2 find implementation. Processes 32 bytes per chunk.
///
/// # Safety
/// Caller must ensure AVX2 + SSSE3 are available (checked at engine build time).
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
pub(crate) unsafe fn find_avx2(
    engine: &EngineData,
    data: &[u8],
    storage: &mut MatchStorage,
) -> usize {
    unsafe {
        let len = data.len();
        if len == 0 {
            return 0;
        }

        storage.ensure_capacity(len / 4 + 64);

        let low_mask = _mm256_set1_epi8(0x0F);
        let zero = _mm256_setzero_si256();

        let has_filter = (engine.filter_fn as *const ()) != (vpa::no_op_filter as *const ());
        let mut filter_state: vpa::FilterState = [0i64; 8];
        let mut count = 0usize;
        let mut offset = 0usize;

        while offset + VBS <= len {
            let chunk = _mm256_loadu_si256(data.as_ptr().add(offset) as *const __m256i);
            count = process_chunk_avx2(engine, chunk, low_mask, zero, offset, VBS, has_filter, &mut filter_state, storage, count);
            offset += VBS;
        }

        if offset < len {
            let remaining = len - offset;
            let mut buf = [0u8; VBS];
            buf[..remaining].copy_from_slice(&data[offset..]);
            let chunk = _mm256_loadu_si256(buf.as_ptr() as *const __m256i);
            let prev_count = storage.len();
            count = process_chunk_avx2(engine, chunk, low_mask, zero, offset, remaining, has_filter, &mut filter_state, storage, count);
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

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
unsafe fn process_chunk_avx2(
    engine: &EngineData,
    chunk: __m256i,
    low_mask: __m256i,
    zero: __m256i,
    base_offset: usize,
    chunk_len: usize,
    has_filter: bool,
    filter_state: &mut vpa::FilterState,
    storage: &mut MatchStorage,
    mut count: usize,
) -> usize {
    unsafe {
        let mut accumulator = zero;

        // Apply round 0 groups
        if !engine.round_group_count.is_empty() {
            let r0_start = engine.round_group_start[0];
            let r0_count = engine.round_group_count[0];
            for g in r0_start..r0_start + r0_count {
                let raw = shuffle_avx2(chunk, &engine.low_luts[g], &engine.high_luts[g], low_mask);
                let cleaned = clean_avx2(raw, &engine.group_literals[g], zero);
                accumulator = _mm256_or_si256(accumulator, cleaned);
            }
        }

        // Range operations
        for &(lower, upper, lit) in &engine.ranges {
            let lower_v = _mm256_set1_epi8(lower as i8);
            let upper_v = _mm256_set1_epi8(upper as i8);
            let lit_v = _mm256_set1_epi8(lit as i8);

            let above_lower = _mm256_cmpeq_epi8(_mm256_max_epu8(chunk, lower_v), chunk);
            let below_upper = _mm256_cmpeq_epi8(_mm256_min_epu8(chunk, upper_v), chunk);
            let in_range = _mm256_and_si256(above_lower, below_upper);
            accumulator = _mm256_or_si256(accumulator, _mm256_and_si256(in_range, lit_v));
        }

        // Apply chunk filter — inline SIMD path for known filters
        match engine.inline_filter {
            InlineFilter::CsvQuote { quote_lit } => {
                accumulator = csv_quote_filter_avx2(accumulator, quote_lit, filter_state);
            }
            InlineFilter::None if has_filter => {
                let mut acc_bytes = [0u8; VBS];
                _mm256_storeu_si256(acc_bytes.as_mut_ptr() as *mut __m256i, accumulator);
                (engine.filter_fn)(&mut acc_bytes[..chunk_len], filter_state, &engine.filter_literals, chunk_len);
                accumulator = _mm256_loadu_si256(acc_bytes.as_ptr() as *const __m256i);
            }
            InlineFilter::None => {}
        }

        // Fast rejection
        if _mm256_testz_si256(accumulator, accumulator) != 0 {
            return count;
        }

        // Decode
        let nonzero_mask = _mm256_cmpeq_epi8(accumulator, _mm256_setzero_si256());
        let mut bits = !(_mm256_movemask_epi8(nonzero_mask) as u32);

        let mut acc_bytes = [0u8; VBS];
        _mm256_storeu_si256(acc_bytes.as_mut_ptr() as *mut __m256i, accumulator);

        while bits != 0 {
            let lane = bits.trailing_zeros() as usize;
            storage.push((base_offset + lane) as u32, acc_bytes[lane]);
            count += 1;
            bits &= bits - 1;
        }

        count
    }
}

/// Nibble-based shuffle lookup using vpshufb.
/// LUTs are 16 entries duplicated across both 128-bit lanes.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
unsafe fn shuffle_avx2(
    input: __m256i,
    low_lut: &[u8; 16],
    high_lut: &[u8; 16],
    low_mask: __m256i,
) -> __m256i {
    unsafe {
        let lo_lut = _mm256_broadcastsi128_si256(_mm_loadu_si128(low_lut.as_ptr() as *const __m128i));
        let hi_lut = _mm256_broadcastsi128_si256(_mm_loadu_si128(high_lut.as_ptr() as *const __m128i));

        let lo_nibble = _mm256_and_si256(input, low_mask);
        let lo_result = _mm256_shuffle_epi8(lo_lut, lo_nibble);

        let hi_nibble = _mm256_and_si256(_mm256_srli_epi16(input, 4), low_mask);
        let hi_result = _mm256_shuffle_epi8(hi_lut, hi_nibble);

        _mm256_and_si256(lo_result, hi_result)
    }
}

/// Clean step: compare-and-blend per literal value.
/// Result is non-zero only where raw matches a known literal.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
#[allow(unsafe_op_in_unsafe_fn)]
unsafe fn clean_avx2(raw: __m256i, literal_values: &[u8], zero: __m256i) -> __m256i {
    let mut result = zero;
    for &lit in literal_values {
        let lit_v = _mm256_set1_epi8(lit as i8);
        let mask = _mm256_cmpeq_epi8(raw, lit_v);
        result = _mm256_or_si256(result, _mm256_and_si256(mask, lit_v));
    }
    result
}

// --- Inline CSV quote filter using AVX2 vectorized prefix XOR ---

/// AVX2 prefix XOR (Hillis-Steele, 5 steps for 32 bytes).
///
/// Uses cross-lane byte shift via permute2x128 + alignr.
/// Each step: r ^= shift_right(r, 2^k)
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
#[allow(unsafe_op_in_unsafe_fn)]
unsafe fn prefix_xor_256(v: __m256i) -> __m256i {
    {
        let zero = _mm256_setzero_si256();

        // Step 1: shift right by 1
        let cross = _mm256_permute2x128_si256(zero, v, 0x03);
        let mut r = _mm256_xor_si256(v, _mm256_alignr_epi8(v, cross, 15));

        // Step 2: shift right by 2
        let cross = _mm256_permute2x128_si256(zero, r, 0x03);
        r = _mm256_xor_si256(r, _mm256_alignr_epi8(r, cross, 14));

        // Step 3: shift right by 4
        let cross = _mm256_permute2x128_si256(zero, r, 0x03);
        r = _mm256_xor_si256(r, _mm256_alignr_epi8(r, cross, 12));

        // Step 4: shift right by 8
        let cross = _mm256_permute2x128_si256(zero, r, 0x03);
        r = _mm256_xor_si256(r, _mm256_alignr_epi8(r, cross, 8));

        // Step 5: shift right by 16
        let cross = _mm256_permute2x128_si256(zero, r, 0x03);
        r = _mm256_xor_si256(r, cross);

        r
    }
}

/// Inline CSV quote filter for AVX2.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
unsafe fn csv_quote_filter_avx2(
    accumulator: __m256i,
    quote_lit: u8,
    filter_state: &mut vpa::FilterState,
) -> __m256i {
    unsafe {
        let quote_v = _mm256_set1_epi8(quote_lit as i8);
        let all_ones = _mm256_set1_epi8(-1);
        let zero = _mm256_setzero_si256();

        // 1. Quote positions
        let is_quote = _mm256_cmpeq_epi8(accumulator, quote_v);

        // Fast path: no quotes and no carry
        let quote_bits = _mm256_movemask_epi8(is_quote) as u32;
        if quote_bits == 0 && filter_state[0] == 0 {
            return accumulator;
        }

        // 2. Build quote markers: 0xFF at quote positions
        let quote_markers = _mm256_and_si256(is_quote, all_ones);

        // 3. Prefix XOR
        let mut inside = prefix_xor_256(quote_markers);

        // 4. Apply carry
        if filter_state[0] != 0 {
            inside = _mm256_xor_si256(inside, all_ones);
        }

        // 5. Update carry: extract byte 31 (last byte)
        // movemask gives bit 31 for byte 31's sign bit; inside is 0xFF or 0x00
        let inside_bits = _mm256_movemask_epi8(inside) as u32;
        filter_state[0] = if (inside_bits >> 31) & 1 != 0 { 1 } else { 0 };

        // 6. Kill structural inside quotes
        // structural = nonzero AND not-quote
        let is_nonzero = _mm256_cmpeq_epi8(accumulator, zero);
        let is_nonzero = _mm256_andnot_si256(is_nonzero, all_ones); // invert: 0xFF where nonzero
        let is_structural = _mm256_andnot_si256(is_quote, is_nonzero); // nonzero AND NOT quote
        let is_inside = inside; // 0xFF where inside

        let kill = _mm256_and_si256(is_structural, is_inside);
        _mm256_andnot_si256(kill, accumulator) // acc AND NOT kill
    }
}
