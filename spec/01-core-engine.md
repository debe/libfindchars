# 01 — Core Engine

This document specifies the core detection engine: its interface, SIMD chunk processing model, shuffle-based detection, literal encoding, match output semantics, and storage management.

---

## Engine Interface

#### ENGINE-001: Engine Interface

**Priority:** MUST

An engine accepts a contiguous byte buffer and a reusable storage object. It returns an immutable view of all matches found: pairs of (byte offset, literal identifier).

The engine exposes a single detection method. A convenience overload accepting a byte array is permitted.

**Acceptance Criteria:**
1. The engine method accepts a byte buffer (language-native contiguous memory) and a storage object
2. The engine method returns an immutable match view
3. The match view provides: `size()`, access to position at index, access to literal at index

**Test derivation:** Construct an engine targeting known characters, call find on a buffer containing those characters, verify the match view is non-empty and positions/literals are accessible.

---

#### ENGINE-002: Literal Identity

**Priority:** MUST

Each target character or codepoint configured in the engine maps to a unique literal byte value in the range `[1, maxLiterals]`. The literal byte zero is reserved to mean "no match." Literal values are determined at engine build time (not compile-time constants) and returned to the caller as a name-to-literal map.

**Acceptance Criteria:**
1. Every configured target produces a non-zero literal byte
2. No two distinct targets share the same literal byte within a single engine
3. The builder returns a mapping from target name to assigned literal byte
4. Literal byte 0 never appears as a match

**Test derivation:** Build an engine with N targets, verify the literal map has N entries with distinct non-zero values.

---

#### ENGINE-003: SIMD Chunk Processing

**Priority:** MUST

Input data is processed in fixed-size chunks matching the platform's vector byte width. The final partial chunk (fewer bytes than vector width) must be handled correctly without reading beyond the buffer boundary.

**Acceptance Criteria:**
1. Full chunks of `vectorByteSize` bytes are processed using vector operations
2. A trailing partial chunk produces correct matches for all bytes within the valid range
3. No out-of-bounds memory access occurs for any input length

**Test derivation:** Test with input lengths that are exact multiples of vector width, one byte short, one byte over, and empty.

---

## Detection

#### ENGINE-004: Shuffle-Based Detection

**Priority:** MUST

Each input byte is split into low nibble (`byte & 0x0F`) and high nibble (`byte >> 4`). Two 16-entry lookup tables (LUTs) are shuffled by these nibbles. The bitwise AND of the two shuffle results yields the literal byte for target characters, or zero for non-targets.

**Acceptance Criteria:**
1. For every target byte `b`: `LUT_lo[b & 0xF] AND LUT_hi[b >> 4] == literal(b)`
2. For every non-target byte `b`: `LUT_lo[b & 0xF] AND LUT_hi[b >> 4] == 0`
3. Each LUT has exactly 16 entries

**Test derivation:** Verify LUT correctness by exhaustive check of all 256 byte values against the target set.

---

#### ENGINE-005: Detection Correctness

**Priority:** MUST

For every occurrence of a target byte in the input, the corresponding byte offset appears in the match output with the correct literal identifier. No target occurrence is missed.

**Acceptance Criteria:**
1. Every target byte position in the input appears in the match output
2. The literal at each match position equals the expected literal for that target byte
3. Validated against a reference implementation (e.g., regex or linear scan)

**Test derivation:** Generate input with known target positions, compare engine output against reference. Use fuzz testing with random inputs.

---

#### ENGINE-006: No False Positives

**Priority:** MUST

Bytes that are not configured as targets must never produce a non-zero literal in the match output.

**Acceptance Criteria:**
1. The match output contains no positions corresponding to non-target bytes
2. Verified for all 256 possible byte values

**Test derivation:** Generate input containing all 256 byte values, verify only target positions appear in output.

---

#### ENGINE-007: Match Ordering

**Priority:** MUST

Positions in the match view are in strictly ascending order by byte offset.

