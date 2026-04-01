//! Integration tests for the core engine.
//! Covers ENGINE-001..009, ENGINE-012, ENGINE-014, ENGINE-015 from the spec.

use findchars::{EngineBuilder, MatchStorage, SimdBackend};

// --- ENGINE-001: Engine Interface ---

#[test]
fn engine_001_find_returns_match_view() {
    let result = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(b"a,b,c", &mut storage);

    assert_eq!(view.len(), 2);
    assert_eq!(view.position(0), 1);
    assert_eq!(view.position(1), 3);
}

// --- ENGINE-002: Literal Identity ---

#[test]
fn engine_002_distinct_literal_bytes() {
    let result = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .codepoints("quote", &[b'"'])
        .codepoints("newline", &[b'\n'])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let lits = &result.literals;
    assert_eq!(lits.len(), 3);

    // All non-zero
    for (_, &v) in lits.iter() {
        assert_ne!(v, 0, "literal byte must be non-zero");
    }

    // All distinct
    let vals: std::collections::HashSet<u8> = lits.values().copied().collect();
    assert_eq!(vals.len(), 3, "literals must be distinct");
}

#[test]
fn engine_002_literal_map_names() {
    let result = EngineBuilder::new()
        .codepoints("ws", &[b'\t', b'\n', b' '])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    assert!(result.literals.contains_key("ws"));
}

// --- ENGINE-005: Detection Correctness ---

#[test]
fn engine_005_all_targets_detected() {
    let result = EngineBuilder::new()
        .codepoints("csv", &[b',', b'"', b'\n', b'\r'])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let data = b"hello,\"world\"\r\n";
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    let csv_lit = result.literals["csv"];

    // Expected positions: 5(,) 6(") 12(") 13(\r) 14(\n)
    assert_eq!(view.len(), 5);
    assert_eq!(view.position(0), 5);
    assert_eq!(view.position(1), 6);
    assert_eq!(view.position(2), 12);
    assert_eq!(view.position(3), 13);
    assert_eq!(view.position(4), 14);

    // All should have the same literal (single group)
    for i in 0..view.len() {
        assert_eq!(view.literal(i), csv_lit);
    }
}

#[test]
fn engine_005_multiple_groups_detected() {
    let result = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .codepoints("newline", &[b'\n'])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let data = b"a,b\nc,d\n";
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    let comma_lit = result.literals["comma"];
    let nl_lit = result.literals["newline"];

    assert_eq!(view.len(), 4);
    assert_eq!((view.position(0), view.literal(0)), (1, comma_lit));
    assert_eq!((view.position(1), view.literal(1)), (3, nl_lit));
    assert_eq!((view.position(2), view.literal(2)), (5, comma_lit));
    assert_eq!((view.position(3), view.literal(3)), (7, nl_lit));
}

// --- ENGINE-006: No False Positives ---

#[test]
fn engine_006_no_false_positives() {
    let result = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    // Input with no commas
    let data = b"hello world 12345";
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    assert_eq!(view.len(), 0);
}

#[test]
fn engine_006_all_256_bytes_no_false_positives() {
    let result = EngineBuilder::new()
        .codepoints("target", &[b',', b'.'])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let target_lit = result.literals["target"];

    // Test all 256 byte values
    let data: Vec<u8> = (0..=255).collect();
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(&data, &mut storage);

    for i in 0..view.len() {
        let pos = view.position(i) as usize;
        let byte = data[pos];
        assert!(
            byte == b',' || byte == b'.',
            "false positive at position {pos} byte {byte:#04x}"
        );
        assert_eq!(view.literal(i), target_lit);
    }
}

// --- ENGINE-007: Match Ordering ---

#[test]
fn engine_007_ascending_positions() {
    let result = EngineBuilder::new()
        .codepoints("chars", &[b',', b'.', b'!', b'?'])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let data = b"hello, world! how? are. you,";
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    for i in 1..view.len() {
        assert!(
            view.position(i) > view.position(i - 1),
            "positions not ascending: pos[{}]={} >= pos[{}]={}",
            i - 1, view.position(i - 1), i, view.position(i)
        );
    }
}

// --- ENGINE-008: Storage Reuse ---

