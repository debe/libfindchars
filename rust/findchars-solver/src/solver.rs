//! Z3-based nibble matrix solver.
//!
//! Solves for two 16-entry LUT vectors whose AND uniquely identifies each
//! target character. Uses Z3's bitvector theory for constraint solving.

use z3::ast::{Ast, BV};
use z3::{Config, SatResult, Solver, with_z3_config};

use crate::literal::{AsciiFindMask, AsciiLiteralGroup, ByteLiteral};

/// Why a solve attempt failed.
///
/// [`Self::Timeout`] is deliberately distinct from [`Self::Unsatisfiable`]: Z3
/// giving up inside its budget is not a proof that no LUT pair exists, and the
/// two must not be conflated when deciding whether to fall back or split.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SolveError {
    /// Z3 proved that no valid LUT pair exists for this group.
    Unsatisfiable,
    /// Z3 exhausted its time budget without deciding.
    Timeout,
    /// A solved model failed the exhaustive 256-byte check.
    VerificationFailed(String),
    /// The disjoint fallback ran out of single-bit literal values.
    DisjointCapacity { needed: usize, available: usize },
}

impl std::fmt::Display for SolveError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Unsatisfiable => {
                write!(f, "unsatisfiable: no valid LUT pair exists for this group")
            }
            Self::Timeout => write!(f, "solver timed out (result unknown, not unsatisfiable)"),
            Self::VerificationFailed(why) => write!(f, "solved LUT failed verification: {why}"),
            Self::DisjointCapacity { needed, available } => write!(
                f,
                "disjoint fallback needs {needed} literals, only {available} single-bit values available"
            ),
        }
    }
}

impl std::error::Error for SolveError {}

/// The constraint solver for character detection LUT generation.
pub struct LiteralCompiler;

impl LiteralCompiler {
    /// Solve for LUT pairs that uniquely identify each target character.
    ///
    /// # Arguments
    /// * `used_literals` - Literal byte values already in use (to avoid collisions)
    /// * `vector_byte_size` - Platform vector width in bytes (16, 32, or 64)
    /// * `groups` - Character groups to solve
    ///
    /// # Returns
    /// One `AsciiFindMask` per group, or an error if unsolvable.
    pub fn solve(
        used_literals: &[u8],
        vector_byte_size: usize,
        groups: &[AsciiLiteralGroup],
    ) -> Result<Vec<AsciiFindMask>, SolveError> {
        let mut results = Vec::with_capacity(groups.len());
        let mut used = used_literals.to_vec();

        for group in groups {
            let mask = Self::solve_one(&used, vector_byte_size, group)?;
            // Add newly assigned literals to used set
            for &(_, lit) in &mask.literal_map {
                if !used.contains(&lit) {
                    used.push(lit);
                }
            }
            results.push(mask);
        }

        Ok(results)
    }

