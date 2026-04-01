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
pub mod utf8;
pub mod vpa;

pub use engine::{BuildResult, FindEngine, MatchStorage, MatchView};
pub use error::FindCharsError;
pub use kernel::SimdBackend;

use std::collections::HashMap;

use engine::EngineData;
use findchars_solver::{ByteLiteral, LiteralCompiler};

/// Engine builder for configuring character detection targets.
pub struct EngineBuilder {
    entries: Vec<BuilderEntry>,
    backend: Option<SimdBackend>,
    filter_fn: Option<vpa::FilterFn>,
    filter_literal_names: Vec<String>,
    inline_filter: engine::InlineFilter,
}

enum BuilderEntry {
    Codepoints { name: String, codepoints: Vec<u8> },
    Codepoint { name: String, codepoint: u32 },
    Range { name: String, from: u8, to: u8 },
}

/// Intermediate representation of a codepoint entry after UTF-8 encoding.
struct ResolvedEntry {
    name: String,
    utf8_bytes: Vec<u8>,
    is_ascii_group: bool,
}

impl EngineBuilder {
    pub fn new() -> Self {
        Self {
            entries: Vec::new(),
            backend: None,
            filter_fn: None,
            filter_literal_names: Vec::new(),
            inline_filter: engine::InlineFilter::None,
        }
    }

    /// Add a group of ASCII codepoints to detect (shared literal ID).
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

    /// Add a contiguous byte range to detect (1 literal ID, compare-and-mask).
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

    /// Set an inline SIMD filter for the AVX-512 fast path.
    ///
    /// This bypasses the generic scalar callback and operates directly on
    /// SIMD registers. Currently only `CsvQuote` is supported.
    pub fn inline_filter(mut self, filter: engine::InlineFilter) -> Self {
        self.inline_filter = filter;
        self
    }

    /// Set a chunk filter that runs between SIMD detection and position decode.
    ///
    /// The filter function receives the accumulator (mutable byte slice),
    /// mutable state for cross-chunk carry, and literal byte bindings.
    /// `literal_names` specifies which configured target names are bound
    /// to the filter's `literals` parameter (in order).
    pub fn chunk_filter(mut self, filter_fn: vpa::FilterFn, literal_names: &[&str]) -> Self {
        self.filter_fn = Some(filter_fn);
        self.filter_literal_names = literal_names.iter().map(|s| s.to_string()).collect();
        self
    }

