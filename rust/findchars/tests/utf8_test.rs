//! UTF-8 multi-byte codepoint detection tests.
//! Covers UTF8-001..012 from the spec.

use findchars::{EngineBuilder, MatchStorage, SimdBackend};

// --- UTF8-001: ASCII Fast Path ---

#[test]
fn utf8_001_ascii_fast_path() {
    // Pure ASCII input with a multi-byte engine should still detect ASCII targets
    let result = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .codepoint("eacute", 0xE9) // é: [0xC3, 0xA9]
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let comma_lit = result.literals["comma"];
    let data = b"hello,world,test";
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    assert_eq!(view.len(), 2);
    assert_eq!(view.literal(0), comma_lit);
    assert_eq!(view.literal(1), comma_lit);
}

// --- UTF8-004: 2-Byte Codepoint Detection ---

#[test]
fn utf8_004_2byte_detection() {
    // é (U+00E9) = [0xC3, 0xA9]
    let result = EngineBuilder::new()
        .codepoint("eacute", 0xE9)
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let lit = result.literals["eacute"];

    // "café" in UTF-8
    let data = "café".as_bytes();
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    assert_eq!(view.len(), 1, "should find exactly one é");
    // Position should be at the lead byte (0xC3)
    let pos = view.position(0) as usize;
    assert_eq!(data[pos], 0xC3, "match should be at lead byte");
    assert_eq!(view.literal(0), lit);
}

#[test]
fn utf8_004_2byte_multiple() {
    // é (U+00E9) = [0xC3, 0xA9]
    let result = EngineBuilder::new()
        .codepoint("eacute", 0xE9)
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    // "résumé" has two é characters
    let data = "résumé".as_bytes();
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    assert_eq!(view.len(), 2, "should find two é characters");
}

// --- UTF8-005: 3-Byte Codepoint Detection ---

#[test]
fn utf8_005_3byte_detection() {
    // ™ (U+2122) = [0xE2, 0x84, 0xA2]
    let result = EngineBuilder::new()
        .codepoint("trademark", 0x2122)
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let lit = result.literals["trademark"];
    let data = "hello™world".as_bytes();
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    assert_eq!(view.len(), 1);
    let pos = view.position(0) as usize;
    assert_eq!(data[pos], 0xE2, "match should be at lead byte");
    assert_eq!(view.literal(0), lit);
}

// --- UTF8-006: 4-Byte Codepoint Detection ---

#[test]
fn utf8_006_4byte_detection() {
    // 😀 (U+1F600) = [0xF0, 0x9F, 0x98, 0x80]
    let result = EngineBuilder::new()
        .codepoint("grinning", 0x1F600)
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let lit = result.literals["grinning"];
    let data = "hello😀world".as_bytes();
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    assert_eq!(view.len(), 1);
    let pos = view.position(0) as usize;
    assert_eq!(data[pos], 0xF0, "match should be at lead byte");
    assert_eq!(view.literal(0), lit);
}

// --- UTF8-007: Shared Lead Bytes ---

#[test]
fn utf8_007_shared_lead_bytes() {
    // é (U+00E9) = [0xC3, 0xA9] and ô (U+00F4) = [0xC3, 0xB4]
    // Both share lead byte 0xC3 but have different continuation bytes
    let result = EngineBuilder::new()
        .codepoint("eacute", 0xE9)
        .codepoint("ocirc", 0xF4)
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let e_lit = result.literals["eacute"];
    let o_lit = result.literals["ocirc"];
    assert_ne!(e_lit, o_lit, "é and ô must have distinct literals");

    let data = "café ôter".as_bytes();
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    assert_eq!(view.len(), 2, "should find both é and ô");

    // Find which match is which by literal
    let mut found_e = false;
    let mut found_o = false;
    for i in 0..view.len() {
        if view.literal(i) == e_lit { found_e = true; }
        if view.literal(i) == o_lit { found_o = true; }
    }
    assert!(found_e, "é not found");
    assert!(found_o, "ô not found");
}

// --- Mixed ASCII + multi-byte ---

#[test]
fn mixed_ascii_and_multibyte() {
    let result = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .codepoint("eacute", 0xE9)
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let comma_lit = result.literals["comma"];
    let e_lit = result.literals["eacute"];

    let data = "café,résumé,test".as_bytes();
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    // Should find: é, comma, é, é, comma
    let mut comma_count = 0;
    let mut e_count = 0;
    for i in 0..view.len() {
        if view.literal(i) == comma_lit { comma_count += 1; }
        if view.literal(i) == e_lit { e_count += 1; }
    }
    assert_eq!(comma_count, 2, "expected 2 commas");
    assert_eq!(e_count, 3, "expected 3 é characters (café + résumé)");
}

// --- UTF8-006: No false positives for continuation bytes ---

#[test]
fn utf8_no_false_positives_at_continuations() {
    // é = [0xC3, 0xA9] — the 0xA9 continuation should NOT be reported as a match
    let result = EngineBuilder::new()
        .codepoint("eacute", 0xE9)
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let data = "café".as_bytes(); // [0x63, 0x61, 0x66, 0xC3, 0xA9]
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    assert_eq!(view.len(), 1, "should only match at lead byte position");
    let pos = view.position(0) as usize;
    assert_eq!(data[pos], 0xC3, "should match at lead byte, not continuation");
}

// --- Parity: scalar vs reference scan for multi-byte ---

#[test]
fn utf8_parity_vs_reference() {
    let result = EngineBuilder::new()
        .codepoint("eacute", 0xE9)
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let data = "héllo wörld café résumé".as_bytes();
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    // Count é occurrences by scanning UTF-8 manually
    let text = "héllo wörld café résumé";
    let expected_count = text.chars().filter(|&c| c == 'é').count();
    assert_eq!(view.len(), expected_count, "match count should equal char count");
}