    /// Solve a single group of literals.
    fn solve_one(
        used_literals: &[u8],
        vector_byte_size: usize,
        group: &AsciiLiteralGroup,
    ) -> Result<AsciiFindMask, SolveError> {
        // Timeout: 5 seconds per solve attempt. If Z3 can't solve in 5s, the
        // group is likely too large and should be split. A fresh config — and
        // thus a fresh implicit context — isolates each group's solve.
        let mut cfg = Config::new();
        cfg.set_timeout_msec(5000);

        with_z3_config(&cfg, || {
            let solver = Solver::new();

            let num_literals = group.literals.len();

            // Create Z3 bitvector variables for the two 16-entry LUTs
            let low_nibbles: Vec<BV> = (0..16)
                .map(|i| BV::new_const(format!("lo_{i}"), 8))
                .collect();
            let high_nibbles: Vec<BV> = (0..16)
                .map(|i| BV::new_const(format!("hi_{i}"), 8))
                .collect();

            // Create Z3 variables for each literal's assigned byte value
            let lit_vars: Vec<BV> = (0..num_literals)
                .map(|i| BV::new_const(format!("lit_{i}"), 8))
                .collect();

            // Constraint: each literal > 0
            let zero = BV::from_u64(0, 8);
            for lv in &lit_vars {
                solver.assert(lv.bvugt(&zero));
            }

            // Constraint: each literal < vector_byte_size
            let max_lit = BV::from_u64(vector_byte_size as u64, 8);
            for lv in &lit_vars {
                solver.assert(lv.bvult(&max_lit));
            }

            // Constraint: literals are distinct
            for i in 0..num_literals {
                for j in (i + 1)..num_literals {
                    solver.assert(Ast::eq(&lit_vars[i], &lit_vars[j]).not());
                }
            }

            // Constraint: literals not in used set
            for lv in &lit_vars {
                for &used in used_literals {
                    let used_bv = BV::from_u64(used as u64, 8);
                    solver.assert(Ast::eq(lv, &used_bv).not());
                }
            }

            // Matching constraints: for each literal and each target byte,
            // lo[byte & 0xF] AND hi[byte >> 4] == literal_value
            for (lit_idx, literal) in group.literals.iter().enumerate() {
                for &target_byte in &literal.chars {
                    let lo_idx = (target_byte & 0x0F) as usize;
                    let hi_idx = ((target_byte >> 4) & 0x0F) as usize;
                    let and_result = low_nibbles[lo_idx].bvand(&high_nibbles[hi_idx]);
                    solver.assert(Ast::eq(&and_result, &lit_vars[lit_idx]));
                }
            }

            // Exclusion constraints: for all non-target nibble pairs,
            // lo[i] AND hi[j] must not equal any literal
            let mut target_nibble_pairs = std::collections::HashSet::new();
            for literal in &group.literals {
                for &target_byte in &literal.chars {
                    let lo = (target_byte & 0x0F) as usize;
                    let hi = ((target_byte >> 4) & 0x0F) as usize;
                    target_nibble_pairs.insert((lo, hi));
                }
            }

            #[allow(clippy::needless_range_loop)]
            for i in 0..16 {
                for j in 0..16 {
                    if target_nibble_pairs.contains(&(i, j)) {
                        continue;
                    }
                    let and_result = low_nibbles[i].bvand(&high_nibbles[j]);
                    // The AND result must be zero OR must not match any literal
                    // Simplest: require AND result to be zero for non-target pairs
                    // when masked to vector_byte_size
                    let masked = if vector_byte_size < 256 {
                        let mask = BV::from_u64((vector_byte_size - 1) as u64, 8);
                        and_result.bvand(&mask)
                    } else {
                        and_result
                    };

                    // For non-target pairs: the masked result must not equal any literal
                    for lv in &lit_vars {
                        solver.assert(Ast::eq(&masked, lv).not());
                    }
                }
            }

            // Solve
            match solver.check() {
                SatResult::Sat => {
                    let model = solver.get_model().unwrap();

                    // Extract LUT values
                    let mut low_mask = [0u8; 16];
                    let mut high_mask = [0u8; 16];
                    for i in 0..16 {
                        low_mask[i] =
                            model.eval(&low_nibbles[i], true).unwrap().as_u64().unwrap() as u8;
                        high_mask[i] = model
                            .eval(&high_nibbles[i], true)
                            .unwrap()
                            .as_u64()
                            .unwrap() as u8;
                    }

                    // Extract literal assignments
                    let mut literal_map = Vec::new();
                    let mut name_literal_map = std::collections::HashMap::new();
                    for (lit_idx, literal) in group.literals.iter().enumerate() {
                        let lit_val = model
                            .eval(&lit_vars[lit_idx], true)
                            .unwrap()
                            .as_u64()
                            .unwrap() as u8;
                        name_literal_map.insert(literal.name.clone(), lit_val);
                        for &target_byte in &literal.chars {
                            literal_map.push((target_byte, lit_val));
                        }
                    }

                    let mask = AsciiFindMask {
                        low_nibble_mask: low_mask,
                        high_nibble_mask: high_mask,
                        literal_map,
                        name_literal_map,
                    };

                    // Z3 answering "sat" only means our constraint system is
                    // satisfiable — not that we encoded the right one. Check the
                    // model against all 256 bytes before letting it out.
                    mask.verify_detailed(vector_byte_size)
                        .map_err(SolveError::VerificationFailed)?;

                    Ok(mask)
                }
                SatResult::Unsat => Err(SolveError::Unsatisfiable),
                SatResult::Unknown => Err(SolveError::Timeout),
            }
        })
    }

