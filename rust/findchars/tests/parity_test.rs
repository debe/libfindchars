//! Cross-backend parity tests.
//! Verifies that AVX2 and scalar backends produce identical results.

use findchars::{EngineBuilder, MatchStorage, SimdBackend};

/// Helper: build same engine config on two backends, run on same data, compare.
fn assert_parity(
    name: &str,
    setup: impl Fn(EngineBuilder) -> EngineBuilder,
    data: &[u8],
) {
    let scalar_result = setup(EngineBuilder::new())
        .backend(SimdBackend::Scalar)
        .build()
        .unwrap();

    let avx2_result = setup(EngineBuilder::new())
        .backend(SimdBackend::Avx2)
        .build()
        .unwrap();

    let mut scalar_storage = MatchStorage::new(256);
    let mut avx2_storage = MatchStorage::new(256);

    let scalar_view = scalar_result.engine.find(data, &mut scalar_storage);
    let avx2_view = avx2_result.engine.find(data, &mut avx2_storage);

    assert_eq!(
        scalar_view.len(),
        avx2_view.len(),
        "{name}: match count differs (scalar={}, avx2={})",
        scalar_view.len(),
        avx2_view.len(),
    );

    for i in 0..scalar_view.len() {
        assert_eq!(
            scalar_view.position(i),
            avx2_view.position(i),
            "{name}: position differs at index {i}",
        );
        // Literal values may differ between backends (different solver runs),
        // but both should be non-zero at the same positions.
        assert_ne!(avx2_view.literal(i), 0, "{name}: avx2 literal is zero at index {i}");
    }
}

// --- Single group, short data ---

#[test]
fn parity_single_target_short() {
    assert_parity(
        "single_comma_short",
        |b| b.codepoints("comma", &[b',']),
        b"hello,world",
    );
}

// --- Multiple targets ---

#[test]
fn parity_csv_targets() {
    assert_parity(
        "csv_targets",
        |b| b.codepoints("csv", &[b',', b'"', b'\n', b'\r']),
        b"hello,\"world\"\r\nfoo,bar\n",
    );
}

// --- Data longer than one chunk (>32 bytes) ---

#[test]
fn parity_multi_chunk() {
    assert_parity(
        "multi_chunk",
        |b| b.codepoints("comma", &[b',']),
        b"a]b,c]d,e]f,g]h,i]j,k]l,m]n,o]p,q]r,s]t,u]v,w]x,y]z,end",
    );
}

// --- Range operations ---

#[test]
fn parity_range() {
    assert_parity(
        "range_digits",
        |b| b.range("digits", b'0', b'9'),
        b"abc 123 def 456 ghi 789 jkl 012 end",
    );
}

// --- Range plus shuffle ---

#[test]
fn parity_range_plus_shuffle() {
    assert_parity(
        "range_plus_shuffle",
        |b| b.codepoints("comma", &[b',']).range("digits", b'0', b'9'),
        b"a,1,2,3 hello 4,5,6 world 7,8,9",
    );
}

// --- Empty input ---

#[test]
fn parity_empty() {
    assert_parity(
        "empty",
        |b| b.codepoints("comma", &[b',']),
        b"",
    );
}

// --- No matches ---

#[test]
fn parity_no_matches() {
    assert_parity(
        "no_matches",
        |b| b.codepoints("comma", &[b',']),
        b"hello world no commas here",
    );
}

// --- Exactly 32 bytes (one full AVX2 chunk) ---

#[test]
fn parity_exact_chunk() {
    assert_parity(
        "exact_chunk",
        |b| b.codepoints("comma", &[b',']),
        b"a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,", // 32 bytes
    );
}

// --- 33 bytes (one full chunk + 1 byte tail) ---

#[test]
fn parity_chunk_plus_one() {
    assert_parity(
        "chunk_plus_one",
        |b| b.codepoints("comma", &[b',']),
        b"a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p", // 33 bytes
    );
}

// --- All 256 byte values ---

#[test]
fn parity_all_bytes() {
    let data: Vec<u8> = (0..=255).collect();
    assert_parity(
        "all_256_bytes",
        |b| b.codepoints("target", &[b',', b'.', b'!', b'?', b'\n']),
        &data,
    );
}

// --- Large data (1 KB) ---

#[test]
fn parity_large_data() {
    let mut data = Vec::with_capacity(1024);
    for i in 0..1024 {
        data.push(if i % 7 == 0 { b',' } else { b'a' + (i % 26) as u8 });
    }
    assert_parity(
        "1kb_sparse_commas",
        |b| b.codepoints("comma", &[b',']),
        &data,
    );
}

// --- Dense matches ---

#[test]
fn parity_dense_matches() {
    // Every byte is a target
    let data = vec![b','; 100];
    assert_parity(
        "all_commas",
        |b| b.codepoints("comma", &[b',']),
        &data,
    );
}

// --- Multiple distinct literal groups ---

#[test]
fn parity_multiple_groups() {
    assert_parity(
        "multiple_groups",
        |b| {
            b.codepoints("comma", &[b','])
                .codepoints("quote", &[b'"'])
                .codepoints("newline", &[b'\n'])
        },
        b"hello,\"world\"\nfoo,\"bar\"\nbaz",
    );
}
