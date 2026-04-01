//! VPA chunk filter integration tests.
//! Covers VPA-001..010 from the spec.

use findchars::vpa::prefix::{prefix_xor_scalar_with_carry, prefix_sum_scalar_with_carry};
use findchars::vpa::{FilterFn, FilterLiterals, FilterState};
use findchars::{EngineBuilder, MatchStorage, SimdBackend};

/// A simple quote filter for testing: suppresses structural chars inside quoted regions.
/// Uses prefix XOR to compute quote toggle state, then zeros non-quote structural chars
/// that fall inside quoted regions.
///
/// Literal bindings: [0] = quote literal byte.
fn test_quote_filter(acc: &mut [u8], state: &mut FilterState, literals: &FilterLiterals, len: usize) {
    if literals.is_empty() {
        return;
    }
    let quote_lit = literals[0];

    // Build quote marker array: 0xFF at quote positions, 0x00 elsewhere
    let mut quote_markers = vec![0u8; len];
    let mut has_quotes = false;
    for i in 0..len {
        if acc[i] == quote_lit {
            quote_markers[i] = 0xFF;
            has_quotes = true;
        }
    }

    // VPA-010: Fast path skip — no quotes and no carry
    if !has_quotes && state[0] == 0 {
        return;
    }

    // Prefix XOR with carry propagation (VPA-003, VPA-005)
    let carry_in = if state[0] != 0 { 0xFF } else { 0x00 };
    let carry_out = prefix_xor_scalar_with_carry(&mut quote_markers, carry_in);

    // Update carry for next chunk (VPA-005)
    state[0] = if carry_out != 0 { 1 } else { 0 };

    // VPA-006: Zero out structural (non-zero, non-quote) chars inside quotes
    for i in 0..len {
        if quote_markers[i] != 0 && acc[i] != 0 && acc[i] != quote_lit {
            acc[i] = 0; // zeroed — excluded from match output
        }
    }
}

// --- VPA-001: Chunk Filter Interface ---

#[test]
fn vpa_001_filter_receives_accumulator() {
    // Build engine with filter
    let result = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .codepoints("quote", &[b'"'])
        .chunk_filter(test_quote_filter as FilterFn, &["quote"])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let data = b"a,b";
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    // No quotes, so filter is a no-op — both commas visible
    let comma_lit = result.literals["comma"];
    assert_eq!(view.len(), 1);
    assert_eq!(view.literal(0), comma_lit);
}

// --- VPA-003 + VPA-006: Quote filtering suppresses structural chars ---

#[test]
fn vpa_003_quote_filter_suppresses_commas() {
    let result = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .codepoints("quote", &[b'"'])
        .chunk_filter(test_quote_filter as FilterFn, &["quote"])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let comma_lit = result.literals["comma"];
    let quote_lit = result.literals["quote"];

    // "a,b","c" — the comma inside quotes should be suppressed
    let data = br#""a,b","c""#;
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    // Expected matches: quote(0), quote(4), comma(5), quote(7), quote(8) — NOT comma(2)
    let mut commas = Vec::new();
    let mut quotes = Vec::new();
    for i in 0..view.len() {
        if view.literal(i) == comma_lit {
            commas.push(view.position(i));
        } else if view.literal(i) == quote_lit {
            quotes.push(view.position(i));
        }
    }

    assert_eq!(commas, vec![5], "only the comma between fields should be visible");
    assert_eq!(quotes.len(), 4, "all 4 quotes should be visible");
}

// --- VPA-005: Carry propagation across chunks ---

#[test]
fn vpa_005_carry_across_chunks() {
    let result = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .codepoints("quote", &[b'"'])
        .chunk_filter(test_quote_filter as FilterFn, &["quote"])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let comma_lit = result.literals["comma"];

    // Build data longer than one scalar chunk (16 bytes) where a quoted region spans chunks
    // Chunk 1: `"field with com`  (16 bytes, quote at 0, no closing quote)
    // Chunk 2: `mas,inside",out`  (16 bytes, closing quote at 11)
    let data = br#""field with commas,inside",outside,"#;
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    let mut visible_commas: Vec<u32> = Vec::new();
    for i in 0..view.len() {
        if view.literal(i) == comma_lit {
            visible_commas.push(view.position(i));
        }
    }

    // The comma at position 18 ("commas,inside") should be suppressed
    // The comma at position 26 (after closing quote) should be visible
    assert!(
        !visible_commas.contains(&18),
        "comma inside quotes should be suppressed, visible commas: {visible_commas:?}"
    );
    // Find the comma after the closing quote
    let outside_comma_found = visible_commas.iter().any(|&p| p > 25);
    assert!(outside_comma_found, "comma after closing quote should be visible: {visible_commas:?}");
}

// --- VPA-007: No-op default ---

#[test]
fn vpa_007_no_filter_passthrough() {
    // Engine without filter should produce same results as with no-op filter
    let no_filter = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let with_noop = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .chunk_filter(findchars::vpa::no_op_filter, &[])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let data = b"a,b,c,d";
    let mut s1 = MatchStorage::new(64);
    let mut s2 = MatchStorage::new(64);

    let v1 = no_filter.engine.find(data, &mut s1);
    let v2 = with_noop.engine.find(data, &mut s2);

    assert_eq!(v1.len(), v2.len());
    for i in 0..v1.len() {
        assert_eq!(v1.position(i), v2.position(i));
    }
}

// --- VPA-002: Filter state reset between find() calls ---

#[test]
fn vpa_002_state_reset_between_calls() {
    let result = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .codepoints("quote", &[b'"'])
        .chunk_filter(test_quote_filter as FilterFn, &["quote"])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let comma_lit = result.literals["comma"];
    let mut storage = MatchStorage::new(64);

    // First call: data with open quote (no close) — sets carry state
    let data1 = br#""a,b"#;
    let v1 = result.engine.find(data1, &mut storage);
    let commas1: Vec<u32> = (0..v1.len())
        .filter(|&i| v1.literal(i) == comma_lit)
        .map(|i| v1.position(i))
        .collect();
    // Comma should be suppressed (inside unclosed quote)
    assert!(commas1.is_empty(), "comma should be suppressed in unclosed quote");

    // Second call: clean data — state should be reset
    let data2 = b"a,b";
    let v2 = result.engine.find(data2, &mut storage);
    let commas2: Vec<u32> = (0..v2.len())
        .filter(|&i| v2.literal(i) == comma_lit)
        .map(|i| v2.position(i))
        .collect();
    assert_eq!(commas2, vec![1], "comma should be visible after state reset");
}
