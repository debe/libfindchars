# 04 — VPA Filters

This document specifies the Visibly Pushdown Automaton (VPA) chunk filter framework: the filter interface, prefix XOR and prefix sum primitives, carry propagation, zeroing semantics, and working memory contracts.

Chunk filters transform the detection result vector between SIMD detection and position decode. The canonical use case is CSV quote handling: suppressing structural characters that appear inside quoted regions.

---

## Filter Interface

#### VPA-001: Chunk Filter Interface

**Priority:** MUST

A chunk filter provides a processing function that accepts the current detection result vector and returns a (potentially modified) result vector. The filter runs once per chunk, after SIMD detection and before position decode.

Implementations must provide:
1. An instance `apply(...)` method — the runtime dispatch target used in JIT and AOT compilation modes via interface dispatch.
2. A static `applyStatic(...)` method with identical parameters, annotated for inlining — the bytecode inlining target used in BYTECODE_INLINE mode. In this mode, the engine rewrites `invokeinterface apply` → `invokestatic applyStatic`, then the bytecode inliner transplants the static body.
3. A `public static final INSTANCE` singleton field — used by the builder to resolve the filter instance for JIT and AOT modes.

**Acceptance Criteria:**
1. The filter function receives: the accumulator (detection result vector), a zero vector, the vector species, mutable state, a mutable scratchpad, and immutable literal vectors
2. The filter returns a (possibly modified) byte vector
3. The filter is invoked exactly once per chunk during detection
4. Both `apply` and `applyStatic` produce identical results for identical inputs

**Test derivation:** Implement a pass-through filter, verify detection results are unchanged across all compilation modes.

---

#### VPA-002: Filter State

**Priority:** MUST

Filters may maintain mutable state across chunks within a single `find()` call. State is represented as a fixed-size long array (at least 8 elements). State is reset to zero at the beginning of each `find()` call.

**Acceptance Criteria:**
1. State persists across chunks within one `find()` call
2. State is zeroed before each new `find()` call
3. State array has at least 8 elements

**Test derivation:** Implement a filter that sets state in chunk 0, verify it reads the same state in chunk 1. Verify state is reset between `find()` calls.

---

## Prefix Primitives

#### VPA-003: Prefix XOR

**Priority:** MUST

`prefixXor(vector)` computes the inclusive parallel prefix XOR across all lanes of a byte vector. This produces toggle semantics: if lane 0 is 1, all subsequent lanes flip between 1 and 0. Used for tracking inside/outside quoted regions.

The algorithm uses the Hillis-Steele pattern in O(log2(vectorByteSize)) steps.

**Acceptance Criteria:**
1. For input `[1, 0, 0, 1, 0, ...]`, output is `[1, 1, 1, 0, 0, ...]`
2. For input `[0, 0, 0, ...]`, output is `[0, 0, 0, ...]`
3. For input `[1, 1, 1, ...]`, output is `[1, 0, 1, ...]` (toggling)
4. Computed in O(log2(V)) vector steps, not O(V) scalar steps

**Test derivation:** Test with known prefix XOR patterns, verify output matches expected toggle sequences.

---

#### VPA-004: Prefix Sum

**Priority:** MUST

`prefixSum(vector)` computes the inclusive parallel prefix sum across all lanes of a byte vector, with 8-bit lane saturation. Used for tracking nesting depth (e.g., bracket counting).

The algorithm uses the Hillis-Steele pattern in O(log2(vectorByteSize)) steps.

**Acceptance Criteria:**
1. For input `[1, 0, 1, 0, ...]`, output is `[1, 1, 2, 2, ...]`
2. For input `[1, -1, 1, -1, ...]` (signed bytes), output is `[1, 0, 1, 0, ...]`
3. 8-bit saturation: values do not wrap past 127 or below -128
4. Computed in O(log2(V)) vector steps

**Test derivation:** Test with known prefix sum patterns including edge cases near saturation bounds.

---

#### VPA-005: Carry Propagation

**Priority:** MUST

Prefix operations accept carry-in from the previous chunk's result and produce carry-out for the next chunk. For prefix XOR: carry is 1 bit (last lane's cumulative XOR). For prefix sum: carry is the cumulative sum value.

**Acceptance Criteria:**
1. A prefix XOR with carry-in = 1 inverts the entire output
2. A prefix sum with carry-in = N adds N to all output lanes
3. Carry-out from chunk K becomes carry-in for chunk K+1
4. Carry is stored in the filter state array

**Test derivation:** Process two chunks where the first chunk's carry affects the second chunk's result, verify correctness.

---

## Filter Semantics

#### VPA-006: Filter Zeroing

**Priority:** MUST

A filter may zero out literal bytes at specific positions in the detection result vector. Zeroed positions are excluded from the match output. This is the mechanism for suppressing matches inside quoted regions, nested structures, etc.

**Acceptance Criteria:**
1. A zeroed position does not appear in the match output
2. Non-zeroed positions are unaffected
3. Zeroing is performed via vector AND with a computed mask

**Test derivation:** Apply a filter that zeros positions 2-5, verify only positions 0-1 and 6+ appear in output.

---

#### VPA-007: No-Op Default

**Priority:** MUST

When no filter is configured, detection results pass through unmodified. The no-op filter adds zero overhead to the detection pipeline.

**Acceptance Criteria:**
1. An engine without a filter produces the same results as one with a no-op filter
2. The no-op path does not execute prefix computations

**Test derivation:** Compare engine output with and without a no-op filter on the same input.

---

#### VPA-008: Filter Composability

**Priority:** MAY

Multiple filters may be chained sequentially, each transforming the result of the previous. This is a future extension; current implementations need only support a single filter.

**Acceptance Criteria:**
1. If supported: filters execute in configured order, each receiving the previous filter's output
2. If not supported: attempting to configure multiple filters produces a clear error

**Test derivation:** Configure two filters, verify they compose correctly (or that the error is clear).

---

## Working Memory

#### VPA-009: Working Memory Contract

**Priority:** MUST

The engine provides all working memory to the filter. The filter must not allocate heap memory. Working memory consists of:
- **State array**: mutable `long[8]` (or equivalent), for inter-chunk carry and counters
- **Scratchpad**: mutable byte array of `vectorByteSize` bytes, for temporary vector materialization
- **Literal vectors**: immutable, pre-broadcast from builder-configured literal bindings

**Acceptance Criteria:**
1. The filter receives all three memory regions as parameters
2. The filter does not allocate heap memory during processing
3. State and scratchpad are engine-owned and reset per `find()` call
4. Literal vectors are immutable and reflect the builder's literal bindings

**Test derivation:** Implement a filter that uses state, scratchpad, and literals; verify correct behavior without allocation.

---

#### VPA-010: Fast Path Skip

**Priority:** SHOULD

When no relevant literals are present in a chunk and no carry exists from the previous chunk, the filter skips prefix computation entirely. This avoids unnecessary work on chunks with no structural characters.

**Acceptance Criteria:**
1. Chunks with no relevant matches and zero carry-in skip prefix computation
2. The fast path produces the same result as the full path (identity)
3. The fast-path check is a single vector comparison

**Test derivation:** Process sparse input where most chunks have no relevant matches, verify prefix computation is skipped.

---
