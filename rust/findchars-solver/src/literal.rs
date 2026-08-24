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
    /// Mapping from literal name to assigned literal value.
    pub name_literal_map: std::collections::HashMap<String, u8>,
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
            if self.and_at(target) != expected_lit {
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
            if literal_values.contains(&self.and_at(b)) {
                return false;
            }
        }
        true
    }

    /// Verify with vector_byte_size constraint: non-target AND results masked
    /// to `[0, vector_byte_size)` must not collide with any literal.
    pub fn verify_with_mask(&self, vector_byte_size: usize) -> bool {
        self.verify_detailed(vector_byte_size).is_ok()
    }

    /// Same check as [`Self::verify_with_mask`], but names the byte that broke it.
    ///
    /// This is the exhaustive 256-value check SOLVE-001 and ENGINE-004 mandate:
    /// every target byte must AND to its assigned literal, and no non-target byte
    /// may collide with a literal once masked to `[0, vector_byte_size)`. It runs
    /// on every solved group, so a satisfiable-but-wrongly-encoded constraint
    /// system cannot reach the engine.
    pub fn verify_detailed(&self, vector_byte_size: usize) -> Result<(), String> {
        let mask = (vector_byte_size - 1) as u8;
        let literal_values: std::collections::HashSet<u8> =
            self.literal_map.iter().map(|&(_, lit)| lit).collect();

        for &(target, expected_lit) in &self.literal_map {
            let result = self.and_at(target);
            if result != expected_lit {
                return Err(format!(
                    "target byte 0x{target:02x} yielded 0x{result:02x}, expected literal 0x{expected_lit:02x}"
                ));
            }
        }

        let target_set: std::collections::HashSet<u8> =
            self.literal_map.iter().map(|&(target, _)| target).collect();

        for byte in 0u16..=255 {
            let b = byte as u8;
            if target_set.contains(&b) {
                continue;
            }
            let result = self.and_at(b) & mask;
            if literal_values.contains(&result) {
                return Err(format!(
                    "non-target byte 0x{b:02x} collides with literal 0x{result:02x}"
                ));
            }
        }
        Ok(())
    }

    /// The nibble-matrix AND for a single byte.
    fn and_at(&self, byte: u8) -> u8 {
        let lo = (byte & 0x0F) as usize;
        let hi = ((byte >> 4) & 0x0F) as usize;
        self.low_nibble_mask[lo] & self.high_nibble_mask[hi]
    }
}
