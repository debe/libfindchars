# Changelog

All notable changes to libfindchars are documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Versioning: the **Java** implementation uses `{semver}-jdk{N}` until the
Vector API graduates from incubator (the `-preview` suffix was dropped in
0.6.0 — no preview features are used). The **Rust** crates (`findchars`,
`findchars-solver`, `findchars-csv`) use plain semver and version independently
on crates.io.

## [Unreleased]

### Java

#### Added
- **`CompilationMode`** (`BYTECODE_INLINE` / `JIT` / `AOT`) replacing the
  boolean `compiled` flag; `Utf8EngineBuilder.compiled(boolean)` is deprecated
  for removal. `ChunkFilter` became a functional interface with instance
  `apply()` for JIT/AOT dispatch and `@Inline applyStatic()` for bytecode
  inlining; `TemplateTransformer` gained a `devirtualizeFilter` pass.
- `Automatic-Module-Name` manifest entries (`org.knownhosts.libfindchars.api`,
  `org.knownhosts.libfindchars.compiler`) for JPMS consumers.
- Runnable examples via `exec-maven-plugin` in `libfindchars-examples`
  (package `org.knownhosts.libfindchars.examples`).

#### Changed
- **Dropped `--enable-preview`** from compilation, tests, javadoc, and JMH
  forks. No preview features are used — the artifact needs only
  `--add-modules=jdk.incubator.vector` and runs on JDK 25 and 26. The version
  scheme becomes `{semver}-jdk{N}` (e.g. `0.6.0-jdk25`).
- `logback-classic` moved to test scope — the library no longer forces a
  logging backend on consumers (`slf4j-api` remains the compile-scope API).

#### Dependencies
- JUnit 5 → 6.1.0, java-smt 6.0.0, javasmt-solver-z3 4.16.0, fastcsv 4.3.0

### Rust

#### Added
- **Rust implementation** — `findchars`, `findchars-solver`, and `findchars-csv`
  crates conforming to the shared `spec/`. Auto-detected SIMD backends (AVX-512
  with VBMI2, AVX2, NEON, scalar), Z3 constraint solver, multi-byte UTF-8
  pipeline, VPA chunk filters, and a SIMD CSV parser. Targeted for an initial
  crates.io release as `0.1.0`.
- Rust usage examples (`findchars-examples`): character detection, multi-byte
  UTF-8 detection, and CSV parsing.
- Rust fuzz/parity test: seeded rounds validating every CPU-supported SIMD
  backend against a linear-scan oracle (ASCII) and constructed multi-byte data.
- Spec conformance tests closing the last MUST gaps: CSV-006 (configurable
  quote character) and PERF-008 (zero hot-path allocation, proven with a
  counting global allocator). All 73-requirement MUSTs are now covered.
- `scripts/release-rust.sh` — publishes the Rust crates to crates.io.
- `rust/README.md` plus per-crate READMEs for crates.io.

#### Changed
- Crates relicensed **MIT → Apache-2.0** before first publish, matching the
  repository license and the Java artifacts (never published as MIT, so there
  is no downstream impact).
- Complete crates.io metadata: keywords, categories, `readme`, `documentation`,
  `homepage`, bundled LICENSE files.

## [0.5.0-jdk25-preview] — 2026-03-31

### Added
- **libfindchars-csv** — SIMD-accelerated CSV parser (~0.9–1.3 GB/s full parse per core, 2–3x faster than FastCSV). Builder API, zero-copy field access via `MemorySegment` offsets, memory-mapped file support.
- **VPA chunk filter framework** — stateful per-chunk filtering between SIMD detection and decode. `VpaKernel` provides `prefixXor` (toggle state) and `prefixSum` (depth tracking) as Hillis-Steele parallel prefix primitives in O(log₂ V) steps. `CsvQuoteFilter` is the first built-in filter.
- **Platform-adaptive decode** — AVX-512 uses `VPCOMPRESSB`; ARM/AVX2 uses `intoArray()` + scatter to avoid `compress()` lambda fallback in hidden classes. `anyTrue()` guard defers expensive `toLong()`.
- **`cleanLUT` shuffle** — `selectFrom()` replaces per-literal compare+add loops, O(1) per group.
- **CSV parameter sweep benchmark** — 3D JMH sweep (columns, quote%, field length) with FastCSV comparison, gnuplot visualization, and run scripts.
- Architectural constraints via `.sentrux/rules.toml`
- New tests: `MatchStorageTest`, `VpaKernelTest`, 10 new `CsvParserTest` cases (28 total)

### Changed
- **Breaking: position type `int` → `long`** — `MatchView.getPositionAt()` now returns `long`, enabling files >2 GB
- **CSV result architecture** — `CsvResult` refactored from record with object-per-field to flat-array backed class (`fieldStarts/fieldEnds/fieldFlags/rowFieldOffset`). Lazy `CsvRow` views, zero allocation until field access.
- **Incremental buffer growth** — `Utf8EngineTemplate` allocates `dataSize/10` initially and grows per-chunk, replacing worst-case `dataSize` pre-allocation. ~10x memory reduction for large files.
- **`CsvParser.newInstance()`** — shares compiled engine with fresh storage for independent parse results (useful for benchmarking per-parse cost).
- Refactored `LiteralCompiler`, `Utf8EngineBuilder`, and bytecode inliner pipeline for readability
- `DeadCodeEliminator` now resolves single-operand constant branches (`ifeq`/`ifne`/etc.)
- Replaced ad-hoc CSV benchmarks (`CsvBenchmark`, `CsvManualProfile`, `CsvProfileTest`, `FastCsvComparisonTest`) with `CsvSweepBenchmark`

### Fixed
- Fuzz test skips unsolvable random configurations instead of failing
- CI: Z3 native library download, `--enable-native-access`, javasmt-solver-z3 4.14.0
- `parse-sweep.py` handles NaN scoreError from JMH

### Dependencies
- `logback-classic` 1.4.14 → 1.5.32
- Removed Spring Boot / Tomcat from bench module

## [0.4.0-jdk25-preview] — 2025-05-15

### Added
- Annotation-driven template specialization (`@Inline` on methods and fields)
- Bytecode pipeline: constant folding → DCE → method inlining via JDK 25 ClassFile API
- `Utf8EngineTemplate` — readable Java source that doubles as compiled engine template
- Hidden class engine loading via `MethodHandles.defineHiddenClass()`
- Parameter sweep benchmarks, cost model fitting, release automation

### Changed
- Migrated from JDK 22 to JDK 25 (`--release 25`)

## [0.3.0] — 2025-04-01

### Added
- UTF-8 multi-byte character detection (2/3/4-byte sequences)
- Per-round shuffle mask solving across continuation bytes
- JMH benchmarks for mixed ASCII/UTF-8 workloads

## [0.2.0] — 2025-03-15

### Added
- Bytecode-compiled SIMD engine with zero virtual dispatch
- `FindEngine` interface

## [0.1.0] — 2025-03-01

### Added
- Z3-based nibble matrix solver for ASCII character detection
- SIMD detection via `jdk.incubator.vector`, auto-growing match buffers, range operations, auto-split
