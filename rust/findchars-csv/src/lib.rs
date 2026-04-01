//! SIMD-accelerated CSV parser built on findchars.
//!
//! Two-phase architecture:
//! 1. SIMD scan detects structural characters (delimiter, quote, newline, CR)
//!    with a quote filter suppressing chars inside quoted regions.
//! 2. Linear match walk extracts field and row boundaries.
//!
//! Zero-copy: field boundaries are stored as byte offsets into the original data.
//!
//! # Example
//!
//! ```no_run
//! use findchars_csv::CsvParser;
//!
//! let parser = CsvParser::builder()
//!     .delimiter(b',')
//!     .quote(b'"')
//!     .has_header(true)
//!     .build()
//!     .expect("build failed");
//!
//! let data = b"name,age\nAlice,30\nBob,25\n";
//! let mut storage = findchars::MatchStorage::new(data.len() / 4);
//! let result = parser.parse(data, &mut storage).unwrap();
//!
//! assert_eq!(result.row_count(), 2);
//! assert_eq!(result.row(0).get(0, data), "Alice");
//! ```

mod quote_filter;

use findchars::{EngineBuilder, FindEngine, MatchStorage, SimdBackend};

/// SIMD-accelerated CSV parser.
///
/// The parser does not own storage — callers provide a [`MatchStorage`] via `parse()`.
/// This lets the caller control capacity and reuse storage across calls.
pub struct CsvParser {
    engine: FindEngine,
    quote_lit: u8,
    delim_lit: u8,
    newline_lit: u8,
    cr_lit: u8,
    quote_char: u8,
    has_header: bool,
}

/// Builder for configuring and constructing a [`CsvParser`].
pub struct CsvParserBuilder {
    delimiter: u8,
    quote: u8,
    has_header: bool,
    backend: Option<SimdBackend>,
}

impl CsvParser {
    pub fn builder() -> CsvParserBuilder {
        CsvParserBuilder {
            delimiter: b',',
            quote: b'"',
            has_header: false,
            backend: None,
        }
    }

    /// Parse CSV data, returning a zero-copy result with field boundaries.
    ///
    /// The caller provides a reusable [`MatchStorage`] — use
    /// `MatchStorage::new(data.len() / 4)` as a reasonable starting capacity.
    /// The storage auto-grows if needed.
    pub fn parse(&self, data: &[u8], storage: &mut MatchStorage) -> Result<CsvResult, CsvError> {
        if data.is_empty() {
            return Ok(CsvResult::empty());
        }

        // find() writes matches into storage, returns a view borrowing it.
        // Drop the view immediately to release the borrow, then read
        // the raw slices directly — zero copy.
        let match_count = {
            let view = self.engine.find(data, storage);
            view.len()
        };
        // Now storage is no longer borrowed — read slices directly
        let positions = &storage.positions()[..match_count];
        let literals = &storage.literals()[..match_count];

        self.walk_matches_raw(data, positions, literals)
    }

