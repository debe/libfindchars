//! Parallel prefix primitives: XOR (toggle) and SUM (depth).
//!
//! Scalar implementations process one byte at a time.
//! SIMD implementations use the Hillis-Steele pattern in O(log₂ V) steps.

/// Scalar prefix XOR: inclusive scan producing toggle state.
///
/// Input: 0xFF at toggle positions, 0x00 elsewhere.
/// Output: `result[i] = input[0] ^ input[1] ^ ... ^ input[i]`
///
/// For CSV quote detection: 0xFF = inside quotes, 0x00 = outside.
#[inline]
pub fn prefix_xor_scalar(data: &mut [u8]) {
    let mut acc = 0u8;
    for byte in data.iter_mut() {
        acc ^= *byte;
        *byte = acc;
    }
}

/// Scalar prefix XOR with carry-in from previous chunk.
///
/// If `carry` is non-zero, the entire output is inverted (XOR with 0xFF).
#[inline]
pub fn prefix_xor_scalar_with_carry(data: &mut [u8], carry: u8) -> u8 {
    let mut acc = carry;
    for byte in data.iter_mut() {
        acc ^= *byte;
        *byte = acc;
    }
    acc // carry-out: last value
}

/// Scalar prefix sum: inclusive scan producing cumulative depth.
///
/// Input: +1 at open positions, -1 (0xFF) at close positions, 0 elsewhere.
/// Output: `result[i] = input[0] + input[1] + ... + input[i]` (wrapping i8 arithmetic).
#[inline]
pub fn prefix_sum_scalar(data: &mut [u8]) {
    let mut acc = 0i8;
    for byte in data.iter_mut() {
        acc = acc.wrapping_add(*byte as i8);
        *byte = acc as u8;
    }
}

/// Scalar prefix sum with carry-in.
#[inline]
pub fn prefix_sum_scalar_with_carry(data: &mut [u8], carry: i8) -> i8 {
    let mut acc = carry;
    for byte in data.iter_mut() {
        acc = acc.wrapping_add(*byte as i8);
        *byte = acc as u8;
    }
    acc
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_prefix_xor_basic() {
        // [1, 0, 0, 1, 0] → [1, 1, 1, 0, 0] (using 0xFF for 1)
        let mut data = [0xFF, 0x00, 0x00, 0xFF, 0x00];
        prefix_xor_scalar(&mut data);
        assert_eq!(data, [0xFF, 0xFF, 0xFF, 0x00, 0x00]);
    }

    #[test]
    fn test_prefix_xor_all_toggles() {
        let mut data = [0xFF, 0xFF, 0xFF, 0xFF];
        prefix_xor_scalar(&mut data);
        assert_eq!(data, [0xFF, 0x00, 0xFF, 0x00]);
    }

    #[test]
    fn test_prefix_xor_empty() {
        let mut data: [u8; 0] = [];
        prefix_xor_scalar(&mut data);
    }

    #[test]
    fn test_prefix_xor_with_carry() {
        // Carry-in = 0xFF means we start "inside"
        let mut data = [0x00, 0xFF, 0x00];
        let carry_out = prefix_xor_scalar_with_carry(&mut data, 0xFF);
        // start inside → [0xFF, 0x00, 0x00] (flip at 0xFF toggle)
        assert_eq!(data, [0xFF, 0x00, 0x00]);
        assert_eq!(carry_out, 0x00);
    }

    #[test]
    fn test_prefix_sum_basic() {
        let mut data = [1, 0, 1, 0];
        prefix_sum_scalar(&mut data);
        assert_eq!(data, [1, 1, 2, 2]);
    }

    #[test]
    fn test_prefix_sum_with_decrements() {
        // +1, -1, +1, -1 → 1, 0, 1, 0
        let mut data = [1, 0xFF, 1, 0xFF]; // 0xFF = -1 as i8
        prefix_sum_scalar(&mut data);
        assert_eq!(data, [1, 0, 1, 0]);
    }
}
