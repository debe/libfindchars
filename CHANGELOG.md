# Changelog

All notable changes to libfindchars are documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: `{semver}-jdk{N}-preview` until the Vector API graduates from incubator.

## [Unreleased]

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