    /// Solve with auto-split: if a single group fails, partition and recurse.
    ///
    /// # Arguments
    /// * `used_literals` - Already-used literal byte values
    /// * `vector_byte_size` - Platform vector width in bytes
    /// * `literals` - All literals to solve (may be split across groups)
    ///
    /// # Returns
    /// One or more `AsciiFindMask` results covering all literals.
    pub fn solve_with_auto_split(
        used_literals: &[u8],
        vector_byte_size: usize,
        literals: &[ByteLiteral],
    ) -> Result<Vec<AsciiFindMask>, SolveError> {
        let group = AsciiLiteralGroup::new(literals.to_vec());

        // Try solving as a single group first
        match Self::solve(used_literals, vector_byte_size, &[group]) {
            Ok(masks) => Ok(masks),
            Err(z3_err) => {
                // Z3 failed — unsatisfiable, out of time, or (in principle) a
                // model that did not verify. The disjoint construction below is
                // deterministic and always succeeds at or under its capacity, so
                // try it before paying for a split. This is what makes small
                // configurations build-independent of Z3's mood.
                if let Ok(mask) = Self::solve_disjoint(used_literals, vector_byte_size, literals) {
                    return Ok(vec![mask]);
                }
                if literals.len() == 1 {
                    return Err(z3_err);
                }
                // Split in half and recurse
                let mid = literals.len() / 2;
                let (left, right) = literals.split_at(mid);

                let mut left_masks =
                    Self::solve_with_auto_split(used_literals, vector_byte_size, left)?;

                // Collect used literals from left solve
                let mut extended_used = used_literals.to_vec();
                for mask in &left_masks {
                    for &(_, lit) in &mask.literal_map {
                        if !extended_used.contains(&lit) {
                            extended_used.push(lit);
                        }
                    }
                }

                let right_masks =
                    Self::solve_with_auto_split(&extended_used, vector_byte_size, right)?;

                left_masks.extend(right_masks);
                Ok(left_masks)
            }
        }
    }

