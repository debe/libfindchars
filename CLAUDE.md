# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

libfindchars is a high-performance character detection library that uses SIMD instructions to find ASCII and multi-byte UTF-8 characters in byte sequences at ~2 GB/s per core. A constraint solver finds optimal shuffle mask configurations at build time; a template-specialized engine executes the operations at runtime.

Key innovation: the solver finds two 16-entry shuffle vectors whose AND yields a unique literal byte for every target character and zero for everything else. A single shuffle group reliably solves ~12 ASCII literals; auto-split doubles that by solving two independent halves.

## Repository Structure

```
libfindchars/
├── java/                    # Java 25 implementation (production)
│   ├── pom.xml              # Multi-module Maven parent POM
│   ├── mvnw                 # Maven wrapper
│   ├── libfindchars-api/    # Runtime engine, API, @Inline annotation
│   ├── libfindchars-compiler/ # Z3 solver, bytecode inliner
│   ├── libfindchars-csv/    # SIMD CSV parser
│   ├── libfindchars-bench/  # JMH benchmarks
│   └── libfindchars-examples/ # Usage examples
├── spec/                    # Language-agnostic specification (73 requirements)
├── scripts/                 # Release, benchmark, and analysis scripts
├── docs/                    # Performance visualizations and sweep data
├── .github/                 # CI/CD workflows
└── .sentrux/                # Architecture constraint rules
```

## Specification

The `spec/` directory contains the language-agnostic specification (73 requirements across 7 documents). Any conforming implementation (Java, Rust, etc.) must satisfy these requirements.

| File | Prefix | Scope |
|------|--------|-------|
| `spec/01-core-engine.md` | ENGINE | Engine interface, SIMD detection, storage |
| `spec/02-constraint-solver.md` | SOLVE | Nibble matrix, LUT solving, auto-split |
| `spec/03-utf8-pipeline.md` | UTF8 | Multi-byte detection, gating, decode |
| `spec/04-vpa-filters.md` | VPA | Chunk filters, prefix XOR/sum |
| `spec/05-engine-compilation.md` | COMP | Template specialization, parity |
| `spec/06-csv-parser.md` | CSV | RFC 4180 parsing, zero-copy fields |
| `spec/07-performance.md` | PERF | Throughput targets, benchmarks |

## Build Commands

All Java build commands run from the `java/` directory:

```bash
# Build entire project (requires JDK 25)
cd java && ./mvnw clean install

# Build without tests
cd java && ./mvnw clean install -DskipTests

# Run all tests
cd java && ./mvnw test

# Run tests for a specific module
cd java && ./mvnw test -pl libfindchars-compiler

# Run a single test class
cd java && ./mvnw test -Dtest=LiteralCompilerTest -pl libfindchars-compiler

# Run a single test method
cd java && ./mvnw test -Dtest=LiteralCompilerTest#testMethodName -pl libfindchars-compiler

# Run CSV sweep benchmark (from repo root — scripts handle cd)
scripts/run-csv-sweep.sh              # Full: 3D sweep (columns, quote%, field length)
scripts/run-csv-sweep.sh --quick      # Smoke test (1 fork, 1 warmup, 1 measurement)
scripts/run-csv-sweep.sh --perfnorm   # With hardware counters (Linux only)
```

**Important**: Requires **JDK 25** with `--enable-preview` and `--add-modules=jdk.incubator.vector`. Maven Surefire is pre-configured with these JVM args. The build compiles with `--release 25`. Maven coordinates: `org.knownhosts:libfindchars-compiler:0.4.0-jdk25-preview`.

**macOS note**: Set `JAVA_HOME` explicitly if the default JDK is not 25:
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
```

## Module Architecture

Multi-module Maven build (`0.4.0-jdk25-preview`). All modules live under `java/`. Dependency graph:

```
api (no deps)
 ^
 |--- compiler (Z3 solver + bytecode inliner)
 |      ^
 |      |--- csv (SIMD CSV parser)
 |      |      ^
 |      |      |--- bench (JMH benchmarks, FastCSV comparison)
 |      |
 |      |--- examples (usage demos)
 |
 |--- bench (also depends on api directly)