    fn walk_matches_raw(
        &self,
        data: &[u8],
        positions: &[u32],
        match_literals: &[u8],
    ) -> Result<CsvResult, CsvError> {
        let match_count = positions.len();
        let data_size = data.len();

        let mut row_capacity = 1usize;
        for &lit in match_literals {
            if lit == self.newline_lit || lit == self.cr_lit {
                row_capacity += 1;
            }
        }

        let field_capacity = match_count + 1;
        let mut field_starts: Vec<u32> = Vec::with_capacity(field_capacity);
        let mut field_ends: Vec<u32> = Vec::with_capacity(field_capacity);
        let mut field_flags: Vec<u8> = Vec::with_capacity(field_capacity);
        let mut row_field_offset: Vec<usize> = Vec::with_capacity(row_capacity + 1);
        row_field_offset.push(0);

        let mut field_start = 0u32;
        let mut field_is_quoted = false;
        let mut row_count = 0usize;

        for m in 0..match_count {
            let pos = positions[m];
            let lit = match_literals[m];

            if lit == self.quote_lit {
                if pos == field_start {
                    field_is_quoted = true;
                }
            } else if lit == self.delim_lit {
                field_starts.push(field_start);
                field_ends.push(pos);
                field_flags.push(if field_is_quoted { 1 } else { 0 });
                field_start = pos + 1;
                field_is_quoted = false;
            } else if lit == self.newline_lit {
                let mut field_end = pos;
                if m > 0 && match_literals[m - 1] == self.cr_lit && positions[m - 1] == pos - 1 {
                    field_end = pos - 1;
                }
                field_starts.push(field_start);
                field_ends.push(field_end);
                field_flags.push(if field_is_quoted { 1 } else { 0 });
                row_field_offset.push(field_starts.len());
                row_count += 1;
                field_start = pos + 1;
                field_is_quoted = false;
            } else if lit == self.cr_lit {
                if m + 1 < match_count
                    && match_literals[m + 1] == self.newline_lit
                    && positions[m + 1] == pos + 1
                {
                    continue;
                }
                field_starts.push(field_start);
                field_ends.push(pos);
                field_flags.push(if field_is_quoted { 1 } else { 0 });
                row_field_offset.push(field_starts.len());
                row_count += 1;
                field_start = pos + 1;
                field_is_quoted = false;
            }
        }

        let field_count_in_last_row = field_starts.len() - *row_field_offset.last().unwrap();
        if field_start <= data_size as u32
            && (field_count_in_last_row > 0 || (field_start as usize) < data_size)
        {
            field_starts.push(field_start);
            field_ends.push(data_size as u32);
            field_flags.push(if field_is_quoted { 1 } else { 0 });
            row_field_offset.push(field_starts.len());
            row_count += 1;
        }

        // Extract headers if configured
        let mut headers = Vec::new();
        let data_row_start = if self.has_header && row_count > 0 {
            let h_start = row_field_offset[0];
            let h_end = row_field_offset[1];
            for fi in h_start..h_end {
                let field = CsvField {
                    start: field_starts[fi],
                    end: field_ends[fi],
                    quoted: field_flags[fi] != 0,
                    quote_byte: self.quote_char,
                };
                headers.push(field.value(data));
            }
            1
        } else {
            0
        };

        Ok(CsvResult {
            field_starts,
            field_ends,
            field_flags,
            row_field_offset,
            row_count,
            data_row_start,
            headers,
            quote_byte: self.quote_char,
        })
    }
}

impl CsvParserBuilder {
    /// Set the field delimiter (default: `,`).
    pub fn delimiter(mut self, d: u8) -> Self {
        self.delimiter = d;
        self
    }

    /// Set the quote character (default: `"`).
    pub fn quote(mut self, q: u8) -> Self {
        self.quote = q;
        self
    }

    /// Enable header row detection (default: false).
    pub fn has_header(mut self, h: bool) -> Self {
        self.has_header = h;
        self
    }

    /// Override the SIMD backend.
    pub fn backend(mut self, b: SimdBackend) -> Self {
        self.backend = Some(b);
        self
    }

    /// Build the CSV parser.
    pub fn build(self) -> Result<CsvParser, CsvError> {
        let mut builder = EngineBuilder::new()
            .codepoints("quote", &[self.quote])
            .codepoints("delim", &[self.delimiter])
            .codepoints("lf", &[b'\n'])
            .codepoints("cr", &[b'\r'])
            .chunk_filter(
                quote_filter::csv_quote_filter,
                &["quote"],
            );

        if let Some(backend) = self.backend {
            builder = builder.backend(backend);
        }

        let result = builder.build().map_err(|e| CsvError::BuildFailed(e.to_string()))?;

        let literals = &result.literals;
        Ok(CsvParser {
            engine: result.engine,
            quote_lit: literals["quote"],
            delim_lit: literals["delim"],
            newline_lit: literals["lf"],
            cr_lit: literals["cr"],
            quote_char: self.quote,
            has_header: self.has_header,
        })
    }
}