    /// Deterministic fallback: give every literal a pairwise bit-disjoint value
    /// and OR those bits into the nibble slots its target bytes occupy.
    ///
    /// For a target byte `b = (hi, lo)` the AND `low[lo] & high[hi]` is exactly
    /// that literal's bit, and every other nibble pair intersects to zero — so
    /// the construction has no cross-terms at all and needs no search. It holds
    /// for *any* byte set whenever each literal carries a single target byte,
    /// which is the case for every codepoint entry: `collect_per_round_literals`
    /// hands each round one byte per literal name. A literal carrying several
    /// bytes (an ASCII `Codepoints` group) can produce spurious intersections,
    /// so the result is verified before it is returned either way.
    ///
    /// Capacity is the count of unused single-bit values below
    /// `vector_byte_size`: 6 on AVX-512, 5 on AVX2, 4 on NEON.
    pub fn solve_disjoint(
        used_literals: &[u8],
        vector_byte_size: usize,
        literals: &[ByteLiteral],
    ) -> Result<AsciiFindMask, SolveError> {
        let available: Vec<u8> = (0..8)
            .map(|bit| 1u8 << bit)
            .filter(|v| (*v as usize) < vector_byte_size && !used_literals.contains(v))
            .collect();

        if literals.len() > available.len() {
            return Err(SolveError::DisjointCapacity {
                needed: literals.len(),
                available: available.len(),
            });
        }

        let mut low_nibble_mask = [0u8; 16];
        let mut high_nibble_mask = [0u8; 16];
        let mut literal_map = Vec::new();
        let mut name_literal_map = std::collections::HashMap::new();

        for (literal, &value) in literals.iter().zip(available.iter()) {
            name_literal_map.insert(literal.name.clone(), value);
            for &target_byte in &literal.chars {
                low_nibble_mask[(target_byte & 0x0F) as usize] |= value;
                high_nibble_mask[(target_byte >> 4) as usize] |= value;
                literal_map.push((target_byte, value));
            }
        }

        let mask = AsciiFindMask {
            low_nibble_mask,
            high_nibble_mask,
            literal_map,
            name_literal_map,
        };
        mask.verify_detailed(vector_byte_size)
            .map_err(SolveError::VerificationFailed)?;
        Ok(mask)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn solve_single_literal() {
        let group = AsciiLiteralGroup::new(vec![ByteLiteral::new("comma", vec![b','])]);
        let masks = LiteralCompiler::solve(&[], 32, &[group]).unwrap();
        assert_eq!(masks.len(), 1);
        assert!(masks[0].verify_with_mask(32), "LUT verification failed");
    }

    #[test]
    fn solve_multiple_literals() {
        let group = AsciiLiteralGroup::new(vec![
            ByteLiteral::new("comma", vec![b',']),
            ByteLiteral::new("quote", vec![b'"']),
            ByteLiteral::new("newline", vec![b'\n']),
        ]);
        let masks = LiteralCompiler::solve(&[], 32, &[group]).unwrap();
        assert_eq!(masks.len(), 1);
        assert!(masks[0].verify());

        // Verify distinct literals assigned
        let lits: Vec<u8> = masks[0].literal_map.iter().map(|&(_, l)| l).collect();
        let unique: std::collections::HashSet<u8> = lits.iter().copied().collect();
        assert_eq!(unique.len(), 3);
    }

    #[test]
    fn solve_csv_characters() {
        let group = AsciiLiteralGroup::new(vec![
            ByteLiteral::new("comma", vec![b',']),
            ByteLiteral::new("quote", vec![b'"']),
            ByteLiteral::new("lf", vec![b'\n']),
            ByteLiteral::new("cr", vec![b'\r']),
        ]);
        let masks = LiteralCompiler::solve(&[], 32, &[group]).unwrap();
        assert_eq!(masks.len(), 1);
        assert!(masks[0].verify());
    }

    #[test]
    fn solve_respects_used_literals() {
        let group = AsciiLiteralGroup::new(vec![ByteLiteral::new("tab", vec![b'\t'])]);
        let used = vec![1, 2, 3]; // block first 3 literal values
        let masks = LiteralCompiler::solve(&used, 32, &[group]).unwrap();
        assert!(masks[0].verify());

        // Assigned literal should not be 1, 2, or 3
        let lit = masks[0].literal_map[0].1;
        assert!(
            !used.contains(&lit),
            "literal {lit} conflicts with used set"
        );
    }

    #[test]
    fn solve_8_ascii_targets() {
        let group = AsciiLiteralGroup::new(vec![ByteLiteral::new(
            "mixed",
            vec![b',', b'"', b'\n', b'\r', b'\t', b' ', b';', b'|'],
        )]);
        let masks = LiteralCompiler::solve(&[], 32, &[group]).unwrap();
        assert!(masks[0].verify());
    }

    #[test]
    fn auto_split_two_groups() {
        // 2 groups of 8 chars each — first group should solve alone,
        // second uses auto-split since its literals conflict with the first
        let group1 = ByteLiteral::new(
            "set1",
            vec![b',', b'"', b'\n', b'\r', b'\t', b' ', b';', b'|'],
        );
        let group2 = ByteLiteral::new("set2", vec![b':', b'=', b'+', b'-', b'*', b'/', b'(', b')']);

        let masks = LiteralCompiler::solve_with_auto_split(&[], 32, &[group1, group2]).unwrap();

        for mask in &masks {
            assert!(
                mask.verify_with_mask(32),
                "LUT verification failed after auto-split"
            );
        }

        // All 16 targets should be covered
        let covered: std::collections::HashSet<u8> = masks
            .iter()
            .flat_map(|m| m.literal_map.iter().map(|&(t, _)| t))
            .collect();
        assert_eq!(covered.len(), 16);
    }

    #[test]
    fn auto_split_many_literals() {
        // 15 individual literals — will require multiple groups via auto-split
        let literals: Vec<ByteLiteral> = (b'A'..=b'O')
            .map(|c| ByteLiteral::new(format!("ch_{}", c as char), vec![c]))
            .collect();

        let masks = LiteralCompiler::solve_with_auto_split(&[], 32, &literals).unwrap();

        for mask in &masks {
            assert!(mask.verify_with_mask(32), "LUT verification failed");
        }

        let covered: std::collections::HashSet<u8> = masks
            .iter()
            .flat_map(|m| m.literal_map.iter().map(|&(t, _)| t))
            .collect();
        assert_eq!(covered.len(), 15, "not all targets covered");
    }

    /// SOLVE-001: a solved LUT that is quietly corrupted must be rejected.
    /// Without this, a verifier that always returned `Ok` would be
    /// indistinguishable from a working one.
    #[test]
    fn verifier_rejects_corrupted_lut() {
        let group = AsciiLiteralGroup::new(vec![ByteLiteral::new("comma", vec![b','])]);
        let mut mask = LiteralCompiler::solve(&[], 32, &[group]).unwrap().remove(0);
        assert!(mask.verify_detailed(32).is_ok());

        mask.low_nibble_mask[(b',' & 0x0F) as usize] = 0;
        let err = mask.verify_detailed(32).unwrap_err();
        assert!(err.contains("0x2c"), "unexpected diagnosis: {err}");
    }

    /// SOLVE-003: the disjoint construction's guaranteed capacity is the number
    /// of single-bit values below the vector width — 6 / 5 / 4 for AVX-512 /
    /// AVX2 / NEON. This is the floor below which a build never depends on Z3.
    #[test]
    fn disjoint_capacity_matches_vector_width() {
        for (vbs, expected) in [(64usize, 6usize), (32, 5), (16, 4)] {
            let literals: Vec<ByteLiteral> = (0..expected)
                .map(|i| ByteLiteral::new(format!("l{i}"), vec![0x41 + i as u8]))
                .collect();
            assert!(
                LiteralCompiler::solve_disjoint(&[], vbs, &literals).is_ok(),
                "vbs {vbs} should solve {expected} literals"
            );

            let one_too_many: Vec<ByteLiteral> = (0..expected + 1)
                .map(|i| ByteLiteral::new(format!("l{i}"), vec![0x41 + i as u8]))
                .collect();
            assert_eq!(
                LiteralCompiler::solve_disjoint(&[], vbs, &one_too_many).unwrap_err(),
                SolveError::DisjointCapacity {
                    needed: expected + 1,
                    available: expected,
                },
                "vbs {vbs} should refuse {} literals",
                expected + 1
            );
        }
    }

    /// SOLVE-003: the floor is universal — it holds for *any* set of target
    /// bytes, not just convenient ones. Sweep pseudo-random 6-byte sets and
    /// assert every one solves and verifies without Z3.
    #[test]
    fn disjoint_solves_any_byte_set_within_capacity() {
        let mut state: u64 = 0x2545_F491_4F6C_DD1D;
        let mut next = || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };

        for _ in 0..5_000 {
            let mut bytes: Vec<u8> = Vec::new();
            while bytes.len() < 6 {
                let b = (next() & 0xFF) as u8;
                if !bytes.contains(&b) {
                    bytes.push(b);
                }
            }
            let literals: Vec<ByteLiteral> = bytes
                .iter()
                .enumerate()
                .map(|(i, &b)| ByteLiteral::new(format!("l{i}"), vec![b]))
                .collect();

            let mask = LiteralCompiler::solve_disjoint(&[], 64, &literals)
                .unwrap_or_else(|e| panic!("disjoint failed on {bytes:02x?}: {e}"));
            mask.verify_detailed(64)
                .unwrap_or_else(|e| panic!("unverified mask for {bytes:02x?}: {e}"));

            // Every non-target must AND to exactly zero under this construction.
            let targets: std::collections::HashSet<u8> = bytes.iter().copied().collect();
            for byte in 0u16..=255 {
                let b = byte as u8;
                if targets.contains(&b) {
                    continue;
                }
                let lo = mask.low_nibble_mask[(b & 0x0F) as usize];
                let hi = mask.high_nibble_mask[(b >> 4) as usize];
                assert_eq!(lo & hi, 0, "cross-term at 0x{b:02x} for {bytes:02x?}");
            }
        }
    }

    /// SOLVE-007: the fallback must respect literals already handed out.
    #[test]
    fn disjoint_skips_used_literals() {
        let literals: Vec<ByteLiteral> = (0..4)
            .map(|i| ByteLiteral::new(format!("l{i}"), vec![0x41 + i as u8]))
            .collect();
        let mask = LiteralCompiler::solve_disjoint(&[1, 4], 64, &literals).unwrap();
        for &(_, lit) in &mask.literal_map {
            assert!(lit != 1 && lit != 4, "reused literal 0x{lit:02x}");
        }
        mask.verify_detailed(64).unwrap();
    }

    /// SOLVE-001: whatever path produced them — Z3, the disjoint fallback, or a
    /// split — every mask leaving the compiler verifies.
    #[test]
    fn auto_split_output_always_verifies() {
        let literals: Vec<ByteLiteral> = "abcdefghij,.\"; \n"
            .bytes()
            .enumerate()
            .map(|(i, b)| ByteLiteral::new(format!("l{i}"), vec![b]))
            .collect();
        let masks = LiteralCompiler::solve_with_auto_split(&[], 64, &literals).unwrap();
        for mask in &masks {
            mask.verify_detailed(64).unwrap();
        }
    }
}
