# 06 — CSV Parser

This document specifies the SIMD-accelerated CSV parser: RFC 4180 compliance, two-phase architecture, quote filtering, zero-copy field access, and result iteration.

The CSV parser is the reference application of the core engine + VPA filter framework. It demonstrates the full pipeline: engine detects structural characters, a prefix-XOR filter suppresses characters inside quoted regions, and a linear match walker extracts field and row boundaries.

---

## Parsing Architecture

#### CSV-001: RFC 4180 Compliance

**Priority:** MUST

The parser handles CSV data conforming to RFC 4180: comma-separated fields, double-quote escaping (embedded quotes as `""`), and CRLF or LF line endings. Fields may contain newlines, commas, and quotes when enclosed in double quotes.

**Acceptance Criteria:**
1. Unquoted fields: text between delimiters, no embedded special characters
2. Quoted fields: delimited by quotes, may contain delimiters, newlines, and escaped quotes
3. Escaped quotes: `""` inside a quoted field represents a literal `"`
4. CRLF and LF both accepted as row terminators
5. Trailing newline at end of file is optional

**Test derivation:** Parse RFC 4180 example data and edge cases, verify field values match expected output.

---

#### CSV-002: Two-Phase Architecture

**Priority:** MUST

Parsing proceeds in two phases:
1. **Phase 1 (SIMD scan):** The engine detects all structural characters (delimiter, quote, newline, CR) in a single pass. A chunk filter zeros out structural characters inside quoted regions.
2. **Phase 2 (match walk):** A linear scan over the filtered match positions extracts field boundaries and row boundaries. No backtracking.

**Acceptance Criteria:**
1. Phase 1 produces a flat array of (position, literal) pairs for unquoted structural characters only
2. Phase 2 iterates this array exactly once to produce field/row structure
3. No state machine or backtracking in Phase 2

**Test derivation:** Parse a CSV with quoted and unquoted regions, verify Phase 1 output contains no structural characters from inside quotes.

---

#### CSV-003: Quote Filtering

**Priority:** MUST

Structural characters inside quoted regions are suppressed by a chunk filter using `prefixXor`. The filter computes a running quote-state toggle: after each quote character, subsequent characters alternate between "inside" and "outside" states. Characters in the "inside" state are zeroed in the detection result.

**Acceptance Criteria:**
1. A comma inside a quoted field does not produce a field boundary
2. A newline inside a quoted field does not produce a row boundary
3. The quote toggle state carries correctly across chunk boundaries
4. Pairs of consecutive quotes (`""`) correctly toggle in and out

**Test derivation:** Parse CSV with `"field,with,commas"` and `"field\nwith\nnewlines"`, verify these do not split into multiple fields/rows.

---

#### CSV-004: Quote Overhead Bound

**Priority:** SHOULD

The overhead of quote filtering is bounded. Target: less than 5% of total scan time for typical CSV data (10-20% quoted fields).

**Acceptance Criteria:**
1. Benchmark shows quote-filtered scan is within 10% of unfiltered scan on representative data
2. Fast path ([VPA-010]) is active for chunks with no quotes

**Test derivation:** Benchmark scan throughput with and without quotes, measure overhead.

---

## Configuration

#### CSV-005: Configurable Delimiter

**Priority:** MUST

The field delimiter character is configurable. Default is comma (`,`). Common alternatives: tab, semicolon, pipe.

**Acceptance Criteria:**
1. A parser configured with tab delimiter correctly splits tab-separated fields
2. The default delimiter is comma

**Test derivation:** Parse tab-separated and semicolon-separated data, verify correct field extraction.

---

#### CSV-006: Configurable Quote Character

**Priority:** MUST

The quote character is configurable. Default is double quote (`"`). Alternative: single quote.

**Acceptance Criteria:**
1. A parser configured with single quote correctly handles `'field,value'`
2. Escaped quotes use the configured character doubled (e.g., `''`)

**Test derivation:** Parse data with single-quote quoting, verify correct field values.

---

#### CSV-007: Header Detection