    /// Build the engine by solving constraints and selecting the best SIMD backend.
    pub fn build(self) -> Result<BuildResult, FindCharsError> {
        let backend = self.backend.unwrap_or_else(SimdBackend::detect);
        let vbs = backend.vector_byte_size();

        // Resolve entries: encode codepoints to UTF-8, separate ranges
        let mut resolved: Vec<ResolvedEntry> = Vec::new();
        let mut range_entries: Vec<(String, u8, u8)> = Vec::new();

        for entry in &self.entries {
            match entry {
                BuilderEntry::Codepoints { name, codepoints } => {
                    resolved.push(ResolvedEntry {
                        name: name.clone(),
                        utf8_bytes: codepoints.clone(),
                        is_ascii_group: true,
                    });
                }
                BuilderEntry::Codepoint { name, codepoint } => {
                    let (bytes, len) = utf8::encode_utf8(*codepoint);
                    resolved.push(ResolvedEntry {
                        name: name.clone(),
                        utf8_bytes: bytes[..len].to_vec(),
                        is_ascii_group: len == 1,
                    });
                }
                BuilderEntry::Range { name, from, to } => {
                    range_entries.push((name.clone(), *from, *to));
                }
            }
        }

        // Determine max rounds from longest UTF-8 sequence
        let max_rounds = resolved
            .iter()
            .map(|e| if e.is_ascii_group { 1 } else { e.utf8_bytes.len() })
            .max()
            .unwrap_or(1);

        // Check namespace limits
        let total_literals = resolved.len() + range_entries.len();
        let max_lits = backend.max_literals();
        if total_literals > max_lits {
            return Err(FindCharsError::NamespaceExceeded {
                configured: total_literals,
                max: max_lits,
            });
        }

        // Assign canonical literal names per round (shared lead bytes get same name)
        let literal_names = assign_literal_names(&resolved, max_rounds);

        // Collect per-round ByteLiterals for Z3
        let per_round_literals = collect_per_round_literals(&resolved, &literal_names, max_rounds);

        // Solve all rounds with Z3 + auto-split
        let mut all_used: Vec<u8> = Vec::new();
        let mut round_mask_groups: Vec<Vec<findchars_solver::AsciiFindMask>> = Vec::new();

        for round_lits in &per_round_literals {
            if round_lits.is_empty() {
                round_mask_groups.push(Vec::new());
                continue;
            }
            let masks = LiteralCompiler::solve_with_auto_split(&all_used, vbs, round_lits)
                .map_err(FindCharsError::SolverFailed)?;
            // Add newly assigned literals to used set
            for mask in &masks {
                for &(_, lit) in &mask.literal_map {
                    if !all_used.contains(&lit) {
                        all_used.push(lit);
                    }
                }
            }
            round_mask_groups.push(masks);
        }

        // Build flat group arrays and round mapping
        let mut low_luts = Vec::new();
        let mut high_luts = Vec::new();
        let mut clean_luts = Vec::new();
        let mut group_literals = Vec::new();
        let mut round_group_start = Vec::new();
        let mut round_group_count = Vec::new();

        for masks in &round_mask_groups {
            round_group_start.push(low_luts.len());
            round_group_count.push(masks.len());

            for mask in masks {
                low_luts.push(mask.low_nibble_mask);
                high_luts.push(mask.high_nibble_mask);

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
        }

        // Build literal map and charspecs
        let mut literal_map: HashMap<String, u8> = HashMap::new();
        let mut charspec_round_lits = Vec::new();
        let mut charspec_byte_lens = Vec::new();
        let mut charspec_final_lits = Vec::new();

        for (e, entry) in resolved.iter().enumerate() {
            if entry.is_ascii_group {
                // ASCII: literal from round 0
                if let Some(ref name) = literal_names[e][0]
                    && let Some(lit) = find_literal_byte(&round_mask_groups, 0, name) {
                        literal_map.insert(entry.name.clone(), lit);
                    }
            } else {
                // Multi-byte: collect per-round literals, build charspec
                let byte_len = entry.utf8_bytes.len();
                let mut rl = Vec::with_capacity(byte_len);
                let mut final_lit = 0u8;
                for r in 0..byte_len {
                    if let Some(name) = &literal_names[e][r]
                        && let Some(lit) = find_literal_byte(&round_mask_groups, r, name) {
                            rl.push(lit);
                            final_lit = lit; // last round's literal is the output
                        }
                }
                if rl.len() == byte_len {
                    charspec_byte_lens.push(byte_len);
                    charspec_round_lits.push(rl);
                    charspec_final_lits.push(final_lit);
                    literal_map.insert(entry.name.clone(), final_lit);
                }
            }
        }

        // Build range operations
        let mut used_lits: Vec<u8> = literal_map.values().copied().collect();
        let mut ranges = Vec::new();
        for (name, from, to) in range_entries {
            let range_lit = allocate_literal(&used_lits, vbs)
                .ok_or(FindCharsError::NamespaceExceeded {
                    configured: total_literals,
                    max: max_lits,
                })?;
            used_lits.push(range_lit);
            ranges.push((from, to, range_lit));
            literal_map.insert(name, range_lit);
        }

        // Pre-broadcast SIMD vectors for AVX-512
        let low_luts_512: Vec<[u8; 64]> = low_luts.iter().map(replicate_4x).collect();
        let high_luts_512: Vec<[u8; 64]> = high_luts.iter().map(replicate_4x).collect();
        let clean_luts_512: Vec<[u8; 64]> = clean_luts.iter().map(|cl| {
            // Build 64-byte vpermb LUT: index by (raw & 0x3F) → literal or 0
            let mut lut64 = [0u8; 64];
            for i in 0..64usize.min(vbs) {
                lut64[i] = cl[i]; // cl[i] is 0 for non-literals, literal value for literals
            }
            lut64
        }).collect();
        let ranges_512: Vec<([u8; 64], [u8; 64], [u8; 64])> = ranges.iter().map(|&(lo, hi, lit)| {
            ([lo; 64], [hi; 64], [lit; 64])
        }).collect();

        let engine_data = EngineData {
            low_luts,
            high_luts,
            clean_luts,
            group_literals,
            low_luts_512,
            high_luts_512,
            clean_luts_512,
            ranges_512,
            round_group_start,
            round_group_count,
            max_rounds,
            charspec_round_lits,
            charspec_byte_lens,
            charspec_final_lits,
            ranges,
            filter_fn: self.filter_fn.unwrap_or(vpa::no_op_filter),
            filter_literals: self.filter_literal_names.iter()
                .filter_map(|name| literal_map.get(name).copied())
                .collect(),
            inline_filter: self.inline_filter,
            vector_byte_size: vbs,
        };

        let find_fn = backend.find_fn();
        let engine = FindEngine::new(engine_data, find_fn);
        Ok(BuildResult { engine, literals: literal_map })
    }
}

impl Default for EngineBuilder {
    fn default() -> Self {
        Self::new()
    }
}

/// Assign canonical literal names per round.
/// Returns `names[entry][round]` — Some(canonical_name) or None.
fn assign_literal_names(entries: &[ResolvedEntry], max_rounds: usize) -> Vec<Vec<Option<String>>> {
    let mut names = vec![vec![None; max_rounds]; entries.len()];

    for r in 0..max_rounds {
        let mut byte_to_name: HashMap<u8, String> = HashMap::new();

        for (e, entry) in entries.iter().enumerate() {
            if entry.is_ascii_group {
                if r == 0 {
                    names[e][0] = Some(format!("{}_r0", entry.name));
                }
            } else if r < entry.utf8_bytes.len() {
                let b = entry.utf8_bytes[r];
                let canonical = byte_to_name
                    .entry(b)
                    .or_insert_with(|| format!("mb_r{r}_0x{b:02x}"))
                    .clone();
                names[e][r] = Some(canonical);
            }
        }
    }

    names
}

/// Collect per-round ByteLiterals for Z3 solving.
fn collect_per_round_literals(
    entries: &[ResolvedEntry],
    literal_names: &[Vec<Option<String>>],
    max_rounds: usize,
) -> Vec<Vec<ByteLiteral>> {
    let mut per_round: Vec<Vec<ByteLiteral>> = (0..max_rounds).map(|_| Vec::new()).collect();
    let mut seen_per_round: Vec<std::collections::HashSet<String>> =
        (0..max_rounds).map(|_| std::collections::HashSet::new()).collect();

    for (e, entry) in entries.iter().enumerate() {
        if entry.is_ascii_group {
            if let Some(name) = &literal_names[e][0]
                && seen_per_round[0].insert(name.clone()) {
                    per_round[0].push(ByteLiteral::new(name, entry.utf8_bytes.clone()));
                }
        } else {
            for r in 0..entry.utf8_bytes.len() {
                if let Some(name) = &literal_names[e][r]
                    && seen_per_round[r].insert(name.clone()) {
                        per_round[r].push(ByteLiteral::new(name, vec![entry.utf8_bytes[r]]));
                    }
            }
        }
    }

    per_round
}

/// Find the literal byte assigned to a named literal in a given round.
fn find_literal_byte(
    round_mask_groups: &[Vec<findchars_solver::AsciiFindMask>],
    round: usize,
    name: &str,
) -> Option<u8> {
    if round >= round_mask_groups.len() {
        return None;
    }
    for mask in &round_mask_groups[round] {
        if let Some(&lit) = mask.name_literal_map.get(name) {
            return Some(lit);
        }
    }
    None
}

/// Replicate a 16-byte array 4x to fill 64 bytes (for AVX-512 vpermb).
fn replicate_4x(src: &[u8; 16]) -> [u8; 64] {
    let mut out = [0u8; 64];
    out[0..16].copy_from_slice(src);
    out[16..32].copy_from_slice(src);
    out[32..48].copy_from_slice(src);
    out[48..64].copy_from_slice(src);
    out
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