```

Published to Maven Central: `libfindchars-api`, `libfindchars-compiler`. Skipped: csv, examples, bench.

### libfindchars-api
Core runtime engine, API, and `@Inline` annotation (9 classes):
- `FindEngine` — Interface: `MatchView find(MemorySegment data, MatchStorage storage)`. Not thread-safe (mutable decode buffers and filter state).
- `Utf8EngineTemplate` — Readable Java template implementing `FindEngine`. Serves as both C2 JIT fallback (works as-is) and compiled engine template (specialized by `TemplateTransformer`). Contains SIMD detection rounds, range operations, multi-byte gating, chunk filter injection, and platform-adaptive decode.
- `Utf8Kernel` — Static SIMD helpers (all `@Inline`): shuffle, cleanLit, classify, hasNonAscii, gateAscii/gateMultiByte2/3/4, rangeMatch, combineRounds, decode.
- `VpaKernel` — Visibly Pushdown Automaton primitives: `prefixXor()` (1-bit toggle, CSV quotes), `prefixSum()` (8-bit depth, bracket nesting), `shiftR()` (shuffle helper). All use Hillis-Steele parallel prefix in O(log₂ V) vector steps. Uses `VectorShuffle.iota()` to avoid lambda synthetic methods in hidden classes.
- `ChunkFilter` — Marker interface for VPA chunk filters. Implementations must provide `@Inline public static ByteVector apply(accumulator, zero, species, state, scratchpad, literals)`.
- `NoOpChunkFilter` — Default no-op filter. Bytecode-rewritten to user's filter class before inlining.
- `@Inline` — `@Target(METHOD, FIELD)` annotation for bytecode inlining (methods) and constant folding (int fields). `maxDepth()` configurable.
- `MatchStorage` — Auto-growing dual buffer (int[] positions, byte[] literals). Reusable across calls.
- `MatchView` — Immutable view: `size()`, `getPositionAt(storage, i)`, `getLiteralAt(storage, i)`.

### libfindchars-compiler
Compiles character detection requirements into optimized engines (11 classes across 3 packages):

**`compiler` package** — Z3 constraint solving:
- `LiteralCompiler` — Heart of the system. Uses Z3 via java-smt to solve 16x16 nibble-matrix constraints. Each byte splits into low/high nibble; Z3 finds two shuffle LUTs whose AND yields unique literal per target character.
- `AsciiFindMask` — Z3 result: low/high LUT arrays + literal byte map.
- `AsciiLiteralGroup` — Input: named group of target ASCII characters.
- `ByteLiteral` — Named literal with target characters.

**`compiler.inline` package** — Bytecode specialization (JDK ClassFile API):
- `TemplateTransformer` — Orchestrates: constant fold → dead code eliminate → private method inline → filter owner rewrite.
- `BytecodeInliner` — Inlines `@Inline` static methods at `invokestatic` sites.
- `MethodInliner` — Core transplant algorithm: slot/label remapping, register allocation.
- `ConstantFolder` — Replaces `@Inline int` field loads with compile-time constants.
- `DeadCodeEliminator` — Removes unreachable code after constant folding.
- `SpecializationConfig` — Config record for template specialization.

**`generator` package**:
- `Utf8EngineBuilder` — Fluent API: `.codepoints("name", chars...)`, `.codepoint("name", cp)`, `.range("name", from, to)`, `.chunkFilter(Class, bindings...)`, `.compiled(bool)`. Returns `Utf8BuildResult(FindEngine, Map<String, Byte>)`.

**Engine construction flow**:
1. Define character sets via builder API
2. `LiteralCompiler.solve()` invokes Z3 per round, with auto-split on failure
3. Range operations get non-conflicting literal IDs outside nibble matrix
4. `TemplateTransformer.transform()` specializes `Utf8EngineTemplate` (constant folding, DCE, private method inlining)
5. `BytecodeInliner.inline()` transplants `Utf8Kernel` bodies
6. If filter configured: `rewriteFilterOwner()` swaps `NoOpChunkFilter` → user class, then inlines filter + VpaKernel
7. `defineHiddenClass()` loads specialized bytecode → ready `FindEngine`

### libfindchars-csv
SIMD-accelerated CSV parser (~1.8 GB/s scan, ~25% faster than FastCSV). Two-phase architecture:
- **Phase 1**: Engine detects `,` `"` `\n` `\r`. `CsvQuoteFilter` zeros out structural chars inside quoted regions using vectorized prefix XOR (4.4% overhead).
- **Phase 2**: Linear match walker — every comma is a field boundary, every newline is a row boundary. No state machine.