**Priority:** MUST

The first row may optionally be treated as a header row. When headers are enabled, field access by column name is supported. Header parsing consumes the first row from the data rows.

**Acceptance Criteria:**
1. With headers enabled: `headers()` returns column names from the first row
2. With headers enabled: `rowCount()` excludes the header row
3. With headers disabled: all rows are data rows, `headers()` is empty or null

**Test derivation:** Parse CSV with and without headers, verify row counts and header values.

---

## Zero-Copy Fields

#### CSV-008: Zero-Copy Fields

**Priority:** MUST

Field boundaries are stored as byte offset pairs `[start, end)` into the original byte buffer. No string materialization occurs during parsing. Strings are created only when explicitly requested.

**Acceptance Criteria:**
1. After parsing, field start/end offsets reference positions in the original buffer
2. No strings are allocated during the parse phase
3. `rawSlice()` returns a view into the original data without copying

**Test derivation:** Parse a CSV, access fields via raw slice, verify offsets point to correct data.

---

#### CSV-009: Escaped Quote Handling

**Priority:** MUST

When a quoted field's string value is materialized, escaped quotes (`""`) are unescaped to single quotes (`"`). The outer quotes are stripped. This only happens at materialization time, not during parsing.

**Acceptance Criteria:**
1. A field containing `""` materializes with a single `"`
2. Outer quotes are not included in the materialized value
3. Unescaping is lazy — it only runs when the string value is requested

**Test derivation:** Parse `"He said ""hello"""`, verify materialized value is `He said "hello"`.

---

## Row and Field Structure

#### CSV-010: Row Boundaries

**Priority:** MUST

Each newline (LF or CRLF) outside a quoted region marks a row boundary. The parser tracks row start offsets for random access to rows.

**Acceptance Criteria:**
1. LF produces a row boundary
2. CRLF produces a single row boundary (not two)
3. Newlines inside quotes do not produce row boundaries
4. Rows are accessible by index in O(1)

**Test derivation:** Parse CSV with mixed LF and CRLF, verify correct row count and boundaries.

---

#### CSV-011: Zero-Allocation Scan

**Priority:** SHOULD

The scan phase (Phase 1: SIMD detection + filter) produces match positions without heap allocation beyond the reusable storage. All buffers are pre-allocated and reused.

**Acceptance Criteria:**
1. Scan does not allocate heap memory per call (storage is reused)
2. The only growing allocation is storage auto-grow for unexpectedly large inputs

**Test derivation:** Profile memory allocation during scan of a large CSV, verify no per-call allocations.

---

#### CSV-012: Large Field Support

**Priority:** MUST

Fields spanning multiple SIMD chunks are handled correctly. A quoted field may be arbitrarily long (limited only by available memory).

**Acceptance Criteria:**
1. A field longer than `vectorByteSize * 10` is parsed correctly
2. The field's content is fully accessible
3. Quote carry state propagates correctly across many chunks

**Test derivation:** Generate a CSV with a single field containing 10KB+ of text, verify correct parsing.

---

#### CSV-013: Empty Fields

**Priority:** MUST

Adjacent delimiters produce empty fields. A trailing delimiter before a newline produces a trailing empty field.

**Acceptance Criteria:**
1. Input `a,,c` produces three fields: `"a"`, `""`, `"c"`
2. Input `a,b,\n` produces three fields, the third being empty
3. Input `,` produces two empty fields

**Test derivation:** Parse CSV with various empty field patterns, verify field counts and values.

---

#### CSV-014: Result Iteration

**Priority:** MUST

The parse result provides: total row count, field count per row, and field access by row and column index. A streaming interface (iterator/stream) over rows is supported.

**Acceptance Criteria:**
1. `rowCount()` returns the number of data rows
2. `row(i)` returns the i-th row with `fieldCount()` and indexed field access
3. A stream/iterator interface iterates all rows without materializing all at once
4. Field access by index is O(1)

**Test derivation:** Parse a multi-row CSV, iterate rows and fields, verify counts and values.

---
