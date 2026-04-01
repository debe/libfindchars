/// A named literal with its target byte values.
#[derive(Debug, Clone)]
pub struct ByteLiteral {
    /// Name of this literal (e.g., "whitespace", "comma").
    pub name: String,
    /// Target byte values that this literal matches.
    pub chars: Vec<u8>,
}

impl ByteLiteral {
    pub fn new(name: impl Into<String>, chars: Vec<u8>) -> Self {
        Self {
            name: name.into(),
            chars,
        }
    }
}

/// A group of ASCII literals to be solved together.
#[derive(Debug, Clone)]
pub struct AsciiLiteralGroup {
    /// The literals in this group.
    pub literals: Vec<ByteLiteral>,
}

impl AsciiLiteralGroup {
    pub fn new(literals: Vec<ByteLiteral>) -> Self {
        Self { literals }
    }
}

/// Result of solving a single shuffle group.
///
/// Contains the two 16-entry LUT vectors and the mapping from target bytes
/// to their assigned literal values.
#[derive(Debug, Clone)]
pub struct AsciiFindMask {
    /// Low-nibble lookup table (16 entries).
    pub low_nibble_mask: [u8; 16],
    /// High-nibble lookup table (16 entries).
    pub high_nibble_mask: [u8; 16],
    /// Mapping from target byte to assigned literal value.
    pub literal_map: Vec<(u8, u8)>,
}

impl AsciiFindMask {
    /// Verify correctness: for every target, AND produces the correct literal;
    /// for every non-target, AND does not produce any literal value.
    ///
    /// Note: non-target AND results may be non-zero — the engine uses a secondary
    /// "clean LUT" (vpermb/selectFrom) to map non-literal values to zero at runtime.
    /// The solver only guarantees non-collision, not zero output.
    pub fn verify(&self) -> bool {
        let literal_values: std::collections::HashSet<u8> =
            self.literal_map.iter().map(|&(_, lit)| lit).collect();

        // Check targets produce correct literal
        for &(target, expected_lit) in &self.literal_map {
            let lo = (target & 0x0F) as usize;
            let hi = ((target >> 4) & 0x0F) as usize;
            let result = self.low_nibble_mask[lo] & self.high_nibble_mask[hi];
            if result != expected_lit {
                return false;
            }
        }

        // Check non-targets don't collide with any literal value
        let target_set: std::collections::HashSet<u8> =
            self.literal_map.iter().map(|&(target, _)| target).collect();

        for byte in 0u16..=255 {
            let b = byte as u8;
            if target_set.contains(&b) {
                continue;
            }
            let lo = (b & 0x0F) as usize;
            let hi = ((b >> 4) & 0x0F) as usize;
            let result = self.low_nibble_mask[lo] & self.high_nibble_mask[hi];
            if literal_values.contains(&result) {
                return false;
            }
        }
        true
    }

    /// Verify with vector_byte_size constraint: non-target AND results masked
    /// to `[0, vector_byte_size)` must not collide with any literal.
    pub fn verify_with_mask(&self, vector_byte_size: usize) -> bool {
        let mask = (vector_byte_size - 1) as u8;
        let literal_values: std::collections::HashSet<u8> =
            self.literal_map.iter().map(|&(_, lit)| lit).collect();

        // Check targets
        for &(target, expected_lit) in &self.literal_map {
            let lo = (target & 0x0F) as usize;
            let hi = ((target >> 4) & 0x0F) as usize;
            let result = self.low_nibble_mask[lo] & self.high_nibble_mask[hi];
            if result != expected_lit {
                return false;
            }
        }

        // Check non-targets (masked)
        let target_set: std::collections::HashSet<u8> =
            self.literal_map.iter().map(|&(target, _)| target).collect();

        for byte in 0u16..=255 {
            let b = byte as u8;
            if target_set.contains(&b) {
                continue;
            }
            let lo = (b & 0x0F) as usize;
            let hi = ((b >> 4) & 0x0F) as usize;
            let result = (self.low_nibble_mask[lo] & self.high_nibble_mask[hi]) & mask;
            if literal_values.contains(&result) {
                return false;
            }
        }
        true
    }
}
