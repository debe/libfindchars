# Changelog

All notable changes to libfindchars are documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: `{semver}-jdk{N}-preview` until the Vector API graduates from incubator.

## [Unreleased]

### Added
- **libfindchars-csv** — SIMD-accelerated CSV parser (~1.8 GB/s scan per core). Builder API, zero-copy field access via `MemorySegment` offsets, memory-mapped file support.
- **VPA chunk filter framework** — stateful per-chunk filtering between SIMD detection and decode. `VpaKernel` provides `prefixXor` (toggle state) and `prefixSum` (depth tracking) as Hillis-Steele parallel prefix primitives in O(log₂ V) steps. `CsvQuoteFilter` is the first built-in filter.
- **Platform-adaptive decode** — AVX-512 uses `VPCOMPRESSB`; ARM/AVX2 uses `intoArray()` + scatter to avoid `compress()` lambda fallback in hidden classes. `anyTrue()` guard defers expensive `toLong()`.
- **`cleanLUT` shuffle** — `selectFrom()` replaces per-literal compare+add loops, O(1) per group.
- Architectural constraints via `.sentrux/rules.toml`
- New tests: `MatchStorageTest`, `VpaKernelTest`, `CsvProfileTest` (11 microbenchmarks), `FastCsvComparisonTest`

### Changed
- **Breaking: position type `int` → `long`** — `MatchView.getPositionAt()` now returns `long`, enabling files >2 GB
- Refactored `LiteralCompiler`, `Utf8EngineBuilder`, and bytecode inliner pipeline for readability
- `DeadCodeEliminator` now resolves single-operand constant branches (`ifeq`/`ifne`/etc.)

### Fixed
- Fuzz test skips unsolvable random configurations instead of failing
- CI: Z3 native library download, `--enable-native-access`, javasmt-solver-z3 4.14.0

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