#[test]
fn engine_008_storage_reuse() {
    let result = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let mut storage = MatchStorage::new(64);

    // First call
    let view1 = result.engine.find(b"a,b,c", &mut storage);
    assert_eq!(view1.len(), 2);

    // Second call with different data — results should be independent
    let view2 = result.engine.find(b"x,y", &mut storage);
    assert_eq!(view2.len(), 1);
    assert_eq!(view2.position(0), 1);
}

// --- ENGINE-009: Auto-Growing Storage ---

#[test]
fn engine_009_auto_growing_storage() {
    let result = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    // Start with tiny capacity
    let mut storage = MatchStorage::new(2);

    // Input with many matches
    let data = b"a,b,c,d,e,f,g,h,i,j";
    let view = result.engine.find(data, &mut storage);
    assert_eq!(view.len(), 9); // 9 commas
}

// --- ENGINE-012: Literal Namespace Limits ---

#[test]
fn engine_012_namespace_limit_scalar() {
    // Scalar backend: vector_byte_size=16, max_literals=15
    let mut builder = EngineBuilder::new().backend(SimdBackend::Scalar);
    // Add 16 individual codepoints — should exceed the limit of 15
    for c in b'A'..=b'P' {
        builder = builder.codepoints(&format!("c_{c}"), &[c]);
    }

    let result = builder.build();
    assert!(result.is_err(), "should reject 16 literals on 16-byte backend");
}

// --- ENGINE-014: Engine Not Thread-Safe (separate instances are fine) ---

#[test]
fn engine_014_separate_instances_parallel() {
    let result = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    // We can't easily test NOT-thread-safe, but we can verify separate
    // storage instances produce correct results
    let engine = &result.engine;

    let mut s1 = MatchStorage::new(64);
    let mut s2 = MatchStorage::new(64);

    let v1 = engine.find(b"a,b", &mut s1);
    let v2 = engine.find(b"x,y,z", &mut s2);

    assert_eq!(v1.len(), 1);
    assert_eq!(v2.len(), 2);
}

// --- ENGINE-015: Empty Input ---

#[test]
fn engine_015_empty_input() {
    let result = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(b"", &mut storage);
    assert_eq!(view.len(), 0);
}

// --- Range operations (SOLVE-008 / UTF8-009) ---

#[test]
fn range_detection() {
    let result = EngineBuilder::new()
        .range("digits", b'0', b'9')
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let digit_lit = result.literals["digits"];
    let data = b"abc 123 xyz";
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    assert_eq!(view.len(), 3);
    assert_eq!(view.position(0), 4); // '1'
    assert_eq!(view.position(1), 5); // '2'
    assert_eq!(view.position(2), 6); // '3'
    for i in 0..3 {
        assert_eq!(view.literal(i), digit_lit);
    }
}

#[test]
fn range_plus_shuffle() {
    let result = EngineBuilder::new()
        .codepoints("comma", &[b','])
        .range("digits", b'0', b'9')
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let comma_lit = result.literals["comma"];
    let digit_lit = result.literals["digits"];
    assert_ne!(comma_lit, digit_lit);

    let data = b"a,1,2b";
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    assert_eq!(view.len(), 4); // , 1 , 2
    assert_eq!((view.position(0), view.literal(0)), (1, comma_lit));
    assert_eq!((view.position(1), view.literal(1)), (2, digit_lit));
    assert_eq!((view.position(2), view.literal(2)), (3, comma_lit));
    assert_eq!((view.position(3), view.literal(3)), (4, digit_lit));
}

// --- Regex parity (ENGINE-005 cross-check) ---

#[test]
fn parity_vs_linear_scan() {
    let targets = &[b',', b'"', b'\n', b'\r', b'\t', b' '];

    let result = EngineBuilder::new()
        .codepoints("targets", targets)
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let target_lit = result.literals["targets"];

    // Generate test data with known positions
    let data = b"hello, world\t\"foo\"\r\nbar baz";
    let mut storage = MatchStorage::new(64);
    let view = result.engine.find(data, &mut storage);

    // Linear scan reference
    let expected: Vec<usize> = data
        .iter()
        .enumerate()
        .filter(|&(_, b)| targets.contains(b))
        .map(|(i, _)| i)
        .collect();

    assert_eq!(view.len(), expected.len(), "match count mismatch");
    for (i, &exp_pos) in expected.iter().enumerate() {
        assert_eq!(view.position(i) as usize, exp_pos, "position mismatch at index {i}");
        assert_eq!(view.literal(i), target_lit, "literal mismatch at index {i}");
    }
}
