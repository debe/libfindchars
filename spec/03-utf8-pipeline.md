# 03 — UTF-8 Pipeline

This document specifies the UTF-8 detection pipeline: ASCII fast path, lead byte classification, multi-byte gating for 2/3/4-byte sequences, boundary handling, range operations, and platform-adaptive decode.

---

## ASCII Fast Path

#### UTF8-001: ASCII Fast Path

**Priority:** MUST

When a chunk contains only ASCII bytes (all bytes < 0x80), the engine skips multi-byte classification and gating. This is the common case for English text and avoids unnecessary overhead.

**Acceptance Criteria:**
1. Pure-ASCII chunks are detected and take the fast path
2. The fast path produces correct results identical to the full pipeline
3. A single non-ASCII byte in a chunk forces the full pipeline

**Test derivation:** Profile or instrument the engine to verify fast-path activation on ASCII-only input.

---

## Classification

#### UTF8-002: Lead Byte Classification

**Priority:** MUST

Each byte in a chunk is classified into one of: ASCII (0x00–0x7F), 2-byte lead (0xC0–0xDF), 3-byte lead (0xE0–0xEF), 4-byte lead (0xF0–0xF7), or continuation (0x80–0xBF). Classification uses a 16-entry lookup table indexed by the high nibble.

**Acceptance Criteria:**
1. All 256 byte values are classified correctly
2. The classification table has exactly 16 entries
3. Classification is performed via vector shuffle (SIMD)

**Test derivation:** Feed all 256 byte values through classification, verify each is categorized correctly.

---

## Multi-Byte Gating

#### UTF8-003: Multi-Byte Gating

**Priority:** MUST

After shuffle-based detection, results for multi-byte codepoints are gated: a match is only valid if it occurs at a lead byte position of the correct type. Matches at continuation byte positions are suppressed.

**Acceptance Criteria:**
1. A 2-byte codepoint match is only retained at 2-byte lead positions
2. A 3-byte codepoint match is only retained at 3-byte lead positions
3. A 4-byte codepoint match is only retained at 4-byte lead positions
4. False matches at continuation positions are zeroed

**Test derivation:** Construct input where target byte patterns appear at both valid lead positions and invalid continuation positions, verify only valid positions are reported.

---

#### UTF8-004: 2-Byte Codepoint Detection

**Priority:** MUST

Codepoints in the range U+0080 through U+07FF are encoded as 2-byte UTF-8 sequences (lead byte 0xC0–0xDF + 1 continuation byte). The engine detects these by matching the lead byte through the shuffle matrix, then gating with the 2-byte lead classification.

**Acceptance Criteria:**
1. Codepoints like U+00E9 (e with acute, `0xC3 0xA9`) are detected
2. The match position is the byte offset of the lead byte
3. The literal matches the assigned value for the codepoint

**Test derivation:** Encode several 2-byte codepoints in a buffer, verify detection at correct positions.

---

#### UTF8-005: 3-Byte Codepoint Detection

**Priority:** MUST

Codepoints in the range U+0800 through U+FFFF are encoded as 3-byte UTF-8 sequences. Detection and gating follow the same pattern as 2-byte, using the 3-byte lead classification.

**Acceptance Criteria:**
1. Codepoints like U+2122 (trademark, `0xE2 0x84 0xA2`) are detected
2. Match position is the lead byte offset

**Test derivation:** Encode several 3-byte codepoints, verify detection.

---

#### UTF8-006: 4-Byte Codepoint Detection

**Priority:** MUST

Codepoints in the range U+10000 through U+10FFFF are encoded as 4-byte UTF-8 sequences. Detection uses 4-byte lead classification gating.

**Acceptance Criteria:**
1. Codepoints like U+1F600 (grinning face, `0xF0 0x9F 0x98 0x80`) are detected
2. Match position is the lead byte offset

**Test derivation:** Encode several 4-byte codepoints (emoji), verify detection.

---

#### UTF8-007: Shared Lead Bytes

**Priority:** MUST

Multiple codepoints sharing the same lead byte value are distinguished correctly. The shuffle matrix solves for each unique lead byte; gating and continuation byte analysis differentiate the codepoints.

**Acceptance Criteria:**
1. Two codepoints with the same lead byte (e.g., U+00E9 and U+00F1, both with lead `0xC3`) are each detected with their own literal
2. No cross-contamination between codepoints sharing a lead byte

**Test derivation:** Configure an engine with two codepoints sharing a lead byte, verify independent detection.

---

#### UTF8-008: Boundary Spanning

**Priority:** MUST

Multi-byte codepoints whose bytes span a chunk boundary are handled correctly. The engine must not miss or duplicate matches at boundaries.

**Acceptance Criteria:**
1. A 2-byte codepoint split across chunks (lead in chunk N, continuation in chunk N+1) is detected exactly once
2. Same for 3-byte and 4-byte codepoints
3. No false matches at the boundary

**Test derivation:** Position multi-byte codepoints at `vectorByteSize - 1`, `vectorByteSize - 2`, and `vectorByteSize - 3` offsets, verify correct detection.

---

## Range Operations

#### UTF8-009: Range Operations

**Priority:** MUST

A byte range `[from, to]` is matched via compare-and-mask, not through the nibble matrix. All bytes `b` where `from <= b <= to` match with the range's literal. Bytes outside the range produce zero.

**Acceptance Criteria:**
1. All bytes in `[from, to]` match with the correct literal
2. Bytes at `from - 1` and `to + 1` do not match
3. Range detection works alongside nibble-matrix detection in the same engine

**Test derivation:** Configure an engine with both a range and shuffle targets, verify both work correctly.

---

## Combining Results

#### UTF8-010: Combined Round Results

**Priority:** MUST

When multiple detection rounds are used (multiple shuffle groups, ranges, multi-byte gates), their results are combined via bitwise OR into a single result vector per chunk. A position that matches in any round appears in the final output.

**Acceptance Criteria:**
1. Targets from different rounds are all present in the final output
2. OR combination does not introduce false positives
3. A position matched by multiple rounds appears exactly once with the correct literal

**Test derivation:** Build an engine with targets spanning two shuffle groups plus a range, verify all are detected.

---

## Decode

#### UTF8-011: Platform-Adaptive Decode

**Priority:** MUST

Match positions are extracted from the result vector into the storage buffers. The extraction method is platform-dependent: compress instructions (AVX-512 `VPCOMPRESSB`) where available, scalar fallback elsewhere. The choice of decode strategy does not affect correctness.

**Acceptance Criteria:**
1. On AVX-512: compress-based decode produces correct results
2. On AVX2/NEON: fallback decode produces correct results
3. Both decode paths produce identical match output for the same input

**Test derivation:** Run the same test suite on platforms with different decode capabilities, compare results.

---

#### UTF8-012: Fast Rejection

**Priority:** SHOULD

Chunks with no matches (all zeros in the result vector) are skipped with zero decode cost. A single-instruction check (e.g., `anyTrue()`) gates the decode path.

**Acceptance Criteria:**
1. Empty chunks do not invoke the decode loop
2. This check maps to a single vector comparison instruction where possible

**Test derivation:** Profile engine on sparse input (few matches), verify decode is not invoked for empty chunks.

---
