//! AVX-512 backend — 512-bit (64 bytes per chunk) SIMD detection.
//!
//! Uses AVX-512 VBMI `vpermb` for full 64-byte shuffle (no lane splitting),
//! AVX-512BW for byte-level operations, and `vpcompressb` for single-instruction
//! position extraction.

#[cfg(target_arch = "x86_64")]
use std::arch::x86_64::*;

use crate::engine::{EngineData, MatchStorage};
use crate::vpa;

const VBS: usize = 64;

/// AVX-512 find implementation. Processes 64 bytes per chunk.
///
/// # Safety
/// Caller must ensure AVX-512BW + AVX-512VBMI are available.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx512f,avx512bw,avx512vbmi")]
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

        storage.ensure_capacity(len / 4 + 64);

        let low_mask = _mm512_set1_epi8(0x0F);
        let zero = _mm512_setzero_si512();

        let has_filter = (engine.filter_fn as *const ()) != (vpa::no_op_filter as *const ());
        let mut filter_state: vpa::FilterState = [0i64; 8];
        let mut count = 0usize;
        let mut offset = 0usize;

        while offset + VBS <= len {
            let chunk = _mm512_loadu_si512(data.as_ptr().add(offset) as *const _);
            count = process_chunk_avx512(
                engine, chunk, low_mask, zero, offset, VBS,
                has_filter, &mut filter_state, storage, count,
            );
            offset += VBS;
        }

        // Tail: pad with zeros
        if offset < len {
            let remaining = len - offset;
            let mut buf = [0u8; VBS];
            buf[..remaining].copy_from_slice(&data[offset..]);
            let chunk = _mm512_loadu_si512(buf.as_ptr() as *const _);
            let prev_count = storage.len();
            count = process_chunk_avx512(
                engine, chunk, low_mask, zero, offset, remaining,
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

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx512f,avx512bw,avx512vbmi")]
#[inline]
unsafe fn process_chunk_avx512(
    engine: &EngineData,
    chunk: __m512i,
    low_mask: __m512i,
    zero: __m512i,
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
                let raw = shuffle_avx512(chunk, &engine.low_luts[g], &engine.high_luts[g], low_mask);
                let cleaned = clean_avx512(raw, &engine.group_literals[g], zero);
                accumulator = _mm512_or_si512(accumulator, cleaned);
            }
        }

        // Range operations
        for &(lower, upper, lit) in &engine.ranges {
            let lower_v = _mm512_set1_epi8(lower as i8);
            let upper_v = _mm512_set1_epi8(upper as i8);
            let lit_v = _mm512_set1_epi8(lit as i8);

            let above_lower = _mm512_cmpeq_epi8_mask(_mm512_max_epu8(chunk, lower_v), chunk);
            let below_upper = _mm512_cmpeq_epi8_mask(_mm512_min_epu8(chunk, upper_v), chunk);
            let in_range = above_lower & below_upper;
            accumulator = _mm512_or_si512(accumulator, _mm512_maskz_mov_epi8(in_range, lit_v));
        }

        // Apply chunk filter (between detection and decode)
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

        // Decode using vpcompressb
        count = decode_avx512(accumulator, nonzero, base_offset, storage, count);

        count
    }
}

/// Full 64-byte shuffle via vpermb (AVX-512 VBMI).
///
/// Unlike AVX2's vpshufb, vpermb uses the full byte value (mod 64) as index
/// into the 64-byte source vector. The 16-entry LUT is replicated 4x to fill
/// 64 bytes. We mask the input to low nibble before shuffling.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx512f,avx512bw,avx512vbmi")]
#[inline]
unsafe fn shuffle_avx512(
    input: __m512i,
    low_lut: &[u8; 16],
    high_lut: &[u8; 16],
    low_mask: __m512i,
) -> __m512i {
    unsafe {
        // Replicate 16-byte LUT 4x to fill 512-bit vector
        let lo_lut = broadcast_lut_512(low_lut);
        let hi_lut = broadcast_lut_512(high_lut);

        // Low nibble lookup: vpermb uses full byte as index (mod 64)
        // Since LUT is replicated 4x and we mask to [0,15], indices are correct
        let lo_nibble = _mm512_and_si512(input, low_mask);
        let lo_result = _mm512_permutexvar_epi8(lo_nibble, lo_lut);

        // High nibble lookup
        let hi_nibble = _mm512_and_si512(_mm512_srli_epi16(input, 4), low_mask);
        let hi_result = _mm512_permutexvar_epi8(hi_nibble, hi_lut);

        _mm512_and_si512(lo_result, hi_result)
    }
}

/// Clean step using compare-and-blend (same approach as AVX2).
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx512f,avx512bw")]
#[inline]
unsafe fn clean_avx512(raw: __m512i, literal_values: &[u8], zero: __m512i) -> __m512i {
    unsafe {
        let mut result = zero;
        for &lit in literal_values {
            let lit_v = _mm512_set1_epi8(lit as i8);
            let mask = _mm512_cmpeq_epi8_mask(raw, lit_v);
            result = _mm512_or_si512(result, _mm512_maskz_mov_epi8(mask, lit_v));
        }
        result
    }
}

/// Broadcast 16-byte LUT to 512-bit vector (replicated 4x).
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx512f")]
#[inline]
unsafe fn broadcast_lut_512(lut: &[u8; 16]) -> __m512i {
    unsafe {
        let lo = _mm_loadu_si128(lut.as_ptr() as *const __m128i);
        let v256 = _mm256_broadcastsi128_si256(lo);
        // Broadcast 256→512 by inserting into both halves
        let mut v512 = _mm512_castsi256_si512(v256);
        v512 = _mm512_inserti64x4(v512, v256, 1);
        v512
    }
}

/// Decode using vpcompressb: single-instruction extraction of non-zero bytes.
///
/// vpcompressb packs the non-zero bytes contiguously. We then extract their
/// literal values and positions from the mask bits.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx512f,avx512bw,avx512vbmi2")]
#[inline]
unsafe fn decode_avx512(
    accumulator: __m512i,
    nonzero_mask: u64,
    base_offset: usize,
    storage: &mut MatchStorage,
    mut count: usize,
) -> usize {
    unsafe {
        // Compress non-zero bytes into contiguous positions
        let compressed = _mm512_maskz_compress_epi8(nonzero_mask, accumulator);
        let match_count = nonzero_mask.count_ones() as usize;

        // Store compressed literals
        let mut lit_buf = [0u8; VBS];
        _mm512_storeu_si512(lit_buf.as_mut_ptr() as *mut _, compressed);

        // Extract positions from mask bits
        let mut bits = nonzero_mask;
        for i in 0..match_count {
            let lane = bits.trailing_zeros() as usize;
            storage.push((base_offset + lane) as u32, lit_buf[i]);
            count += 1;
            bits &= bits - 1;
        }

        count
    }
}
