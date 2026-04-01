# 00 — Specification Index

This specification defines the observable contract for **libfindchars**, a high-performance character detection library that uses SIMD instructions to find ASCII and multi-byte UTF-8 characters in byte sequences. Implementations in any language must satisfy these requirements to be considered conforming.

---

## Implementations

| Language | Location   | Runtime        | Maturity   |
|----------|------------|----------------|------------|
| Java 25  | `java/`    | JDK Vector API | Production |
| Rust     | `rust/`    | `std::arch`    | Planned    |

---

## Spec Philosophy

1. **Language-agnostic.** Requirements describe observable behavior, not implementation details. "The solver finds two 16-entry LUT vectors" is a requirement; "Z3 via java-smt" is an implementation note.
2. **Verifiable.** Every requirement has acceptance criteria that translate directly to test assertions.
3. **Traceable.** Each requirement has a unique ID (e.g., `ENGINE-005`). Test names reference these IDs.
4. **Implementation notes welcome.** Language-specific optimization strategies belong in code comments, not in the spec. The spec says *what*, the code says *how*.

---

## Spec Files

| File | Prefix | Scope | Reqs |
|------|--------|-------|------|
| [01-core-engine.md](01-core-engine.md) | ENGINE | Engine interface, SIMD detection, literal encoding, storage, match output | 15 |
| [02-constraint-solver.md](02-constraint-solver.md) | SOLVE | Nibble matrix problem, LUT solving, auto-split, literal assignment | 8 |
| [03-utf8-pipeline.md](03-utf8-pipeline.md) | UTF8 | Multi-byte detection, classification, gating, range ops, decode | 12 |
| [04-vpa-filters.md](04-vpa-filters.md) | VPA | Chunk filters, prefix XOR/sum, carry propagation, working memory | 10 |
| [05-engine-compilation.md](05-engine-compilation.md) | COMP | Template specialization, constant folding, DCE, inlining, parity | 6 |
| [06-csv-parser.md](06-csv-parser.md) | CSV | RFC 4180 parsing, two-phase architecture, zero-copy fields | 14 |
| [07-performance.md](07-performance.md) | PERF | Throughput targets, benchmark methodology, allocation constraints | 8 |

**Total: 73 requirements** (across 7 documents)

---

## Priority Distribution

| Priority | Count | Meaning |
|----------|-------|---------|
| MUST     | 56    | Non-negotiable for conformance |
| SHOULD   | 15    | Expected unless technically infeasible |
| MAY      | 2     | Optional enhancements |

---

## Alphabetical Cross-Reference Index

