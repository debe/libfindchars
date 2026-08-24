//! UTF-8 classification and multi-byte gating.
//!
//! Provides the classification table and gating logic for detecting
//! multi-byte UTF-8 codepoints (2/3/4-byte sequences).

/// UTF-8 byte classification values.
pub const CLASSIFY_ASCII: u8 = 1; // 0x00–0x7F
pub const CLASSIFY_CONTINUATION: u8 = 0; // 0x80–0xBF
pub const CLASSIFY_LEAD2: u8 = 2; // 0xC0–0xDF
pub const CLASSIFY_LEAD3: u8 = 3; // 0xE0–0xEF
pub const CLASSIFY_LEAD4: u8 = 4; // 0xF0–0xFF (see the note on CLASSIFY_TABLE)

/// 16-entry classification table indexed by high nibble (byte >> 4).
///
/// Maps each byte's high nibble to its UTF-8 classification:
/// - 0x0–0x7: ASCII (1)
/// - 0x8–0xB: continuation (0)
/// - 0xC–0xD: 2-byte lead (2)
/// - 0xE: 3-byte lead (3)
/// - 0xF: 4-byte lead (4)
///
/// Classification is *not* validation. Because the index is the high nibble
/// alone, `0xC0`/`0xC1` classify as 2-byte leads (overlong forms) and the whole
/// `0xF_` row classifies as a 4-byte lead — including `0xF5`–`0xFF`, which can
/// never begin a well-formed sequence. Surrogate leads (`0xED`) are likewise
/// indistinguishable from any other 3-byte lead. libfindchars detects configured
/// characters in a byte stream that is *assumed* to be well-formed UTF-8; it does
/// not reject malformed input. Callers needing validation must do it upstream.
pub const CLASSIFY_TABLE: [u8; 16] = [
    1, 1, 1, 1, 1, 1, 1, 1, // 0x0_–0x7_: ASCII
    0, 0, 0, 0, // 0x8_–0xB_: continuation
    2, 2, // 0xC_–0xD_: 2-byte lead
    3, // 0xE_: 3-byte lead
    4, // 0xF_: 4-byte lead
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
///
/// # Preconditions
///
/// `codepoint` must be a Unicode scalar value: at most `U+10FFFF` and not a
/// surrogate. The 4-byte branch masks the top bits away, so `0x110000` would
/// otherwise encode silently as `U+10000`. `EngineBuilder::build` rejects
/// invalid codepoints before reaching here, matching the Java builder, which
/// rejects them via `Character.toChars`.
pub fn encode_utf8(codepoint: u32) -> ([u8; 4], usize) {
    debug_assert!(
        char::from_u32(codepoint).is_some(),
        "encode_utf8 called with non-scalar value U+{codepoint:04X}"
    );
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

#[cfg(test)]
mod tests {
    use super::*;

    /// UTF8-002 AC1: all 256 byte values classify correctly. The oracle is an
    /// independent range match rather than the table itself, so a wrong table
    /// entry cannot pass by agreeing with itself.
    #[test]
    fn utf8_002_classifies_all_256_bytes() {
        fn reference(byte: u8) -> u8 {
            match byte {
                0x00..=0x7F => CLASSIFY_ASCII,
                0x80..=0xBF => CLASSIFY_CONTINUATION,
                0xC0..=0xDF => CLASSIFY_LEAD2,
                0xE0..=0xEF => CLASSIFY_LEAD3,
                0xF0..=0xFF => CLASSIFY_LEAD4,
            }
        }

        for byte in 0u16..=255 {
            let b = byte as u8;
            assert_eq!(
                classify_byte(b),
                reference(b),
                "byte 0x{b:02x} misclassified"
            );
            assert_eq!(is_non_ascii(b), b >= 0x80, "byte 0x{b:02x} ascii flag");
        }
    }

    /// UTF8-002: the classifier deliberately admits bytes that cannot begin a
    /// well-formed sequence. This pins that contract so a future change to make
    /// it a validator is a conscious one rather than a silent behaviour shift.
    #[test]
    fn utf8_002_classification_is_not_validation() {
        // Overlong 2-byte leads.
        assert_eq!(classify_byte(0xC0), CLASSIFY_LEAD2);
        assert_eq!(classify_byte(0xC1), CLASSIFY_LEAD2);
        // Surrogate lead is an ordinary 3-byte lead.
        assert_eq!(classify_byte(0xED), CLASSIFY_LEAD3);
        // Beyond U+10FFFF, and bytes that are never leads at all.
        for b in [0xF5u8, 0xF7, 0xF8, 0xFE, 0xFF] {
            assert_eq!(classify_byte(b), CLASSIFY_LEAD4, "byte 0x{b:02x}");
        }
    }

    /// UTF8-011: encoding agrees with the standard encoder across the entire
    /// valid codepoint space — all 1,112,064 scalar values, surrogates excluded.
    #[test]
    fn utf8_011_encode_matches_std_for_every_valid_codepoint() {
        let mut scratch = [0u8; 4];
        let mut checked = 0u32;

        for cp in 0u32..=0x10FFFF {
            let Some(ch) = char::from_u32(cp) else {
                continue; // surrogate range D800-DFFF
            };
            let (bytes, len) = encode_utf8(cp);
            assert_eq!(
                &bytes[..len],
                ch.encode_utf8(&mut scratch).as_bytes(),
                "U+{cp:04X}"
            );
            checked += 1;
        }

        assert_eq!(checked, 1_112_064, "unexpected valid codepoint count");
    }
}
