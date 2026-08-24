# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

libfindchars is a high-performance character detection library that uses SIMD instructions to find ASCII and multi-byte UTF-8 characters in byte sequences at ~2 GB/s per core. A constraint solver finds optimal shuffle mask configurations at build time; a template-specialized engine executes the operations at runtime.

Key innovation: the solver finds two 16-entry shuffle vectors whose AND yields a unique literal byte for every target character, and for every non-target a value that collides with no literal (a secondary clean LUT maps those to zero — the solver guarantees non-collision, not zero output). A single shuffle group reliably solves ~12 ASCII literals; auto-split doubles that by solving two independent halves.

## Specification

The `spec/` directory contains the language-agnostic specification (73 requirements across 7 documents). Any conforming implementation (Java, Rust, etc.) must satisfy these requirements. `spec/00-index.md` maps each file to its requirement prefix, scope, and count.

## Build Commands

Toolchains: **JDK 25** with `--add-modules=jdk.incubator.vector` for Java (no preview features); **Rust 1.94+** for Rust.

- Java: see `java/CLAUDE.md`
- Rust: see `rust/CLAUDE.md`

Releasing and benchmark sweeps have dedicated skills — see `.claude/skills/release/` and `.claude/skills/benchmarks/`.

## Engine Construction Flow

1. Define character sets via builder API
2. `LiteralCompiler.solve()` invokes Z3 per round, with auto-split on failure
3. Range operations get non-conflicting literal IDs outside nibble matrix
4. `TemplateTransformer.transform()` specializes `Utf8EngineTemplate` (constant folding, DCE, private method inlining)
5. `BytecodeInliner.inline()` transplants `Utf8Kernel` bodies
6. If filter configured: `rewriteFilterOwner()` swaps `NoOpChunkFilter` → user class, then inlines filter + VpaKernel
7. `defineHiddenClass()` loads specialized bytecode → ready `FindEngine`

## Key Design Patterns

**Unified UTF-8 Pipeline**: Single entry point via `Utf8EngineBuilder`. The UTF-8 engine is a superset of ASCII — its fast path handles pure-ASCII data efficiently (skips classify and multi-byte gating), while supporting multi-byte characters and range operations in the same engine.

**Z3 Constraint Solving**: Character detection is modeled as a satisfiability problem. The compiler feeds hundreds of bitwise constraints into Z3 to find two 16-entry shuffle vectors per group. Auto-split recursion handles groups too large for a single solve. Literal IDs share range `[1, vectorByteSize-1]`.

**Annotation-Driven Template**: `Utf8EngineTemplate` is readable Java annotated with `@Inline`. `TemplateTransformer` constant-folds `@Inline int` fields, eliminates dead code, and inlines `@Inline` private methods. Then `BytecodeInliner` transplants `@Inline` static method bodies from `Utf8Kernel`, producing flat zero-overhead bytecode.

**VPA Chunk Filters**: Stateful per-chunk filtering via `chunkFilter()`. A `ChunkFilter` provides an `@Inline static apply()` method running between SIMD detection and position decode. `VpaKernel` gives reusable parallel prefix primitives in O(log₂ V) steps. The engine manages all working memory (`state[8]`, `scratchpad[vbs]`, `literals[]`).

**Platform-Adaptive Decode**: AVX-512 uses `VPCOMPRESSB` (single instruction). ARM/AVX2 uses `intoArray()` + scalar scatter to avoid `compress()` lambda fallback that causes `IllegalAccessError` in hidden classes. `anyTrue()` maps to a single NEON UMAXV for fast rejection of empty chunks.

**Zero-Copy Architecture**: CSV results hold offsets into the original `MemorySegment`. String materialization is deferred until `CsvField.value()` is called. `CsvMatchView` iterates flat literal buffers without allocation.

## Architecture Constraints

Enforced via `.sentrux/rules.toml` — layer ordering, dependency boundaries, cycle count, and complexity budgets, with the intentional exemptions documented as inline comments there.

## Solver Limits

- **Per shuffle group**: ~12 ASCII literals. Auto-split doubles to ~20-24.
- **Literal namespace**: `[1, vectorByteSize-1]`. AVX-512: 63 max. NEON: 15 max.
- **Range operations**: 1 literal each, evaluated separately from nibble matrix.
- **Multi-byte codepoints**: 1 literal each (reuse IDs across rounds).

## Development Notes

- Literal byte values are determined at runtime by Z3 (not compile-time constants)
- `Utf8EngineTemplate` is **not thread-safe** (mutable `decodeTmp`, `filterState` fields)
- `MatchStorage` is reusable across `find()` calls (engine overwrites buffers)
