//! ARM NEON backend — 128-bit (16 bytes per chunk) SIMD detection.
//!
//! Uses `vqtbl1q_u8` for 16-byte table lookup (no lane splitting needed),
//! `vceqq_u8` for byte comparison, and `vmaxvq_u8` for fast rejection.
//!
//! Multi-byte UTF-8 detection runs one shuffle round per codepoint byte.
//! Round `r` reads the chunk shifted `r` bytes ahead (`load_shifted_neon`),
//! so a lead byte at lane `j` is gated lane-aligned against its continuation
//! bytes — codepoints straddling chunk boundaries work without special handling.

#[cfg(target_arch = "aarch64")]
use std::arch::aarch64::*;

#[cfg(target_arch = "aarch64")]
use crate::engine::{EngineData, InlineFilter, MatchStorage};
#[cfg(target_arch = "aarch64")]
use crate::utf8;
#[cfg(target_arch = "aarch64")]
use crate::vpa;

/// Vector byte size for NEON.
#[cfg(target_arch = "aarch64")]
const VBS: usize = 16;

/// NEON find implementation. Processes 16 bytes per chunk.
///
/// # Safety
/// Caller must ensure NEON is available (always true on aarch64).
#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
pub(crate) unsafe fn find_neon(
    engine: &EngineData,
    data: &[u8],
    storage: &mut MatchStorage,
) -> usize {
    let len = data.len();
    if len == 0 {
        return 0;
    }

    storage.ensure_capacity(len / 4 + 64);

    unsafe {
        let low_mask = vdupq_n_u8(0x0F);
        let zero = vdupq_n_u8(0);

        let has_filter = (engine.filter_fn as *const ()) != (vpa::no_op_filter as *const ());
        let mut filter_state: vpa::FilterState = [0i64; 8];
        let mut count = 0usize;
        let mut offset = 0usize;

        // Process full 16-byte chunks
        while offset + VBS <= len {
            let chunk = vld1q_u8(data.as_ptr().add(offset));
            count = process_chunk_neon(
                engine,
                data,
                chunk,
                low_mask,
                zero,
                offset,
                VBS,
                has_filter,
                &mut filter_state,
                storage,
                count,
            );
            offset += VBS;
        }

        // Tail: pad with zeros
        if offset < len {
            let remaining = len - offset;
            let mut buf = [0u8; VBS];
            buf[..remaining].copy_from_slice(&data[offset..]);
            let chunk = vld1q_u8(buf.as_ptr());
            let prev_count = storage.len();
            count = process_chunk_neon(
                engine,
                data,
                chunk,
                low_mask,
                zero,
                offset,
                remaining,
                has_filter,
                &mut filter_state,
                storage,
                count,
            );
            // Remove matches beyond valid data range
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

#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
#[inline]
#[allow(clippy::too_many_arguments)]
unsafe fn process_chunk_neon(
    engine: &EngineData,
    data: &[u8],
    chunk: uint8x16_t,
    low_mask: uint8x16_t,
    zero: uint8x16_t,
    base_offset: usize,
    chunk_len: usize,
    has_filter: bool,
    filter_state: &mut vpa::FilterState,
    storage: &mut MatchStorage,
    mut count: usize,
) -> usize {
    unsafe {
        // Round 0: ASCII literals + multi-byte lead bytes.
        let r0 = apply_round_neon(engine, chunk, 0, low_mask, zero);

        let mut accumulator =
            if engine.max_rounds > 1 && vmaxvq_u8(vandq_u8(chunk, vdupq_n_u8(0x80))) != 0 {
                // --- Multi-byte detection ---
                let classify_lut = vld1q_u8(utf8::CLASSIFY_TABLE.as_ptr());
                let classify = classify_neon(chunk, classify_lut);

                // rounds[r] = round-r detection of the chunk shifted r bytes ahead,
                // so a lead byte is gated lane-aligned against its continuations.
                // max_rounds <= 4 (longest UTF-8 sequence), so the array always fits.
                let mut rounds = [zero; 4];
                rounds[0] = r0;
                for (r, slot) in rounds
                    .iter_mut()
                    .enumerate()
                    .take(engine.max_rounds)
                    .skip(1)
                {
                    let shifted = load_shifted_neon(data, base_offset + r);
                    *slot = apply_round_neon(engine, shifted, r, low_mask, zero);
                }

                // gateAscii: keep round-0 results only at ASCII positions.
                let ascii = vceqq_u8(classify, vdupq_n_u8(utf8::CLASSIFY_ASCII));
                let mut acc = vandq_u8(r0, ascii);

                // gate each charspec: classify == byte_len AND every round literal matches.
                for s in 0..engine.charspec_byte_lens.len() {
                    let n = engine.charspec_byte_lens[s];
                    let rl = &engine.charspec_round_lits[s];
                    let mut gate = vceqq_u8(classify, vdupq_n_u8(n as u8));
                    for r in 0..n {
                        gate = vandq_u8(gate, vceqq_u8(rounds[r], vdupq_n_u8(rl[r])));
                    }
                    let final_lit = vdupq_n_u8(engine.charspec_final_lits[s]);
                    acc = vorrq_u8(acc, vandq_u8(final_lit, gate));
                }
                acc
            } else {
                // ASCII-only fast path: round 0 is the result.
                r0
            };

        // Range operations: unsigned compare via max/min
        for &(lower, upper, lit) in &engine.ranges {
            let lower_v = vdupq_n_u8(lower);
            let upper_v = vdupq_n_u8(upper);
            let lit_v = vdupq_n_u8(lit);

            // chunk >= lower: max(chunk, lower) == chunk
            let above_lower = vceqq_u8(vmaxq_u8(chunk, lower_v), chunk);
            // chunk <= upper: min(chunk, upper) == chunk
            let below_upper = vceqq_u8(vminq_u8(chunk, upper_v), chunk);
            let in_range = vandq_u8(above_lower, below_upper);
            accumulator = vorrq_u8(accumulator, vandq_u8(in_range, lit_v));
        }

        // Apply chunk filter — inline SIMD path for known filters
        match engine.inline_filter {
            InlineFilter::CsvQuote { quote_lit } => {
                accumulator = csv_quote_filter_neon(accumulator, quote_lit, filter_state);
            }
            InlineFilter::None if has_filter => {
                let mut acc_bytes = [0u8; VBS];
                vst1q_u8(acc_bytes.as_mut_ptr(), accumulator);
                (engine.filter_fn)(
                    &mut acc_bytes[..chunk_len],
                    filter_state,
                    &engine.filter_literals,
                    chunk_len,
                );
                accumulator = vld1q_u8(acc_bytes.as_ptr());
            }
            InlineFilter::None => {}
        }

        // Fast rejection: vmaxvq_u8 reduces to max across all lanes
        if vmaxvq_u8(accumulator) == 0 {
            return count;
        }

        // Decode: store to array, scan non-zero bytes
        let mut acc_bytes = [0u8; VBS];
        vst1q_u8(acc_bytes.as_mut_ptr(), accumulator);

        #[allow(clippy::needless_range_loop)]
        for i in 0..chunk_len {
            if acc_bytes[i] != 0 {
                storage.push((base_offset + i) as u32, acc_bytes[i]);
                count += 1;
            }
        }

        count
    }
}

/// Apply one detection round's shuffle groups, OR-ing their cleaned results.
#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
#[inline]
unsafe fn apply_round_neon(
    engine: &EngineData,
    input: uint8x16_t,
    round: usize,
    low_mask: uint8x16_t,
    zero: uint8x16_t,
) -> uint8x16_t {
    unsafe {
        if round >= engine.round_group_count.len() {
            return zero;
        }
        let start = engine.round_group_start[round];
        let count = engine.round_group_count[round];
        let mut result = zero;
        for g in start..start + count {
            let raw = shuffle_neon(input, &engine.low_luts[g], &engine.high_luts[g], low_mask);
            let cleaned = clean_neon(raw, &engine.group_literals[g], zero);
            result = vorrq_u8(result, cleaned);
        }
        result
    }
}

/// Classify each byte by UTF-8 role (1=ASCII, 0=continuation, 2/3/4=lead).
#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
#[inline]
unsafe fn classify_neon(chunk: uint8x16_t, classify_lut: uint8x16_t) -> uint8x16_t {
    // High nibble in [0,15] — vqtbl1q_u8 indexes the 16-byte classify table.
    let hi_nibble = vshrq_n_u8(chunk, 4);
    vqtbl1q_u8(classify_lut, hi_nibble)
}

/// Load `VBS` bytes from `data` starting at `pos`, zero-padding past the end.
#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
#[inline]
unsafe fn load_shifted_neon(data: &[u8], pos: usize) -> uint8x16_t {
    unsafe {
        let len = data.len();
        if pos + VBS <= len {
            vld1q_u8(data.as_ptr().add(pos))
        } else {
            let mut buf = [0u8; VBS];
            if pos < len {
                buf[..len - pos].copy_from_slice(&data[pos..]);
            }
            vld1q_u8(buf.as_ptr())
        }
    }
}

/// Nibble-based shuffle lookup using vqtbl1q_u8.
///
/// NEON's `vqtbl1q_u8` is a true 16-byte table lookup: each index byte
/// selects from the 16-byte table, with out-of-range indices (>= 16)
/// producing zero. No lane splitting needed.
#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
#[inline]
unsafe fn shuffle_neon(
    input: uint8x16_t,
    low_lut: &[u8; 16],
    high_lut: &[u8; 16],
    low_mask: uint8x16_t,
) -> uint8x16_t {
    unsafe {
        let lo_lut = vld1q_u8(low_lut.as_ptr());
        let hi_lut = vld1q_u8(high_lut.as_ptr());

        // Low nibble lookup: input & 0x0F → index into low LUT
        let lo_nibble = vandq_u8(input, low_mask);
        let lo_result = vqtbl1q_u8(lo_lut, lo_nibble);

        // High nibble lookup: input >> 4 → index into high LUT
        // vshrq_n_u8 shifts each byte right by 4, result is in [0,15]
        // vqtbl1q_u8 zeros indices >= 16, so no mask needed
        let hi_nibble = vshrq_n_u8(input, 4);
        let hi_result = vqtbl1q_u8(hi_lut, hi_nibble);

        // AND: literal byte for targets, garbage for non-targets
        vandq_u8(lo_result, hi_result)
    }
}

/// Clean step: compare-and-blend per literal value.
/// Result is non-zero only where raw matches a known literal.
#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
#[inline]
unsafe fn clean_neon(raw: uint8x16_t, literal_values: &[u8], zero: uint8x16_t) -> uint8x16_t {
    let mut result = zero;
    for &lit in literal_values {
        let lit_v = vdupq_n_u8(lit);
        let mask = vceqq_u8(raw, lit_v);
        result = vorrq_u8(result, vandq_u8(mask, lit_v));
    }
    result
}

// --- Inline CSV quote filter using NEON vectorized prefix XOR ---

/// NEON prefix XOR (Hillis-Steele, 4 steps for 16 bytes).
///
/// Uses `vextq_u8(zero, v, 16-N)` for cross-lane byte shift right by N.
/// This is a native NEON operation — no lane-crossing workarounds needed.
#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
#[inline]
unsafe fn prefix_xor_128(v: uint8x16_t) -> uint8x16_t {
    let zero = vdupq_n_u8(0);
    // Step 1: shift right by 1, XOR
    let mut r = veorq_u8(v, vextq_u8(zero, v, 15));
    // Step 2: shift right by 2
    r = veorq_u8(r, vextq_u8(zero, r, 14));
    // Step 3: shift right by 4
    r = veorq_u8(r, vextq_u8(zero, r, 12));
    // Step 4: shift right by 8
    r = veorq_u8(r, vextq_u8(zero, r, 8));
    r
}

/// Inline CSV quote filter for NEON.
#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
#[inline]
unsafe fn csv_quote_filter_neon(
    accumulator: uint8x16_t,
    quote_lit: u8,
    filter_state: &mut vpa::FilterState,
) -> uint8x16_t {
    unsafe {
        let quote_v = vdupq_n_u8(quote_lit);
        let all_ones = vdupq_n_u8(0xFF);
        let zero = vdupq_n_u8(0);

        // 1. Quote mask
        let is_quote = vceqq_u8(accumulator, quote_v);

        // Fast path: no quotes and no carry
        if vmaxvq_u8(is_quote) == 0 && filter_state[0] == 0 {
            return accumulator;
        }

        // 2. Quote markers (0xFF at quote positions)
        let quote_markers = vandq_u8(is_quote, all_ones);

        // 3. Prefix XOR
        let mut inside = prefix_xor_128(quote_markers);

        // 4. Apply carry
        if filter_state[0] != 0 {
            inside = veorq_u8(inside, all_ones);
        }

        // 5. Update carry: extract last byte (lane 15)
        filter_state[0] = if vgetq_lane_u8(inside, 15) != 0 { 1 } else { 0 };

        // 6. Kill structural inside quotes
        let is_nonzero = vmvnq_u8(vceqq_u8(accumulator, zero)); // 0xFF where nonzero
        let is_structural = vbicq_u8(is_nonzero, is_quote); // nonzero AND NOT quote
        let kill = vandq_u8(is_structural, inside);
        vbicq_u8(accumulator, kill) // acc AND NOT kill
    }
}