| ID | Title | Priority | Depends On |
|----|-------|----------|------------|
| COMP-001 | Template Specialization | SHOULD | ENGINE-001 |
| COMP-002 | Constant Folding | SHOULD | COMP-001 |
| COMP-003 | Dead Code Elimination | SHOULD | COMP-002 |
| COMP-004 | Method Inlining | SHOULD | COMP-001 |
| COMP-005 | Compiled–Interpreted Parity | MUST | COMP-001, ENGINE-005 |
| COMP-006 | Filter Specialization | SHOULD | COMP-004, VPA-001 |
| CSV-001 | RFC 4180 Compliance | MUST | ENGINE-001 |
| CSV-002 | Two-Phase Architecture | MUST | ENGINE-001, VPA-001 |
| CSV-003 | Quote Filtering | MUST | VPA-003, VPA-006 |
| CSV-004 | Quote Overhead Bound | SHOULD | CSV-003 |
| CSV-005 | Configurable Delimiter | MUST | CSV-001 |
| CSV-006 | Configurable Quote Character | MUST | CSV-001 |
| CSV-007 | Header Detection | MUST | CSV-010 |
| CSV-008 | Zero-Copy Fields | MUST | CSV-002 |
| CSV-009 | Escaped Quote Handling | MUST | CSV-003 |
| CSV-010 | Row Boundaries | MUST | ENGINE-005 |
| CSV-011 | Zero-Allocation Scan | SHOULD | ENGINE-008 |
| CSV-012 | Large Field Support | MUST | ENGINE-003 |
| CSV-013 | Empty Fields | MUST | CSV-010 |
| CSV-014 | Result Iteration | MUST | CSV-010 |
| ENGINE-001 | Engine Interface | MUST | — |
| ENGINE-002 | Literal Identity | MUST | — |
| ENGINE-003 | SIMD Chunk Processing | MUST | — |
| ENGINE-004 | Shuffle-Based Detection | MUST | ENGINE-003 |
| ENGINE-005 | Detection Correctness | MUST | ENGINE-004 |
| ENGINE-006 | No False Positives | MUST | ENGINE-004 |
| ENGINE-007 | Match Ordering | MUST | ENGINE-005 |
| ENGINE-008 | Storage Reuse | MUST | ENGINE-001 |
| ENGINE-009 | Auto-Growing Storage | MUST | ENGINE-008 |
| ENGINE-010 | Multi-Round Detection | MUST | ENGINE-004, SOLVE-001 |
| ENGINE-011 | Auto-Split | MUST | SOLVE-004 |
| ENGINE-012 | Literal Namespace Limits | MUST | ENGINE-002 |
| ENGINE-013 | Platform Vector Sizes | MUST | ENGINE-003 |
| ENGINE-014 | Engine Not Thread-Safe | MUST | ENGINE-001 |
| ENGINE-015 | Empty Input | MUST | ENGINE-001 |
| PERF-001 | ASCII Scan Throughput | SHOULD | ENGINE-005 |
| PERF-002 | Mixed UTF-8 Throughput | SHOULD | UTF8-001 |
| PERF-003 | CSV Parse Throughput | SHOULD | CSV-002 |
| PERF-004 | Sublinear Scaling | SHOULD | ENGINE-010 |
| PERF-005 | Benchmark Methodology | MUST | — |
| PERF-006 | Parameter Sweep | SHOULD | PERF-005 |
| PERF-007 | CSV Sweep | SHOULD | PERF-005, CSV-002 |
| PERF-008 | No Hot-Path Allocation | MUST | ENGINE-008 |
| SOLVE-001 | Nibble Matrix Problem | MUST | — |
| SOLVE-002 | Solution Existence | MUST | SOLVE-001 |
| SOLVE-003 | Group Capacity | MUST | SOLVE-001 |
| SOLVE-004 | Auto-Split Recursion | MUST | SOLVE-002 |
| SOLVE-005 | Split Capacity | MUST | SOLVE-004 |
| SOLVE-006 | Deterministic Output | MAY | SOLVE-001 |
| SOLVE-007 | Literal Assignment | MUST | ENGINE-002, SOLVE-001 |
| SOLVE-008 | Range Operation Bypass | MUST | ENGINE-002 |
| UTF8-001 | ASCII Fast Path | MUST | ENGINE-004 |
| UTF8-002 | Lead Byte Classification | MUST | ENGINE-003 |
| UTF8-003 | Multi-Byte Gating | MUST | UTF8-002 |
| UTF8-004 | 2-Byte Codepoint Detection | MUST | UTF8-003 |
| UTF8-005 | 3-Byte Codepoint Detection | MUST | UTF8-003 |
| UTF8-006 | 4-Byte Codepoint Detection | MUST | UTF8-003 |
| UTF8-007 | Shared Lead Bytes | MUST | UTF8-003 |
| UTF8-008 | Boundary Spanning | MUST | ENGINE-003, UTF8-003 |
| UTF8-009 | Range Operations | MUST | SOLVE-008 |
| UTF8-010 | Combined Round Results | MUST | ENGINE-010 |
| UTF8-011 | Platform-Adaptive Decode | MUST | ENGINE-013 |
| UTF8-012 | Fast Rejection | SHOULD | ENGINE-003 |
| VPA-001 | Chunk Filter Interface | MUST | ENGINE-003 |
| VPA-002 | Filter State | MUST | VPA-001 |
| VPA-003 | Prefix XOR | MUST | VPA-001 |
| VPA-004 | Prefix Sum | MUST | VPA-001 |
| VPA-005 | Carry Propagation | MUST | VPA-002 |
| VPA-006 | Filter Zeroing | MUST | VPA-001 |
| VPA-007 | No-Op Default | MUST | VPA-001 |
| VPA-008 | Filter Composability | MAY | VPA-001 |
| VPA-009 | Working Memory Contract | MUST | VPA-001 |
| VPA-010 | Fast Path Skip | SHOULD | VPA-005 |

---

## Coverage Matrix

*To be populated as test suites reference spec IDs.*

| ID | Java Test | Rust Test |
|----|-----------|-----------|
| ENGINE-001 | `Utf8EngineTest`, `CompiledEngineTest` | — |
| ENGINE-005 | `RegexParityTest`, `FuzzRegexParityTest` | — |
| SOLVE-001 | `LiteralCompilerTest` | — |
| CSV-001 | `CsvParserTest` | — |
| COMP-005 | `CompiledEngineTest` | — |
| VPA-003 | `VpaKernelTest` | — |