/// Zero-copy CSV parse result.
///
/// Field boundaries are stored as byte offsets into the original data.
/// String materialization is deferred until `CsvField::value()` is called.
pub struct CsvResult {
    field_starts: Vec<u32>,
    field_ends: Vec<u32>,
    field_flags: Vec<u8>,
    row_field_offset: Vec<usize>,
    row_count: usize,
    data_row_start: usize,
    headers: Vec<String>,
    quote_byte: u8,
}

impl CsvResult {
    fn empty() -> Self {
        Self {
            field_starts: Vec::new(),
            field_ends: Vec::new(),
            field_flags: Vec::new(),
            row_field_offset: vec![0],
            row_count: 0,
            data_row_start: 0,
            headers: Vec::new(),
            quote_byte: b'"',
        }
    }

    /// Number of data rows (excluding header if present).
    pub fn row_count(&self) -> usize {
        self.row_count.saturating_sub(self.data_row_start)
    }

    /// Access row `i` (0-indexed from data rows, excluding header).
    pub fn row(&self, i: usize) -> CsvRow<'_> {
        let actual_row = i + self.data_row_start;
        let f_start = self.row_field_offset[actual_row];
        let f_end = self.row_field_offset[actual_row + 1];
        CsvRow {
            result: self,
            base: f_start,
            field_count: f_end - f_start,
        }
    }

    /// Column headers (empty if no header configured).
    pub fn headers(&self) -> &[String] {
        &self.headers
    }
}

/// Zero-copy row view into a [`CsvResult`].
pub struct CsvRow<'a> {
    result: &'a CsvResult,
    base: usize,
    field_count: usize,
}

impl CsvRow<'_> {
    /// Number of fields in this row.
    pub fn field_count(&self) -> usize {
        self.field_count
    }

    /// Get the string value of field at column `col`, materializing from `data`.
    pub fn get(&self, col: usize, data: &[u8]) -> String {
        self.field(col).value(data)
    }

    /// Get the field metadata at column `col`.
    pub fn field(&self, col: usize) -> CsvField {
        let fi = self.base + col;
        CsvField {
            start: self.result.field_starts[fi],
            end: self.result.field_ends[fi],
            quoted: self.result.field_flags[fi] != 0,
            quote_byte: self.result.quote_byte,
        }
    }

    /// Zero-copy raw bytes of field at column `col`.
    pub fn raw_field<'d>(&self, col: usize, data: &'d [u8]) -> &'d [u8] {
        let f = self.field(col);
        &data[f.start as usize..f.end as usize]
    }
}

/// A single CSV field with lazy string materialization.
#[derive(Debug, Clone)]
pub struct CsvField {
    pub start: u32,
    pub end: u32,
    pub quoted: bool,
    pub quote_byte: u8,
}

impl CsvField {
    /// Materialize the field value as a String.
    ///
    /// For quoted fields: strips outer quotes and unescapes `""` → `"`.
    /// For unquoted fields: returns the raw bytes as a string.
    pub fn value(&self, data: &[u8]) -> String {
        let raw = &data[self.start as usize..self.end as usize];
        if !self.quoted {
            return String::from_utf8_lossy(raw).into_owned();
        }
        // Strip outer quotes
        let inner = if raw.len() >= 2
            && raw[0] == self.quote_byte
            && raw[raw.len() - 1] == self.quote_byte
        {
            &raw[1..raw.len() - 1]
        } else {
            raw
        };
        // Unescape doubled quotes
        let qb = self.quote_byte;
        let mut result = Vec::with_capacity(inner.len());
        let mut i = 0;
        while i < inner.len() {
            if inner[i] == qb && i + 1 < inner.len() && inner[i + 1] == qb {
                result.push(qb);
                i += 2;
            } else {
                result.push(inner[i]);
                i += 1;
            }
        }
        String::from_utf8_lossy(&result).into_owned()
    }

    /// Raw byte slice (zero-copy, no unescaping).
    pub fn raw_slice<'d>(&self, data: &'d [u8]) -> &'d [u8] {
        &data[self.start as usize..self.end as usize]
    }
}

/// Errors from CSV parsing.
#[derive(Debug, thiserror::Error)]
pub enum CsvError {
    #[error("engine build failed: {0}")]
    BuildFailed(String),
}
