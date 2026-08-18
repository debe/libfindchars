# 00 — Specification Index

This specification defines the observable contract for **libfindchars**, a high-performance character detection library that uses SIMD instructions to find ASCII and multi-byte UTF-8 characters in byte sequences. Implementations in any language must satisfy these requirements to be considered conforming.

---

## Implementations

| Language | Location   | Runtime        | Maturity   |
|----------|------------|----------------|------------|
| Java 25  | `java/`    | JDK Vector API | Production |
| Rust     | `rust/`    | `std::arch`    | Production |

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

Maps each requirement to the test(s) that exercise it. The Rust column reflects
the test suites under `rust/`; `solver::tests` and `vpa::prefix` are inline
`#[cfg(test)]` modules, `sweep`/`csv_sweep` are Criterion benchmarks, and all
other names are integration tests under `tests/`. The Java column maps at
class or `Class.method` granularity, since Java test names do not encode spec
IDs; `SweepBenchmark`, `CsvSweepBenchmark`, and `Utf8Benchmark` are JMH
benchmarks in `libfindchars-bench`.

| ID | Java Test | Rust Test |
|----|-----------|-----------|
| ENGINE-001 | `Utf8EngineTest`, `CompiledEngineTest` | `engine_test::engine_001` |
| ENGINE-002 | `Utf8EngineTest.literalMapReturnsDistinctBytes`, `LiteralCompilerTest.testCompileMultiple` | `engine_test::engine_002_distinct_literal_bytes`, `engine_002_literal_map_names` |
| ENGINE-003 | `Utf8EngineTest.multiByteAtBufferEnd`, `RegexParityTest.engineMatchesRegexOnRealFile` (unpadded 3 MB input) | `parity_test` (exact-chunk / chunk-plus-one / multi-chunk), `fuzz_parity_test` |
| ENGINE-004 | `LiteralCompilerTest` (AND-of-LUT assertions), `FuzzRegexParityTest` | `solver::tests`, `engine_test::engine_005_all_targets_detected` |
| ENGINE-005 | `RegexParityTest`, `FuzzRegexParityTest` | `engine_test::engine_005_*`, `parity_test`, `fuzz_parity_test::fuzz_parity_ascii` |
| ENGINE-006 | `RegexParityTest`, `FuzzRegexParityTest`, `Utf8EngineTest.noFalsePositivesOnPartialSequence` | `engine_test::engine_006_*`, `fuzz_parity_test::fuzz_parity_ascii` |
| ENGINE-007 | `CompiledEngineTest.compiledEngineFindsAllMatches`, `BytecodeInlinerTest.inlinedEngineProducesCorrectResults` | `engine_test::engine_007_ascending_positions` |
| ENGINE-008 | `CsvParserTest.storageReuseAcrossParses` | `engine_test::engine_008_storage_reuse` |
| ENGINE-009 | `MatchStorageTest` | `engine_test::engine_009_auto_growing_storage` |
| ENGINE-010 | `RegexParityTest`, `Utf8EngineTest.mixedAsciiAndMultiByte`, `CompiledEngineTest.compiledEngineWithMultipleGroups` | `utf8_test::utf8_005_3byte_detection`, `utf8_006_4byte_detection`, `fuzz_parity_test::fuzz_parity_multibyte` |
| ENGINE-011 | `CompiledEngineTest.compiledEngineWithMultipleGroups`, `RegexParityTest` (23 ASCII targets force recursive auto-split) | `solver::tests::auto_split_two_groups`, `auto_split_many_literals`, `fuzz_parity_test::fuzz_parity_ascii` |
| ENGINE-012 | — *(gap)* | `engine_test::engine_012_namespace_limit_scalar` |
| ENGINE-013 | `Utf8EngineTest` (pinned `SPECIES_256`; rest of suite runs `SPECIES_PREFERRED`) | `parity_test` (scalar 16 B vs AVX2 32 B), `backend_csv_test` |
| ENGINE-014 | — *(gap)* | `engine_test::engine_014_separate_instances_parallel` |
| ENGINE-015 | `CsvParserTest.emptyInput`, `CsvParserTest.emptyFileWithHeader` | `engine_test::engine_015_empty_input`, `csv_test::csv_empty_input` |
| SOLVE-001 | `LiteralCompilerTest` | `solver::tests::solve_single_literal`, `solve_multiple_literals`, `solve_csv_characters` |
| SOLVE-002 | `FuzzRegexParityTest` (unsolvable rounds fail with a clear error, skipped) | `solver::tests::solve_8_ascii_targets`, `solve_multiple_literals` |
| SOLVE-003 | `LiteralCompilerTest.testCompile`, `LiteralCompilerTest.testCompileOneBig` | `solver::tests::solve_8_ascii_targets` |
| SOLVE-004 | `CompiledEngineTest.compiledEngineWithMultipleGroups`, `BytecodeInlinerTest.inlinedEngineWithMultipleGroups` | `solver::tests::auto_split_two_groups`, `auto_split_many_literals` |
| SOLVE-005 | `RegexParityTest`, `CompiledEngineTest.compiledEngineWithMultipleGroups` (23 ASCII targets) | `solver::tests::auto_split_many_literals` |
| SOLVE-006 | — *(gap)* | — *(gap)* |
| SOLVE-007 | `Utf8EngineTest.literalMapReturnsDistinctBytes`, `LiteralCompilerTest.testCompileMultiple` | `solver::tests::solve_respects_used_literals`, `engine_test::engine_002_distinct_literal_bytes` |
| SOLVE-008 | `CompiledEngineTest.compiledEngineFindsAllMatches`, `Utf8EngineTest.mixedAsciiAndSharedLeadByteMultiByte` | `engine_test::range_detection`, `range_plus_shuffle`, `parity_test::parity_range` |
| UTF8-001 | `Utf8EngineTest` | `utf8_test::utf8_001_ascii_fast_path` |
| UTF8-002 | `Utf8EngineTest.twoByteChar`, `FuzzRegexParityTest` | `utf8_test::utf8_004_2byte_detection`, `fuzz_parity_test::fuzz_parity_multibyte` |
| UTF8-003 | `Utf8EngineTest.twoByteChar`, `noCrossByteContamination`, `noFalsePositivesOnPartialSequence` | `utf8_test::utf8_no_false_positives_at_continuations`, `utf8_004_2byte_detection` |
| UTF8-004 | `Utf8EngineTest.twoByteChar`, `adjacentMultiByteChars`, `FuzzRegexParityTest` | `utf8_test::utf8_004_2byte_detection`, `utf8_004_2byte_multiple`, `fuzz_parity_test::fuzz_parity_multibyte` |
| UTF8-005 | `Utf8EngineTest.threeByteChar`, `FuzzRegexParityTest` | `utf8_test::utf8_005_3byte_detection`, `fuzz_parity_test::fuzz_parity_multibyte` |
| UTF8-006 | `Utf8EngineTest.fourByteChar` | `utf8_test::utf8_006_4byte_detection`, `fuzz_parity_test::fuzz_parity_multibyte` |
| UTF8-007 | `Utf8EngineTest.sharedLeadByteTwoByteChars`, `multipleSharedLeadByte2ByteChars`, `sharedLeadByte3ByteChars` | `utf8_test::utf8_007_shared_lead_bytes` |
| UTF8-008 | `Utf8EngineTest.boundarySpanning`, `multiByteAtBufferEnd` | `fuzz_parity_test::fuzz_parity_multibyte` |
| UTF8-009 | `Utf8EngineTest.mixedAsciiAndSharedLeadByteMultiByte`, `CompiledEngineTest.compiledEngineFindsAllMatches` | `engine_test::range_detection`, `range_plus_shuffle` |
| UTF8-010 | `Utf8EngineTest.mixedAsciiAndMultiByte`, `RegexParityTest` | `utf8_test::utf8_005_3byte_detection`, `utf8_006_4byte_detection`, `fuzz_parity_test::fuzz_parity_multibyte` |
| UTF8-011 | — *(gap)* | `utf8_test` (scalar decode), `fuzz_parity_test::fuzz_parity_multibyte` (native decode) |
| UTF8-012 | — *(gap)* | — *(gap)* |
| VPA-001 | `CompiledEngineTest.filteredEngineParityAcrossAllModes` (`apply` vs `applyStatic`), `CsvQuoteFilterTest` | `vpa_test::vpa_001_filter_receives_accumulator` |
| VPA-002 | `CompiledEngineTest.filteredEngineParityAcrossAllModes`, `CsvQuoteFilterTest.crossChunkQuoteCarryHandled` | `vpa_test::vpa_002_state_reset_between_calls` |
| VPA-003 | `VpaKernelTest` | `vpa_test::vpa_003_quote_filter_suppresses_commas`, `vpa::prefix::tests` (`test_prefix_xor_*`) |
| VPA-004 | `VpaKernelTest.prefixSumCountsUpAndDown`, `prefixSumMonotonicIncrease` | `vpa::prefix::tests::test_prefix_sum_basic`, `test_prefix_sum_with_decrements` |
| VPA-005 | `CsvParserTest.crossChunkQuoteCarry`, `CsvQuoteFilterTest.crossChunkQuoteCarryHandled` | `vpa_test::vpa_005_carry_across_chunks`, `vpa::prefix::tests::test_prefix_xor_with_carry` |
| VPA-006 | `CsvQuoteFilterTest.quotedFieldsHideCommasFromParser`, `quotedFieldsHideNewlinesFromParser` | `vpa_test::vpa_003_quote_filter_suppresses_commas` |
| VPA-007 | `CompiledEngineTest.jitModeFindsAllMatches`, `RegexParityTest` (default no-op filter path) | `vpa_test::vpa_007_no_filter_passthrough` |
| VPA-008 | — *(gap)* | — *(gap)* |
| VPA-009 | `CompiledEngineTest.filteredEngineParityAcrossAllModes`, `CsvQuoteFilterTest` | `vpa_test::vpa_001_filter_receives_accumulator`, `vpa_002_state_reset_between_calls` |
| VPA-010 | `CsvQuoteFilterTest.noQuotesNoCarryFastPath` | `vpa_test::vpa_001_filter_receives_accumulator` |
| COMP-001 | `CompiledEngineTest` (BYTECODE_INLINE / JIT / AOT modes) | n/a — Rust specializes via monomorphization, not a runtime template transform |
| COMP-002 | — *(gap)* | n/a — handled by the Rust compiler |
| COMP-003 | — *(gap)* | n/a — handled by the Rust compiler |
| COMP-004 | `BytecodeInlinerTest` | n/a — handled by the Rust compiler |
| COMP-005 | `CompiledEngineTest` | `parity_test`, `fuzz_parity_test` (scalar reference vs SIMD backend) |
| COMP-006 | `CompiledEngineTest.filteredEngineParityAcrossAllModes`, `CsvQuoteFilterTest` | `vpa_test`, `backend_csv_test` |
| CSV-001 | `CsvParserTest` | `csv_test::csv_001_basic_parsing`, `csv_001_quoted_fields` |
| CSV-002 | `CsvQuoteFilterTest.quotedFieldsHideCommasFromParser`, `CsvParserTest.quotedFieldWithComma` | `csv_test::csv_003_comma_inside_quotes` (quote filter + match walk) |
| CSV-003 | `CsvParserTest.quotedFieldWithComma`, `quotedFieldWithNewline`, `CsvQuoteFilterTest` | `csv_test::csv_003_comma_inside_quotes`, `csv_003_newline_inside_quotes` |
| CSV-004 | — *(gap)* | — *(gap)* |
| CSV-005 | `CsvParserTest.customDelimiter` | `csv_test::csv_005_tab_delimiter` |
| CSV-006 | `CsvParserTest.customQuoteWithEscapedQuotes` | `csv_test::csv_006_single_quote_char`, `csv_006_escaped_single_quote`, `backend_csv_test::csv_006_single_quote_{scalar,avx2}` |
| CSV-007 | `CsvParserTest.headerParsing`, `headerOnlyFile` | `csv_test::csv_007_headers` |
| CSV-008 | `CsvParserTest.rawFieldReturnsCorrectSlice` | `csv_test::csv_008_raw_field` |
| CSV-009 | `CsvParserTest.escapedQuotes`, `customQuoteWithEscapedQuotes` | `csv_test::csv_009_escaped_quotes`, `backend_csv_test::csv_009_escaped_quotes_{scalar,avx2}` |
| CSV-010 | `CsvParserTest.crlfLineEndings`, `mixedLineEndings`, `noTrailingNewline` | `csv_test::csv_010_lf_rows`, `csv_010_crlf_rows`, `csv_010_no_trailing_newline` |
| CSV-011 | — *(gap)* | — *(gap)* |
| CSV-012 | `CsvParserTest.largeFieldSpanningMultipleVectors`, `CsvQuoteFilterTest.crossChunkQuoteCarryHandled` | `csv_test::csv_012_large_field`, `backend_csv_test::csv_012_large_field_{scalar,avx2}` |
| CSV-013 | `CsvParserTest.emptyFields`, `emptyQuotedField` | `csv_test::csv_013_empty_fields`, `csv_013_trailing_delimiter` |
| CSV-014 | `CsvParserTest.rowsMatchesIndexedAccess`, `streamMatchesRowCount` | `csv_test::csv_014_row_iteration` |
| PERF-001 | `SweepBenchmark` | `sweep::sweep_ascii_count`, `sweep_density` |
| PERF-002 | `Utf8Benchmark`, `SweepBenchmark` (multi-byte configs) | — *(gap)* |
| PERF-003 | `CsvSweepBenchmark` | `csv_sweep::csv_sweep_columns`, `csv_sweep_field_len` |
| PERF-004 | — *(gap)* | — *(gap)* |
| PERF-005 | `SweepBenchmark`, `CsvSweepBenchmark`, `Utf8Benchmark` (JMH harness) | `sweep`, `csv_sweep` (Criterion harness) |
| PERF-006 | `SweepBenchmark` | `sweep::sweep_ascii_count`, `sweep_density`, `sweep_range` |
| PERF-007 | `CsvSweepBenchmark` (FastCSV baseline) | `csv_sweep::csv_sweep_columns`, `csv_sweep_quotes`, `csv_sweep_field_len`, `csv_backend_compare` |
| PERF-008 | — *(gap)* | `perf_alloc_test::perf_008_no_hot_path_allocation`, `perf_008_autogrow_is_only_allocation` |

