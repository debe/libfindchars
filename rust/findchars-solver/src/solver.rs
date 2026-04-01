//! Z3-based nibble matrix solver.
//!
//! Solves for two 16-entry LUT vectors whose AND uniquely identifies each
//! target character. Uses Z3's bitvector theory for constraint solving.

use z3::ast::{Ast, BV};
use z3::{Config, Context, SatResult, Solver};

use crate::literal::{AsciiFindMask, AsciiLiteralGroup, ByteLiteral};

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
    ) -> Result<Vec<AsciiFindMask>, String> {
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
    ) -> Result<AsciiFindMask, String> {
        let cfg = Config::new();
        let ctx = Context::new(&cfg);
        let solver = Solver::new(&ctx);
        // Timeout: 5 seconds per solve attempt. If Z3 can't solve in 5s,
        // the group is likely too large and should be split.
        let mut params = z3::Params::new(&ctx);
        params.set_u32("timeout", 5000);
        solver.set_params(&params);

        let num_literals = group.literals.len();

        // Create Z3 bitvector variables for the two 16-entry LUTs
        let low_nibbles: Vec<BV> = (0..16)
            .map(|i| BV::new_const(&ctx, format!("lo_{i}"), 8))
            .collect();
        let high_nibbles: Vec<BV> = (0..16)
            .map(|i| BV::new_const(&ctx, format!("hi_{i}"), 8))
            .collect();

        // Create Z3 variables for each literal's assigned byte value
        let lit_vars: Vec<BV> = (0..num_literals)
            .map(|i| BV::new_const(&ctx, format!("lit_{i}"), 8))
            .collect();

        // Constraint: each literal > 0
        let zero = BV::from_u64(&ctx, 0, 8);
        for lv in &lit_vars {
            solver.assert(&lv.bvugt(&zero));
        }

        // Constraint: each literal < vector_byte_size
        let max_lit = BV::from_u64(&ctx, vector_byte_size as u64, 8);
        for lv in &lit_vars {
            solver.assert(&lv.bvult(&max_lit));
        }

        // Constraint: literals are distinct
        for i in 0..num_literals {
            for j in (i + 1)..num_literals {
                solver.assert(&lit_vars[i]._eq(&lit_vars[j]).not());
            }
        }

        // Constraint: literals not in used set
        for lv in &lit_vars {
            for &used in used_literals {
                let used_bv = BV::from_u64(&ctx, used as u64, 8);
                solver.assert(&lv._eq(&used_bv).not());
            }
        }

        // Matching constraints: for each literal and each target byte,
        // lo[byte & 0xF] AND hi[byte >> 4] == literal_value
        for (lit_idx, literal) in group.literals.iter().enumerate() {
            for &target_byte in &literal.chars {
                let lo_idx = (target_byte & 0x0F) as usize;
                let hi_idx = ((target_byte >> 4) & 0x0F) as usize;
                let and_result = low_nibbles[lo_idx].bvand(&high_nibbles[hi_idx]);
                solver.assert(&and_result._eq(&lit_vars[lit_idx]));
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
                    let mask = BV::from_u64(&ctx, (vector_byte_size - 1) as u64, 8);
                    and_result.bvand(&mask)
                } else {
                    and_result
                };

                // For non-target pairs: the masked result must not equal any literal
                for lv in &lit_vars {
                    solver.assert(&masked._eq(lv).not());
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
                    high_mask[i] =
                        model.eval(&high_nibbles[i], true).unwrap().as_u64().unwrap() as u8;
                }

                // Extract literal assignments
                let mut literal_map = Vec::new();
                for (lit_idx, literal) in group.literals.iter().enumerate() {
                    let lit_val =
                        model.eval(&lit_vars[lit_idx], true).unwrap().as_u64().unwrap() as u8;
                    for &target_byte in &literal.chars {
                        literal_map.push((target_byte, lit_val));
                    }
                }

                Ok(AsciiFindMask {
                    low_nibble_mask: low_mask,
                    high_nibble_mask: high_mask,
                    literal_map,
                })
            }
            SatResult::Unsat => Err("unsatisfiable: no valid LUT pair exists for this group".into()),
            SatResult::Unknown => Err("solver returned unknown".into()),
        }
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
    ) -> Result<Vec<AsciiFindMask>, String> {
        let group = AsciiLiteralGroup::new(literals.to_vec());

        // Try solving as a single group first
        match Self::solve(used_literals, vector_byte_size, &[group]) {
            Ok(masks) => Ok(masks),
            Err(_) if literals.len() > 1 => {
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
            Err(e) => Err(e),
        }
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
        assert!(!used.contains(&lit), "literal {lit} conflicts with used set");
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
        let group1 = ByteLiteral::new("set1", vec![b',', b'"', b'\n', b'\r', b'\t', b' ', b';', b'|']);
        let group2 = ByteLiteral::new("set2", vec![b':', b'=', b'+', b'-', b'*', b'/', b'(', b')']);

        let masks = LiteralCompiler::solve_with_auto_split(&[], 32, &[group1, group2]).unwrap();

        for mask in &masks {
            assert!(mask.verify_with_mask(32), "LUT verification failed after auto-split");
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
}
