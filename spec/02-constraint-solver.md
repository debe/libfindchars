# 02 — Constraint Solver

This document specifies the constraint-solving subsystem: the nibble matrix problem formulation, solution existence, group capacity, auto-split recursion, and literal assignment.

The solver's job is to find two 16-entry shuffle LUT vectors whose AND operation uniquely identifies each target character. The spec describes the problem and its constraints — implementations may use any solver (SAT, SMT, brute-force, heuristic) that satisfies these requirements.

---

## Nibble Matrix

#### SOLVE-001: Nibble Matrix Problem

**Priority:** MUST

Given a set of N target bytes, the solver must find two 16-entry byte vectors `LUT_lo` and `LUT_hi` such that for every target byte `b`, `LUT_lo[b & 0xF] AND LUT_hi[b >> 4]` produces that byte's unique non-zero literal, and for every non-target byte the same operation produces a value that collides with no literal.

Non-target results are **not** required to be zero. Detection is two-stage: the nibble matrix guarantees only non-collision, and a secondary clean LUT (`vpermb` / `selectFrom`, see [ENGINE-004]) maps every non-literal value to zero at runtime. Because that stage indexes by the low `log2(vectorByteSize)` bits, the solver's non-collision constraint is evaluated on the result masked with `vectorByteSize - 1`.

**Acceptance Criteria:**
1. For each target byte: the AND result is a unique non-zero value in `[1, vectorByteSize)`
2. For each non-target byte (all 256 - N others): the AND result, masked with `vectorByteSize - 1`, equals no assigned literal
3. No two targets share the same AND result
4. Each LUT has exactly 16 entries indexed by nibble value `[0, 15]`
5. The check runs on every solved group at engine-construction time, not only in tests — a satisfiable answer to a wrongly encoded constraint system is still wrong

**Test derivation:** Solve for a known target set, verify all 256 byte values produce correct results. Separately, corrupt a solved LUT and verify the check rejects it — a verifier that always accepts is indistinguishable from a working one.

---

#### SOLVE-002: Solution Existence

**Priority:** MUST

When no valid LUT pair exists for the given target set within a single group, the solver must report failure (not hang or return an incorrect solution). Failure triggers auto-split ([SOLVE-004]).

Failure modes must remain distinguishable. An SMT solver exhausting its time budget has *not* proven the group unsolvable, and conflating the two would let a solvable configuration fail to build purely because the machine was loaded. Implementations report unsatisfiability, timeout, and verification failure as distinct outcomes, and fall back to the guaranteed construction of [SOLVE-003] before splitting.

**Acceptance Criteria:**
1. The solver terminates in bounded time for any input
2. On failure, a clear signal is returned (not an invalid LUT pair)
3. An invalid/incomplete LUT is never used for detection

**Test derivation:** Submit a target set known to be unsolvable in one group (e.g., 16+ characters with conflicting nibble patterns), verify failure is reported.

---

#### SOLVE-003: Group Capacity

**Priority:** MUST

Capacity has a guaranteed floor and a measured frontier, and the two are different claims.

**The floor is universal.** Assigning each literal a pairwise bit-disjoint value and OR-ing those bits into the nibble slots its target bytes occupy makes every non-target nibble pair intersect to zero, so no cross-terms exist and no search is needed. This succeeds for *any* target set whose literals carry a single byte each. Its capacity is the count of unused single-bit values below `vectorByteSize` — 6 for AVX-512, 5 for AVX2, 4 for NEON — and it is what makes small configurations independent of the SMT solver entirely.

**The frontier is empirical, and it depends on the vector width.** Above the floor, whether a set solves depends on the nibble-incidence structure of its bytes rather than on how many there are, because cross-terms need only avoid the *assigned* literals rather than equal zero. Measured over random single-byte printable-ASCII sets (25 trials per point, one group, no split):

| `vectorByteSize` | 100% solved through | 50% near | 0% at |
|------------------|--------------------|----------|-------|
| 16 (NEON)        | 6                  | 8        | 10    |
| 32 (AVX2)        | 13                 | 15       | 18    |
| 64 (AVX-512)     | 20 (frontier beyond the swept range) | — | — |

Note the consequence: **a single group does not reach 12 characters on a 16-byte vector.** The literal namespace there is only `[1, 15]`, and cross-term pressure exhausts it well before 12. Implementations targeting NEON must rely on auto-split ([SOLVE-004]) for sets that size, which is the designed behaviour — a set that fails a single solve is split, not treated as an error.

**Acceptance Criteria:**
1. Any set of at most `floor(log2(vectorByteSize))` single-byte literals solves without invoking the SMT solver. The budget is per engine rather than per group: the construction consumes one bit per literal from a namespace shared across all groups and rounds, so a split cannot replenish it
2. A single solve with 12 arbitrary printable ASCII characters succeeds where `vectorByteSize >= 32`
3. Any set within the platform's namespace limit ([SOLVE-007]) builds successfully once auto-split ([SOLVE-004]) is applied, on every width
4. The resulting LUTs pass the correctness check ([SOLVE-001])

**Test derivation:** Sweep random single-byte literal sets at the floor width and verify every one solves and verifies without the SMT solver. Separately, sweep increasing set sizes per vector width and record the measured success rate; that measurement is the source for the table above and must be re-run when the constraint encoding changes. Note that success rates measured against an SMT solver with a fixed time budget are load-dependent, so they belong in a reported sweep rather than in an assertion.

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