Classes (7):
- `CsvParser` — Builder: `.delimiter()`, `.quote()`, `.hasHeader()`. API: `scan(MemorySegment)` → zero-alloc `CsvMatchView`; `parse(MemorySegment|byte[]|Path)` → `CsvResult` with zero-copy field boundaries. `newInstance()` shares the compiled engine with fresh storage (useful for benchmarking per-parse cost).
- `CsvQuoteFilter` — `ChunkFilter` using `VpaKernel.prefixXor()`. Fast path: skips prefix computation when no quotes and no carry.
- `CsvMatchView` — Zero-allocation view: `size()`, `positionAt(i)`, `tokenAt(i)`, `rowCount()` (lazy).
- `CsvResult` — Flat-array backed result: `rowCount()`, `row(i)` (lazy `CsvRow` view), `headers()`, `stream()`, `rows()` (materializes array). Holds `fieldStarts/fieldEnds/fieldFlags/rowFieldOffset` internally; zero allocation until field access.
- `CsvRow` — Zero-copy row: `fieldCount()`, `get(col)` (materializes String), `field(col)`, `rawField(col)` (MemorySegment slice).
- `CsvField` — Record: `startOffset`, `endOffset`, `quoted`, `quoteByte`. `value(data)` handles unescape (`""` → `"`). `rawSlice(data)` for zero-copy.
- `CsvToken` — Sealed interface: `Quote`, `Delimiter`, `Newline`, `Cr` singleton records.

### libfindchars-examples
Usage examples in `zz.customname` package:
- `FindLiteralsAndPositions` — ASCII codepoints + ranges, file-mapped matching
- `FindUtf8Characters` — 2-byte (e), 3-byte (trademark), 4-byte (emoji) detection

### libfindchars-bench
JMH benchmarks and profiling harnesses:
- `SweepBenchmark` — 4D parameter sweep (ASCII count, density, multi-byte count, groups). Composite `@Param` string format `asciiCount-density-multiByteCount-groups`.
- `CsvSweepBenchmark` — 3D CSV sweep (columns, quote%, field length) with FastCSV comparison. Composite `@Param` string format `columns-quotePercent-avgFieldLen`. 100 MB data.
- `CsvDataGenerator` — Deterministic CSV generation with configurable columns, quote%, CRLF, field length.
- `BenchDataGenerator` — Parameterized test data with target match densities.

Test classes: `DataGenerationTest`.

## Key Design Patterns

**Unified UTF-8 Pipeline**: Single entry point via `Utf8EngineBuilder`. The UTF-8 engine is a superset of ASCII — its fast path handles pure-ASCII data efficiently (skips classify and multi-byte gating), while supporting multi-byte characters and range operations in the same engine.

**Z3 Constraint Solving**: Character detection is modeled as a satisfiability problem. The compiler feeds hundreds of bitwise constraints into Z3 to find two 16-entry shuffle vectors per group. Auto-split recursion handles groups too large for a single solve. Literal IDs share range `[1, vectorByteSize-1]`.

**Annotation-Driven Template**: `Utf8EngineTemplate` is readable Java annotated with `@Inline`. `TemplateTransformer` constant-folds `@Inline int` fields, eliminates dead code, and inlines `@Inline` private methods. Then `BytecodeInliner` transplants `@Inline` static method bodies from `Utf8Kernel`, producing flat zero-overhead bytecode.

**VPA Chunk Filters**: Stateful per-chunk filtering via `chunkFilter()`. A `ChunkFilter` provides an `@Inline static apply()` method running between SIMD detection and position decode. `VpaKernel` gives reusable parallel prefix primitives in O(log₂ V) steps. The engine manages all working memory (`state[8]`, `scratchpad[vbs]`, `literals[]`).

**Platform-Adaptive Decode**: AVX-512 uses `VPCOMPRESSB` (single instruction). ARM/AVX2 uses `intoArray()` + scalar scatter to avoid `compress()` lambda fallback that causes `IllegalAccessError` in hidden classes. `anyTrue()` maps to a single NEON UMAXV for fast rejection of empty chunks.

**Zero-Copy Architecture**: CSV results hold offsets into the original `MemorySegment`. String materialization is deferred until `CsvField.value()` is called. `CsvMatchView` iterates flat literal buffers without allocation.

## Testing

**Framework**: JUnit 5 (Jupiter). Test patterns:
- Unit tests: builder setup → engine.find() → assert positions and literal bytes
- Parity tests: SIMD engine output validated against java.util.regex
- Fuzz tests: 50 rounds with seeded RNG, validates solver limits gracefully
- CSV tests: RFC 4180 compliance, edge cases (escaped quotes, CRLF, large fields, 100+ columns)

