//! UTF-8 classification and multi-byte gating.
//!
//! Provides the classification table and gating logic for detecting
//! multi-byte UTF-8 codepoints (2/3/4-byte sequences).

/// UTF-8 byte classification values.
pub const CLASSIFY_ASCII: u8 = 1;      // 0x00–0x7F
pub const CLASSIFY_CONTINUATION: u8 = 0; // 0x80–0xBF
pub const CLASSIFY_LEAD2: u8 = 2;      // 0xC0–0xDF
pub const CLASSIFY_LEAD3: u8 = 3;      // 0xE0–0xEF
pub const CLASSIFY_LEAD4: u8 = 4;      // 0xF0–0xF7

/// 16-entry classification table indexed by high nibble (byte >> 4).
///
/// Maps each byte's high nibble to its UTF-8 classification:
/// - 0x0–0x7: ASCII (1)
/// - 0x8–0xB: continuation (0)
/// - 0xC–0xD: 2-byte lead (2)
/// - 0xE: 3-byte lead (3)
/// - 0xF: 4-byte lead (4)
pub const CLASSIFY_TABLE: [u8; 16] = [
    1, 1, 1, 1, 1, 1, 1, 1, // 0x0_–0x7_: ASCII
    0, 0, 0, 0,             // 0x8_–0xB_: continuation
    2, 2,                   // 0xC_–0xD_: 2-byte lead
    3,                       // 0xE_: 3-byte lead
    4,                       // 0xF_: 4-byte lead
];

/// Classify a single byte by its UTF-8 role.
#[inline]
pub fn classify_byte(byte: u8) -> u8 {
    CLASSIFY_TABLE[((byte >> 4) & 0x0F) as usize]
}

/// Check if a byte is non-ASCII (>= 0x80).
#[inline]
pub fn is_non_ascii(byte: u8) -> bool {
    byte & 0x80 != 0
}

/// Encode a Unicode codepoint to UTF-8 bytes.
///
/// Returns the bytes and the length (1-4).
pub fn encode_utf8(codepoint: u32) -> ([u8; 4], usize) {
    let mut buf = [0u8; 4];
    if codepoint <= 0x7F {
        buf[0] = codepoint as u8;
        (buf, 1)
    } else if codepoint <= 0x7FF {
        buf[0] = 0xC0 | ((codepoint >> 6) & 0x1F) as u8;
        buf[1] = 0x80 | (codepoint & 0x3F) as u8;
        (buf, 2)
    } else if codepoint <= 0xFFFF {
        buf[0] = 0xE0 | ((codepoint >> 12) & 0x0F) as u8;
        buf[1] = 0x80 | ((codepoint >> 6) & 0x3F) as u8;
        buf[2] = 0x80 | (codepoint & 0x3F) as u8;
        (buf, 3)
    } else {
        buf[0] = 0xF0 | ((codepoint >> 18) & 0x07) as u8;
        buf[1] = 0x80 | ((codepoint >> 12) & 0x3F) as u8;
        buf[2] = 0x80 | ((codepoint >> 6) & 0x3F) as u8;
        buf[3] = 0x80 | (codepoint & 0x3F) as u8;
        (buf, 4)
    }
}

/// CharSpec — describes a multi-byte codepoint for runtime gating.
#[derive(Debug, Clone)]
pub struct CharSpec {
    /// Number of UTF-8 bytes (2, 3, or 4).
    pub byte_len: usize,
    /// Expected literal byte in each round (index = round).
    pub round_literals: Vec<u8>,
    /// The literal byte output when all rounds match.
    pub final_literal: u8,
}
