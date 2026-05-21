//! Fuzz/parity test: seeded randomized engine configurations validated against
//! a linear-scan oracle, across every SIMD backend the host CPU supports.
//!
//! Mirrors the Java `FuzzRegexParityTest`. Each round is deterministically
//! replayable via its seed; unsolvable solver configurations are skipped
//! (the suite tolerates a minority of skips, like the Java equivalent).
//!
//! Covers ENGINE-005 (detection correctness), ENGINE-006 (no false positives),
//! ENGINE-007 (match ordering), COMP-005 (backend parity), and UTF8-004..008.

use findchars::{BuildResult, EngineBuilder, MatchStorage, SimdBackend};
use rand::rngs::StdRng;
use rand::seq::SliceRandom;
use rand::{Rng, SeedableRng};

/// SIMD backends the host CPU actually supports — scalar plus whatever the
/// architecture provides. Requesting an unsupported backend would execute
/// unavailable instructions, so the set is gated on runtime feature detection.
fn available_backends() -> Vec<SimdBackend> {
    let mut backends = vec![SimdBackend::Scalar];
    #[cfg(target_arch = "x86_64")]
    {
        if is_x86_feature_detected!("avx2") && is_x86_feature_detected!("ssse3") {
            backends.push(SimdBackend::Avx2);
        }
        if is_x86_feature_detected!("avx512bw")
            && is_x86_feature_detected!("avx512vbmi")
            && is_x86_feature_detected!("avx512vbmi2")
        {
            backends.push(SimdBackend::Avx512);
        }
    }
    #[cfg(target_arch = "aarch64")]
    {
        backends.push(SimdBackend::Neon);
    }
    backends
}

// --- ASCII fuzz/parity ---

/// A randomly generated engine configuration for one ASCII fuzz round.
struct RoundConfig {
    /// ASCII literal groups: `(name, distinct bytes)`.
    groups: Vec<(String, Vec<u8>)>,
    /// Optional inclusive byte range: `(name, from, to)`, disjoint from groups.
    range: Option<(String, u8, u8)>,
    /// Random data to scan.
    data: Vec<u8>,
}

/// Generate a random configuration. Group bytes and the range are kept disjoint
/// so every matching byte maps to exactly one literal — making the linear-scan
/// oracle unambiguous.
fn generate_config(rng: &mut StdRng) -> RoundConfig {
    // Optionally pick a contiguous byte range first.
    let range = if rng.random_bool(0.5) {
        let from = rng.random_range(0x21u8..=0x70);
        let width = rng.random_range(0u8..=8);
        let to = from.saturating_add(width).min(0x7E);
        Some(("range".to_string(), from, to))
    } else {
        None
    };

    // Printable-ASCII pool excluding the range, shuffled deterministically.
    let mut pool: Vec<u8> = (0x21u8..=0x7E)
        .filter(|&b| match range {
            Some((_, f, t)) => b < f || b > t,
            None => true,
        })
        .collect();
    pool.shuffle(rng);
    let mut pool_iter = pool.into_iter();

    // 1–4 groups, at most 12 distinct ASCII bytes total (single-group budget).
    let num_groups = rng.random_range(1usize..=4);
    let mut budget: usize = 12;
    let mut groups: Vec<(String, Vec<u8>)> = Vec::new();
    for g in 0..num_groups {
        if budget == 0 {
            break;
        }
        let size = rng.random_range(1usize..=4).min(budget);
        let bytes: Vec<u8> = (0..size).filter_map(|_| pool_iter.next()).collect();
        if bytes.is_empty() {
            break;
        }
        budget -= bytes.len();
        groups.push((format!("g{g}"), bytes));
    }

    // Random data with a 5–30% target density.
    let data_len = rng.random_range(4096usize..=32768);
    let density = rng.random_range(0.05f64..0.30);

    let mut is_target = [false; 256];
    for (_, bytes) in &groups {
        for &b in bytes {
            is_target[b as usize] = true;
        }
    }
    if let Some((_, f, t)) = range {
        for b in f..=t {
            is_target[b as usize] = true;
        }
    }
    let target_bytes: Vec<u8> = (0..=255u8).filter(|&b| is_target[b as usize]).collect();
    let filler_bytes: Vec<u8> = (0..=255u8).filter(|&b| !is_target[b as usize]).collect();

    let mut data = Vec::with_capacity(data_len);
    for _ in 0..data_len {
        if !target_bytes.is_empty() && rng.random_bool(density) {
            data.push(target_bytes[rng.random_range(0..target_bytes.len())]);
        } else {
            data.push(filler_bytes[rng.random_range(0..filler_bytes.len())]);
        }
    }

    RoundConfig { groups, range, data }
}

/// Build an engine for `config` on `backend`, or `None` if the solver fails.
fn build_engine(config: &RoundConfig, backend: SimdBackend) -> Option<BuildResult> {
    let mut builder = EngineBuilder::new().backend(backend);
    for (name, bytes) in &config.groups {
        builder = builder.codepoints(name, bytes);
    }
    if let Some((name, from, to)) = &config.range {
        builder = builder.range(name, *from, *to);
    }
    builder.build().ok()
}

