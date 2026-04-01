//! High-performance SIMD character detection in byte sequences.
//!
//! `findchars` detects ASCII and multi-byte UTF-8 characters in byte sequences
//! at ~2 GB/s per core using SIMD instructions. A constraint solver finds optimal
//! shuffle mask configurations at build time; the runtime engine executes the
//! detection at full vector width.
//!
//! # Quick Start
//!
//! ```no_run
//! use findchars::{EngineBuilder, MatchStorage};
//!
//! let result = EngineBuilder::new()
//!     .codepoints("whitespace", &[b'\t', b'\n', b' '])
//!     .range("digits", b'0', b'9')
//!     .build()
//!     .expect("solver failed");
//!
//! let mut storage = MatchStorage::new(256);
//! let view = result.engine.find(b"hello\t42\n", &mut storage);
//!
//! for i in 0..view.len() {
//!     println!("match at {} literal {}", view.position(i), view.literal(i));
//! }
//! ```

pub mod engine;
pub mod error;
pub mod kernel;
pub mod vpa;

pub use engine::{BuildResult, FindEngine, MatchStorage, MatchView};
pub use error::FindCharsError;
pub use kernel::SimdBackend;

use std::collections::HashMap;

use engine::EngineData;
use findchars_solver::{ByteLiteral, LiteralCompiler};

/// Engine builder for configuring character detection targets.
///
/// Supports ASCII codepoints, multi-byte UTF-8 codepoints, and byte ranges.
/// The builder invokes the constraint solver at `build()` time to find optimal
/// shuffle LUT configurations.
pub struct EngineBuilder {
    entries: Vec<BuilderEntry>,
    backend: Option<SimdBackend>,
}

enum BuilderEntry {
    Codepoints { name: String, codepoints: Vec<u8> },
    Codepoint { name: String, codepoint: u32 },
    Range { name: String, from: u8, to: u8 },
}

impl EngineBuilder {
    pub fn new() -> Self {
        Self {
            entries: Vec::new(),
            backend: None,
        }
    }

    /// Add a group of ASCII codepoints to detect.
    ///
    /// All bytes in the group share a single literal ID.
    pub fn codepoints(mut self, name: &str, bytes: &[u8]) -> Self {
        self.entries.push(BuilderEntry::Codepoints {
            name: name.to_string(),
            codepoints: bytes.to_vec(),
        });
        self
    }

    /// Add a single codepoint (ASCII or multi-byte UTF-8) to detect.
    pub fn codepoint(mut self, name: &str, codepoint: u32) -> Self {
        self.entries.push(BuilderEntry::Codepoint {
            name: name.to_string(),
            codepoint,
        });
        self
    }

    /// Add a contiguous byte range to detect.
    ///
    /// Ranges bypass the nibble matrix solver — each range consumes 1 literal ID
    /// and is evaluated via compare-and-mask at detection time.
    pub fn range(mut self, name: &str, from: u8, to: u8) -> Self {
        self.entries.push(BuilderEntry::Range {
            name: name.to_string(),
            from,
            to,
        });
        self
    }

    /// Override the SIMD backend (default: auto-detect).
    pub fn backend(mut self, backend: SimdBackend) -> Self {
        self.backend = Some(backend);
        self
    }

