# 05 — Engine Compilation

This document specifies template specialization: the process of producing an optimized engine from a generic template by folding compile-time constants, eliminating dead code, and inlining method bodies.

Compilation is an optimization, not a correctness requirement. A conforming implementation may operate in interpreted mode only. However, if compilation is supported, the compiled engine must produce results identical to the unspecialized template.

---

## Specialization

#### COMP-001: Template Specialization

**Priority:** SHOULD

An engine template may be specialized with compile-time constants — LUT entries, literal counts, detection round counts, filter type — to produce an optimized engine. The template is a readable, general-purpose implementation; the specialized version eliminates branches and indirection.

**Acceptance Criteria:**
1. The builder accepts a flag to enable/disable compilation
2. When enabled, the engine is produced from the template with constants folded
3. When disabled, the template runs as-is (interpreted mode)
4. Both modes produce identical results for any input

**Test derivation:** Build the same engine with `compiled=true` and `compiled=false`, verify identical output on shared test inputs.

---

#### COMP-002: Constant Folding

**Priority:** SHOULD

Configuration values known at build time (round count, literal count, vector byte size, filter presence) replace variable loads in the specialized engine. This enables the compiler/JIT to evaluate branches at specialization time.

**Acceptance Criteria:**
1. Variables marked as compile-time constants are replaced with their values
2. The folded engine contains no loads for these variables

**Test derivation:** Inspect the specialized bytecode/IR to verify constant propagation (language-specific verification).

---

#### COMP-003: Dead Code Elimination

**Priority:** SHOULD

Code paths unreachable after constant folding are removed. For example, if `roundCount == 1`, code handling round 2+ is eliminated. If no filter is configured, filter invocation code is removed.

**Acceptance Criteria:**
1. Unreachable branches after constant folding are removed
2. The elimination does not affect reachable code paths
3. The resulting engine is smaller than the unspecialized template

**Test derivation:** Compare specialized engine size/instruction count against the template.

---

#### COMP-004: Method Inlining

**Priority:** SHOULD

Static helper method bodies (SIMD kernel operations, filter apply) are inlined at their call sites in the specialized engine. This eliminates call overhead and enables further optimization by the runtime compiler.

**Acceptance Criteria:**
1. Annotated static method calls are replaced with their bodies
2. Local variable slots and labels are remapped to avoid conflicts
3. The inlined engine produces correct results

**Test derivation:** Verify the specialized engine has no calls to inlined methods (language-specific verification).

---

## Parity

#### COMP-005: Compiled–Interpreted Parity

**Priority:** MUST

A compiled engine must produce byte-for-byte identical match output (positions and literals) as the uncompiled template for any input. This is the fundamental correctness guarantee for compilation.

**Acceptance Criteria:**
1. For any input buffer: compiled and interpreted engines produce the same match count
2. For any input buffer: every (position, literal) pair matches between the two engines
3. Verified across ASCII-only, mixed UTF-8, and adversarial inputs

**Test derivation:** Run the full test suite with both compiled and interpreted engines, diff all results.

---

#### COMP-006: Filter Specialization

**Priority:** SHOULD

When a chunk filter is configured, the filter's processing function is inlined into the detection loop of the specialized engine. The no-op default filter is replaced with the user's filter implementation before inlining.

**Acceptance Criteria:**
1. The no-op filter placeholder is replaced with the configured filter
2. The filter's method body is inlined alongside kernel methods
3. The specialized engine with filter produces correct filtered results

**Test derivation:** Build an engine with a filter (e.g., CSV quote filter), verify results match the interpreted path.

---