### Known Rust coverage gaps

The following requirements have no dedicated Rust test. They are *untested in
Rust*, not unimplemented — several are exercised indirectly by neighbouring tests:

| ID | Priority | Title |
|----|----------|-------|
| SOLVE-006 | MAY | Deterministic Output |
| UTF8-012 | SHOULD | Fast Rejection |
| VPA-008 | MAY | Filter Composability |
| CSV-004 | SHOULD | Quote Overhead Bound |
| CSV-011 | SHOULD | Zero-Allocation Scan |
| PERF-002 | SHOULD | Mixed UTF-8 Throughput |
| PERF-004 | SHOULD | Sublinear Scaling |

All **MUST** requirements are covered in Rust; the remaining gaps are SHOULD/MAY
and optional to close (CSV-011 can reuse the counting-allocator harness from
`perf_alloc_test` against `CsvParser::parse()`). COMP-001..004 are marked n/a:
they describe the Java bytecode specialization pipeline, which Rust achieves
through compile-time monomorphization rather than a runtime transform.

### Known Java coverage gaps

The following requirements have no dedicated Java test. They are *untested in
Java*, not unimplemented — several are exercised indirectly by neighbouring tests:

| ID | Priority | Title |
|----|----------|-------|
| **ENGINE-012** | **MUST** | **Literal Namespace Limits** |
| **ENGINE-014** | **MUST** | **Engine Not Thread-Safe** |
| SOLVE-006 | MAY | Deterministic Output |
| **UTF8-011** | **MUST** | **Platform-Adaptive Decode** |
| UTF8-012 | SHOULD | Fast Rejection |
| VPA-008 | MAY | Filter Composability |
| COMP-002 | SHOULD | Constant Folding |
| COMP-003 | SHOULD | Dead Code Elimination |
| CSV-004 | SHOULD | Quote Overhead Bound |
| CSV-011 | SHOULD | Zero-Allocation Scan |
| PERF-004 | SHOULD | Sublinear Scaling |
| **PERF-008** | **MUST** | **No Hot-Path Allocation** |

Java covers 61 of 73 requirements. Three of the four MUST gaps are implemented
but merely untested: literal IDs are already solver-constrained to
`[1, vectorByteSize - 1]` (ENGINE-012), non-thread-safety is documented and
separate instances work — verified only sequentially by
`CsvParserTest.newInstanceSharesEngine` (ENGINE-014) — and the detection loop is
allocation-free by design (PERF-008). UTF8-011 is different: the AVX-512
compress decode path is not only untested but has a known `MatchDecoder`
truncation bug (see the species note in `Utf8EngineTest`, which pins
`SPECIES_256` to avoid it).
