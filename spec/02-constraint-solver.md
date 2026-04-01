# 02 — Constraint Solver

This document specifies the constraint-solving subsystem: the nibble matrix problem formulation, solution existence, group capacity, auto-split recursion, and literal assignment.

The solver's job is to find two 16-entry shuffle LUT vectors whose AND operation uniquely identifies each target character. The spec describes the problem and its constraints — implementations may use any solver (SAT, SMT, brute-force, heuristic) that satisfies these requirements.

---

## Nibble Matrix

#### SOLVE-001: Nibble Matrix Problem

**Priority:** MUST

Given a set of N target bytes, the solver must find two 16-entry byte vectors `LUT_lo` and `LUT_hi` such that for every target byte `b`, `LUT_lo[b & 0xF] AND LUT_hi[b >> 4]` produces a unique non-zero literal byte, and for every non-target byte `b`, the same operation produces zero.

**Acceptance Criteria:**
1. For each target byte: the AND result is a unique non-zero value in `[1, 255]`
2. For each non-target byte (all 256 - N others): the AND result is zero
3. No two targets share the same AND result
4. Each LUT has exactly 16 entries indexed by nibble value `[0, 15]`

**Test derivation:** Solve for a known target set, verify all 256 byte values produce correct results.

---

#### SOLVE-002: Solution Existence

**Priority:** MUST

When no valid LUT pair exists for the given target set within a single group, the solver must report failure (not hang or return an incorrect solution). Failure triggers auto-split ([SOLVE-004]).

**Acceptance Criteria:**
1. The solver terminates in bounded time for any input
2. On failure, a clear signal is returned (not an invalid LUT pair)
3. An invalid/incomplete LUT is never used for detection

**Test derivation:** Submit a target set known to be unsolvable in one group (e.g., 16+ characters with conflicting nibble patterns), verify failure is reported.

---

#### SOLVE-003: Group Capacity

**Priority:** MUST

A single solve must reliably handle at least 12 ASCII characters. This is the empirical capacity of the 16x16 nibble matrix with bitwise AND constraints.

**Acceptance Criteria:**
1. A solve with 12 arbitrary printable ASCII characters succeeds
2. The resulting LUTs pass the correctness check ([SOLVE-001])

**Test derivation:** Solve for 12 randomly chosen printable ASCII characters across multiple seeds, verify success rate > 95%.

---

## Auto-Split

#### SOLVE-004: Auto-Split Recursion

**Priority:** MUST

When a single solve fails, the target set is partitioned into two subsets. Each subset is solved independently as a separate shuffle group. The partition is recursive: if a half still fails, it is split again.

**Acceptance Criteria:**
1. A target set exceeding single-group capacity is automatically partitioned
2. Each partition produces a valid LUT pair
3. The combined results cover all original targets
4. Recursion terminates (each split reduces the problem)

**Test derivation:** Build an engine with 15-20 ASCII targets, verify successful construction and that all targets are detected.

---

#### SOLVE-005: Split Capacity

**Priority:** MUST

Auto-split must handle at least 20-24 ASCII literals across two groups. The effective capacity is approximately double the single-group capacity.

**Acceptance Criteria:**
1. An engine with 20 ASCII targets builds successfully
2. An engine with 24 ASCII targets builds successfully (if platform vector size permits)
3. All targets are detected correctly

**Test derivation:** Build and test engines with 20 and 24 ASCII targets.

---

## Literal Assignment

#### SOLVE-006: Deterministic Output

**Priority:** MAY

Given the same input targets in the same order, the solver may produce the same LUT pair across invocations. Determinism is not required but is desirable for reproducible testing.

**Acceptance Criteria:**
1. If deterministic: two solves with identical input produce identical LUTs
2. If non-deterministic: both solutions are valid per [SOLVE-001]

**Test derivation:** Solve the same target set twice, compare LUT outputs.

---

#### SOLVE-007: Literal Assignment

**Priority:** MUST

The solver assigns literal IDs from the shared namespace `[1, vectorByteSize - 1]`. Literal IDs are assigned per-engine, not globally. Multi-byte codepoints may reuse literal IDs across detection rounds (the same codepoint uses the same literal in all rounds where its bytes appear).

**Acceptance Criteria:**
1. All assigned literals are in `[1, vectorByteSize - 1]`
2. No two distinct target names within one engine share a literal
3. Multi-byte codepoints use consistent literals across rounds

**Test derivation:** Build an engine with mixed ASCII and multi-byte targets, verify literal assignments.

---

#### SOLVE-008: Range Operation Bypass

**Priority:** MUST

Contiguous byte ranges (e.g., `0x30–0x39` for ASCII digits) bypass the nibble matrix solver entirely. Each range consumes 1 literal ID and is evaluated via compare-and-mask operations at detection time.

**Acceptance Criteria:**
1. A range operation does not consume nibble matrix capacity
2. A range consumes exactly 1 literal ID
3. Range detection is correct for all bytes in `[from, to]` inclusive
4. Bytes outside the range produce zero

**Test derivation:** Build an engine with a range plus near-capacity shuffle targets, verify both work correctly.

---