**Acceptance Criteria:**
1. For all `i` in `[0, size-1)`: `position(i) < position(i+1)`

**Test derivation:** Verify monotonically increasing positions across multiple test inputs.

---

## Storage

#### ENGINE-008: Storage Reuse

**Priority:** MUST

Storage objects are reusable across `find()` calls. Each call overwrites previous results. The caller may pre-allocate storage sized to expected match count.

**Acceptance Criteria:**
1. A single storage object used in two consecutive `find()` calls returns correct, independent results
2. Results from the first call are not visible after the second call

**Test derivation:** Call `find()` twice with different inputs on the same storage, verify each result is correct.

---

#### ENGINE-009: Auto-Growing Storage

**Priority:** MUST

When the number of matches exceeds the current storage capacity, the storage grows automatically. No matches are lost due to capacity limits.

**Acceptance Criteria:**
1. An engine with many matches (exceeding initial capacity) returns all matches
2. Storage capacity increases to accommodate the result

**Test derivation:** Create input with more matches than the default storage size, verify all matches are returned.

---

## Multi-Round and Splitting

#### ENGINE-010: Multi-Round Detection

**Priority:** MUST

When the number of target characters exceeds the capacity of a single shuffle group, multiple detection rounds are used. Each round uses its own LUT pair. Results from all rounds are combined.

**Acceptance Criteria:**
1. An engine with more targets than a single group supports still detects all targets
2. No targets are missed due to round boundaries
3. Positions from different rounds are merged in ascending order

**Test derivation:** Configure an engine with 20+ ASCII targets, verify all are detected correctly.

---

#### ENGINE-011: Auto-Split

**Priority:** MUST

When the constraint solver cannot find a valid LUT pair for a given set of targets, the set is automatically partitioned and each half is solved independently. This is transparent to the caller.

**Acceptance Criteria:**
1. An engine with more targets than a single solve handles (>12 ASCII) still builds successfully
2. Detection correctness is maintained after splitting

**Test derivation:** Build engines with progressively larger target sets, verify successful construction and correctness.

---

## Limits and Platform

#### ENGINE-012: Literal Namespace Limits

**Priority:** MUST

The maximum number of distinct literals per engine is `vectorByteSize - 1` (since literal byte 0 is reserved). On 128-bit vectors: 15 max. On 256-bit: 31 max. On 512-bit: 63 max.

**Acceptance Criteria:**
1. The builder rejects configurations exceeding the namespace limit
2. Up to `vectorByteSize - 1` targets are accepted

**Test derivation:** Attempt to build an engine at and beyond the limit, verify acceptance/rejection.

---

#### ENGINE-013: Platform Vector Sizes

**Priority:** MUST

The engine must support at least 128-bit (16-byte) and 256-bit (32-byte) vector widths. 512-bit (64-byte) support is required on platforms that provide it.

**Acceptance Criteria:**
1. Engine builds and runs correctly on 128-bit (ARM NEON)
2. Engine builds and runs correctly on 256-bit (x86 AVX2)
3. Engine builds and runs correctly on 512-bit (x86 AVX-512) where available

**Test derivation:** Run the test suite with different vector species configurations.

---

#### ENGINE-014: Engine Not Thread-Safe

**Priority:** MUST

A single engine instance must not be called concurrently from multiple threads. The engine maintains mutable internal state (decode buffers, filter state) that is not synchronized. Separate engine instances may run in parallel.

**Acceptance Criteria:**
1. Documented as not thread-safe
2. Separate instances produce correct results when run concurrently

**Test derivation:** Run two engine instances in parallel threads, verify independent correct results.

---

#### ENGINE-015: Empty Input

**Priority:** MUST

An empty byte buffer (length 0) produces zero matches. The engine does not crash or allocate unnecessarily.

**Acceptance Criteria:**
1. `find(empty_buffer, storage).size() == 0`
2. No exception or panic

**Test derivation:** Call `find()` with a zero-length buffer, verify size is 0.

---
