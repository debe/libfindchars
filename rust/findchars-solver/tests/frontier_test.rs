//! SOLVE-003: where the nibble matrix actually stops solving.
//!
//! SOLVE-003 is a MUST carrying a ">95% success rate" acceptance criterion but
//! has never had a harness behind it. These tests supply one. Above the
//! `solve_disjoint` floor there is no theorem to prove — whether a set solves
//! depends on the nibble-incidence structure of its bytes, not on how many there
//! are — so the honest thing to report is a measurement.
//!
//! The full sweep is `#[ignore]`d because it runs hundreds of Z3 solves — roughly
//! 13 minutes, since every *failing* point costs the full 5-second budget:
//!
//! ```text
//! cargo test --release -p findchars-solver --test frontier_test -- --ignored --nocapture
//! ```

use findchars_solver::{AsciiLiteralGroup, ByteLiteral, LiteralCompiler};

/// Deterministic xorshift — no rand dependency in this crate.
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
}

/// `count` distinct printable-ASCII bytes, one per literal.
fn random_ascii_literals(rng: &mut Rng, count: usize) -> Vec<ByteLiteral> {
    let mut bytes: Vec<u8> = Vec::new();
    while bytes.len() < count {
        let b = 0x21 + (rng.next() % (0x7E - 0x21 + 1)) as u8;
        if !bytes.contains(&b) {
            bytes.push(b);
        }
    }
    bytes
        .iter()
        .enumerate()
        .map(|(i, &b)| ByteLiteral::new(format!("l{i}"), vec![b]))
        .collect()
}

/// Fraction of `trials` random `count`-literal sets that solve in one group.
fn success_rate(seed: u64, vbs: usize, count: usize, trials: usize) -> f64 {
    let mut rng = Rng(seed);
    let mut solved = 0usize;
    for _ in 0..trials {
        let literals = random_ascii_literals(&mut rng, count);
        let group = AsciiLiteralGroup::new(literals);
        if let Ok(masks) = LiteralCompiler::solve(&[], vbs, &[group]) {
            for mask in &masks {
                mask.verify_detailed(vbs).expect("solved mask must verify");
            }
            solved += 1;
        }
    }
    solved as f64 / trials as f64
}

/// SOLVE-003: a single group solves at least 12 ASCII literals.
///
/// This is a *measurement*, not a guarantee, and it is deliberately not part of
/// the default run. Z3 works against a fixed time budget, so on a loaded machine
/// a solvable set can come back undecided — which is precisely why the guaranteed
/// floor is the bit-disjoint construction (asserted deterministically in
/// `solver::tests`) rather than anything Z3 does.
#[test]
#[ignore = "Z3 timing is load-dependent — run with --release --ignored --nocapture"]
fn solve_003_twelve_ascii_literals_in_one_group() {
    let rate = success_rate(0x1234_5678, 64, 12, 10);
    assert!(
        rate >= 0.95,
        "12 ASCII literals solved in only {:.0}% of attempts",
        rate * 100.0
    );
}

/// SOLVE-003: the measured frontier per vector width. Reports rather than
/// asserts above 12 — the point is to record where capacity actually falls off.
#[test]
#[ignore = "hundreds of Z3 solves — run with --release --ignored --nocapture"]
fn solve_003_frontier_sweep() {
    const TRIALS: usize = 25;
    for vbs in [16usize, 32, 64] {
        println!("\n-- vector_byte_size = {vbs} (max literal {}) --", vbs - 1);
        for count in 4..=20usize {
            let rate = success_rate(0xC0FFEE ^ (vbs as u64), vbs, count, TRIALS);
            println!("  {count:2} literals: {:5.1}% solved", rate * 100.0);
            if rate == 0.0 {
                println!("  (frontier reached at {count})");
                break;
            }
        }
    }
}