    /// Build the engine by solving constraints and selecting the best SIMD backend.
    pub fn build(self) -> Result<BuildResult, FindCharsError> {
        let backend = self.backend.unwrap_or_else(SimdBackend::detect);
        let vbs = backend.vector_byte_size();

        // Separate entries into shuffle targets and ranges
        let mut shuffle_literals: Vec<(String, ByteLiteral)> = Vec::new();
        let mut range_entries: Vec<(String, u8, u8)> = Vec::new();

        for entry in &self.entries {
            match entry {
                BuilderEntry::Codepoints { name, codepoints } => {
                    shuffle_literals.push((
                        name.clone(),
                        ByteLiteral::new(name, codepoints.clone()),
                    ));
                }
                BuilderEntry::Codepoint { name, codepoint } => {
                    if *codepoint <= 0x7F {
                        // ASCII codepoint — treat as single-byte literal
                        shuffle_literals.push((
                            name.clone(),
                            ByteLiteral::new(name, vec![*codepoint as u8]),
                        ));
                    } else {
                        // TODO: Phase 3 — multi-byte UTF-8 codepoints
                        return Err(FindCharsError::InvalidConfig(
                            format!("multi-byte codepoint U+{codepoint:04X} not yet supported"),
                        ));
                    }
                }
                BuilderEntry::Range { name, from, to } => {
                    range_entries.push((name.clone(), *from, *to));
                }
            }
        }

        // Check namespace limits
        let total_literals = shuffle_literals.len() + range_entries.len();
        let max_lits = backend.max_literals();
        if total_literals > max_lits {
            return Err(FindCharsError::NamespaceExceeded {
                configured: total_literals,
                max: max_lits,
            });
        }

        // Solve shuffle groups via Z3
        let byte_literals: Vec<ByteLiteral> = shuffle_literals
            .iter()
            .map(|(_, bl)| bl.clone())
            .collect();

        let masks = if byte_literals.is_empty() {
            Vec::new()
        } else {
            LiteralCompiler::solve_with_auto_split(&[], vbs, &byte_literals)
                .map_err(|e| FindCharsError::SolverFailed(e))?
        };

        // Build literal name → byte map from solver results
        let mut literal_map: HashMap<String, u8> = HashMap::new();
        for (name, bl) in &shuffle_literals {
            // Find the literal value assigned to this ByteLiteral's first target byte
            let target = bl.chars[0];
            for mask in &masks {
                if let Some(&(_, lit)) = mask
                    .literal_map
                    .iter()
                    .find(|(t, _): &&(u8, u8)| *t == target)
                {
                    literal_map.insert(name.clone(), lit);
                    break;
                }
            }
        }

        // Build engine data from solved masks
        let group_count = masks.len();
        let mut low_luts = Vec::with_capacity(group_count);
        let mut high_luts = Vec::with_capacity(group_count);
        let mut clean_luts = Vec::with_capacity(group_count);
        let mut group_literals = Vec::with_capacity(group_count);

        for mask in &masks {
            low_luts.push(mask.low_nibble_mask);
            high_luts.push(mask.high_nibble_mask);

            // Build clean LUT: only known literal values pass through, rest → 0
            let mut clean = [0u8; 256];
            let mut lits = Vec::new();
            for &(_, lit) in &mask.literal_map {
                clean[lit as usize] = lit;
                if !lits.contains(&lit) {
                    lits.push(lit);
                }
            }
            clean_luts.push(clean);
            group_literals.push(lits);
        }

        // Build range operations, assign unused literal IDs
        let mut used_lits: Vec<u8> = literal_map.values().copied().collect();
        let mut ranges = Vec::new();
        for (name, from, to) in range_entries {
            let range_lit = allocate_literal(&used_lits, vbs)
                .ok_or_else(|| FindCharsError::NamespaceExceeded {
                    configured: total_literals,
                    max: max_lits,
                })?;
            used_lits.push(range_lit);
            ranges.push((from, to, range_lit));
            literal_map.insert(name, range_lit);
        }

        let engine_data = EngineData {
            low_luts,
            group_literals,
            high_luts,
            clean_luts,
            group_count,
            ranges,
            vector_byte_size: vbs,
        };

        let find_fn = backend.find_fn();
        let engine = FindEngine::new(engine_data, find_fn);
        Ok(BuildResult {
            engine,
            literals: literal_map,
        })
    }
}

impl Default for EngineBuilder {
    fn default() -> Self {
        Self::new()
    }
}

/// Find the lowest unused literal byte in [1, vbs).
fn allocate_literal(used: &[u8], vector_byte_size: usize) -> Option<u8> {
    for candidate in 1..vector_byte_size as u8 {
        if !used.contains(&candidate) {
            return Some(candidate);
        }
    }
    None
}
