use std::collections::HashMap;

/// Result of building an engine via [`EngineBuilder`](crate::EngineBuilder).
pub struct BuildResult {
    /// The detection engine, ready for `find()` calls.
    pub engine: FindEngine,
    /// Mapping from target name to assigned literal byte.
    pub literals: HashMap<String, u8>,
}

/// Internal engine configuration produced by the builder.
pub(crate) struct EngineData {
    /// Per-group low-nibble LUTs (16 entries each).
    pub low_luts: Vec<[u8; 16]>,
    /// Per-group high-nibble LUTs (16 entries each).
    pub high_luts: Vec<[u8; 16]>,
    /// Per-group clean LUTs: maps non-literal AND results to zero.
    /// Index by AND result byte → 0 if not a literal, literal value if it is.
    pub clean_luts: Vec<[u8; 256]>,
    /// Number of shuffle groups.
    pub group_count: usize,
    /// Range operations: (lower bound, upper bound, literal byte).
    pub ranges: Vec<(u8, u8, u8)>,
    /// Logical vector byte size for this engine.
    pub vector_byte_size: usize,
}

/// Type alias for the find function pointer.
type FindFn = fn(&EngineData, &[u8], &mut MatchStorage) -> usize;

/// SIMD character detection engine.
///
/// Detects configured characters in byte sequences and returns their positions.
/// Created via [`EngineBuilder`](crate::EngineBuilder).
///
/// # Thread Safety
///
/// A single engine instance must not be called concurrently from multiple threads.
/// The engine maintains mutable internal state (decode buffers, filter state).
/// Create separate instances for parallel use.
pub struct FindEngine {
    pub(crate) data: EngineData,
    find_fn: FindFn,
}

impl FindEngine {
    pub(crate) fn new(data: EngineData, find_fn: FindFn) -> Self {
        Self { data, find_fn }
    }

    /// Scan `data` for configured characters, writing matches into `storage`.
    ///
    /// Returns an immutable view over the matches found.
    /// Previous results in `storage` are overwritten.
    pub fn find<'s>(&self, data: &[u8], storage: &'s mut MatchStorage) -> MatchView<'s> {
        storage.clear();
        let count = (self.find_fn)(&self.data, data, storage);
        MatchView { storage, len: count }
    }
}

/// Auto-growing dual-buffer storage for match results.
///
/// Holds parallel arrays of match positions (`u32`) and literal identifiers (`u8`).
/// Reusable across `find()` calls — the engine overwrites previous results.
pub struct MatchStorage {
    pub(crate) positions: Vec<u32>,
    pub(crate) literals: Vec<u8>,
}

impl MatchStorage {
    /// Create storage with the given initial capacity.
    pub fn new(capacity: usize) -> Self {
        Self {
            positions: Vec::with_capacity(capacity),
            literals: Vec::with_capacity(capacity),
        }
    }

    /// Ensure capacity for at least `additional` more entries beyond current length.
    #[inline]
    pub fn ensure_capacity(&mut self, additional: usize) {
        self.positions.reserve(additional);
        self.literals.reserve(additional);
    }

    /// Push a match (position + literal) into storage.
    #[inline]
    pub fn push(&mut self, position: u32, literal: u8) {
        self.positions.push(position);
        self.literals.push(literal);
    }

    /// Clear all matches, keeping allocated capacity.
    #[inline]
    pub fn clear(&mut self) {
        self.positions.clear();
        self.literals.clear();
    }

    /// Number of matches currently stored.
    #[inline]
    pub fn len(&self) -> usize {
        self.positions.len()
    }

    /// Whether storage is empty.
    #[inline]
    pub fn is_empty(&self) -> bool {
        self.positions.is_empty()
    }

    /// Access the positions buffer directly.
    #[inline]
    pub fn positions(&self) -> &[u32] {
        &self.positions
    }

    /// Access the literals buffer directly.
    #[inline]
    pub fn literals(&self) -> &[u8] {
        &self.literals
    }
}

/// Immutable view over the results of a single `find()` call.
///
/// Provides indexed access to match positions and literal identifiers
/// stored in the associated [`MatchStorage`].
pub struct MatchView<'a> {
    storage: &'a MatchStorage,
    len: usize,
}

impl MatchView<'_> {
    /// Number of matches found.
    #[inline]
    pub fn len(&self) -> usize {
        self.len
    }

    /// Whether no matches were found.
    #[inline]
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// Byte offset of the match at `index`.
    ///
    /// # Panics
    /// Panics if `index >= len()`.
    #[inline]
    pub fn position(&self, index: usize) -> u32 {
        assert!(index < self.len, "index {index} out of bounds (len {})", self.len);
        self.storage.positions[index]
    }

    /// Literal identifier of the match at `index`.
    ///
    /// # Panics
    /// Panics if `index >= len()`.
    #[inline]
    pub fn literal(&self, index: usize) -> u8 {
        assert!(index < self.len, "index {index} out of bounds (len {})", self.len);
        self.storage.literals[index]
    }
}