/// The configured target name for `byte`, or `None` if it is not a target.
fn target_name(config: &RoundConfig, byte: u8) -> Option<&str> {
    for (name, bytes) in &config.groups {
        if bytes.contains(&byte) {
            return Some(name);
        }
    }
    if let Some((name, from, to)) = &config.range
        && byte >= *from
        && byte <= *to
    {
        return Some(name);
    }
    None
}

/// Assert the engine's matches equal the linear-scan oracle for `config`.
fn check(config: &RoundConfig, result: &BuildResult, label: &str) {
    let mut storage = MatchStorage::new(config.data.len() / 4 + 64);
    let view = result.engine.find(&config.data, &mut storage);

    let expected: Vec<usize> = config
        .data
        .iter()
        .enumerate()
        .filter(|&(_, &b)| target_name(config, b).is_some())
        .map(|(i, _)| i)
        .collect();

    assert_eq!(view.len(), expected.len(), "{label}: match count mismatch");
    for (i, &pos) in expected.iter().enumerate() {
        assert_eq!(
            view.position(i) as usize,
            pos,
            "{label}: position mismatch at index {i}"
        );
        let name = target_name(config, config.data[pos]).unwrap();
        assert_eq!(
            view.literal(i),
            result.literals[name],
            "{label}: literal mismatch at index {i} (target '{name}')"
        );
    }
}

/// Run one ASCII round on every available backend; `false` if unsolvable.
fn run_round(seed: u64) -> bool {
    let mut rng = StdRng::seed_from_u64(seed);
    let config = generate_config(&mut rng);

    for backend in available_backends() {
        let Some(result) = build_engine(&config, backend) else {
            return false;
        };
        check(&config, &result, &format!("{backend:?}"));
    }
    true
}

#[test]
fn fuzz_parity_ascii() {
    const ROUNDS: u64 = 50;
    let mut solved = 0u64;
    for round in 0..ROUNDS {
        if run_round(round) {
            solved += 1;
        }
    }
    println!("fuzz_parity_ascii: {solved}/{ROUNDS} rounds solved");
    assert!(
        solved >= ROUNDS / 2,
        "too many unsolvable rounds: only {solved}/{ROUNDS} solved"
    );
}

// --- Multi-byte UTF-8 fuzz ---

/// Codepoints with distinct lead bytes — embedded at known positions so the
/// expected output is exact by construction.
const CODEPOINTS: &[(&str, u32)] = &[
    ("eacute", 0x00E9),    // é  — 2-byte
    ("trademark", 0x2122), // ™  — 3-byte
    ("grin", 0x1F600),     // 😀 — 4-byte
];

/// Run one multi-byte round on every available backend; `false` if unsolvable.
fn run_multibyte_round(rng: &mut StdRng) -> bool {
    // Random non-empty subset of codepoints.
    let mut selected: Vec<(&str, u32)> = CODEPOINTS
        .iter()
        .copied()
        .filter(|_| rng.random_bool(0.6))
        .collect();
    if selected.is_empty() {
        selected.push(CODEPOINTS[rng.random_range(0..CODEPOINTS.len())]);
    }

    // Build data: alternating ASCII-letter filler and embedded codepoints.
    // Filler bytes are letters — never UTF-8 lead or continuation bytes — so the
    // recorded positions are the only matches the engine can produce.
    let encoded: Vec<(&str, Vec<u8>)> = selected
        .iter()
        .map(|&(name, cp)| {
            let bytes = char::from_u32(cp).unwrap().to_string().into_bytes();
            (name, bytes)
        })
        .collect();
    let mut data: Vec<u8> = Vec::new();
    let mut expected: Vec<(usize, &str)> = Vec::new();
    let segments = rng.random_range(20usize..=120);
    for _ in 0..segments {
        let fill = rng.random_range(0usize..=8);
        for _ in 0..fill {
            data.push(b'a' + rng.random_range(0u8..26));
        }
        let (name, bytes) = &encoded[rng.random_range(0..encoded.len())];
        expected.push((data.len(), name));
        data.extend_from_slice(bytes);
    }

    for backend in available_backends() {
        let mut builder = EngineBuilder::new().backend(backend);
        for (name, cp) in &selected {
            builder = builder.codepoint(name, *cp);
        }
        let Ok(result) = builder.build() else {
            return false;
        };

        let mut storage = MatchStorage::new(expected.len() + 64);
        let view = result.engine.find(&data, &mut storage);

        assert_eq!(
            view.len(),
            expected.len(),
            "{backend:?}: multibyte match count mismatch"
        );
        for (i, &(pos, name)) in expected.iter().enumerate() {
            assert_eq!(
                view.position(i) as usize,
                pos,
                "{backend:?}: multibyte position mismatch at index {i}"
            );
            assert_eq!(
                view.literal(i),
                result.literals[name],
                "{backend:?}: multibyte literal mismatch at index {i} (codepoint '{name}')"
            );
        }
    }
    true
}

#[test]
fn fuzz_parity_multibyte() {
    const ROUNDS: u64 = 24;
    let mut solved = 0u64;
    for round in 0..ROUNDS {
        let mut rng = StdRng::seed_from_u64(round.wrapping_add(0x9E37));
        if run_multibyte_round(&mut rng) {
            solved += 1;
        }
    }
    println!("fuzz_parity_multibyte: {solved}/{ROUNDS} rounds solved");
    assert!(
        solved >= ROUNDS / 2,
        "too many unsolvable rounds: only {solved}/{ROUNDS} solved"
    );
}