**Key test classes**:
- `LiteralCompilerTest` — Z3 constraint solving
- `Utf8EngineTest` — Multi-byte UTF-8 (2/3/4 byte, shared lead bytes, boundary spanning)
- `CompiledEngineTest` — Compiled hidden class vs template
- `RegexParityTest` / `FuzzRegexParityTest` — Equivalence vs regex
- `BytecodeInlinerTest` — Bytecode specialization
- `CsvParserTest` — 16 CSV parsing tests

## CI/CD

**GitHub Actions** (`.github/workflows/build.yml`):
- Triggers: push to main, PRs to main
- Runner: ubuntu-latest, JDK 25-ea (Temurin), 15-min timeout
- Z3: Downloads native `.so` files from Maven Central (not bundled on Linux)
- Build: `cd java && LD_LIBRARY_PATH=$PWD/native ./mvnw verify`
- Concurrency: cancels in-progress on new push to same ref

**Dependabot**: Weekly Maven dependency updates (scans `java/pom.xml`).

## Scripts

```bash
# Release Java to Maven Central (builds, signs, uploads, tags, creates GitHub release)
scripts/release-java.sh 0.4.1-jdk25-preview
scripts/release-java.sh --dry-run 0.4.1-jdk25-preview

# Parameter sweep benchmarks (~6 min full, ~1 min quick)
scripts/run-sweep.sh              # Full: 4D sweep (ascii, density, mb, groups)
scripts/run-sweep.sh --quick      # Smoke test (1 fork, 1 warmup, 1 measurement)
scripts/run-sweep.sh --perfnorm   # With hardware counters (Linux only)

# CSV sweep benchmarks (SIMD scan/parse vs FastCSV)
scripts/run-csv-sweep.sh              # Full: 3D sweep (columns, quote%, field length)
scripts/run-csv-sweep.sh --quick      # Smoke test (1 fork, 1 warmup, 1 measurement)
scripts/run-csv-sweep.sh --perfnorm   # With hardware counters (Linux only)

# Cost model fitting (requires numpy)
python3 scripts/fit-cost-model.py docs/sweep-data/

# Regenerate plots
gnuplot java/libfindchars-bench/sweep-overview.gnuplot
gnuplot java/libfindchars-bench/csv-sweep-overview.gnuplot
```

## Releasing

**Version format**: `{semver}-jdk{N}-preview` (e.g. `0.4.0-jdk25-preview`). Drop `-jdk25-preview` suffix when Vector API graduates from incubator.

**Tag format**: `v{version}` (e.g. `v0.4.0-jdk25-preview`).

**Published artifacts**: `libfindchars-api`, `libfindchars-compiler`. CSV, examples, and bench skip deployment.

**Prerequisites**:
- GPG signing key available to `gpg-agent`
- `~/.m2/settings.xml` with `<server id="central">` credentials (Central Portal token)
- `gh auth login` (GitHub CLI authenticated)

## Architecture Constraints

Enforced via `.sentrux/rules.toml` (paths prefixed with `java/`):
- **Layers**: api (L0) → compiler, csv (L1) → examples, bench (L4)
- **Boundaries**: api must not depend on compiler/examples/bench; compiler must not depend on examples/bench
- **No cycles** (max_cycles=0, currently perfect)
- **max_cc=25**, **max_fn_lines=100** (Utf8EngineTemplate and LiteralCompiler exceed by design)

## Solver Limits

- **Per shuffle group**: ~12 ASCII literals. Auto-split doubles to ~20-24.
- **Literal namespace**: `[1, vectorByteSize-1]`. AVX-512: 63 max. NEON: 15 max.
- **Range operations**: 1 literal each, evaluated separately from nibble matrix.
- **Multi-byte codepoints**: 1 literal each (reuse IDs across rounds).

## Development Notes

- `--add-modules=jdk.incubator.vector` required for compilation and test execution
- Maven Surefire pre-configured with `--enable-preview`, `--add-modules`, `--enable-native-access`
- Z3 native libraries bundled via `javasmt-solver-z3` on macOS; downloaded in CI for Linux
- Literal byte values are determined at runtime by Z3 (not compile-time constants)
- `Utf8EngineTemplate` is **not thread-safe** (mutable `decodeTmp`, `filterState` fields)
- `MatchStorage` is reusable across `find()` calls (engine overwrites buffers)
- When running bench tests standalone, build csv module first or use `-am` flag from reactor root
