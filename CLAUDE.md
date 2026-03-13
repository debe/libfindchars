# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

libfindchars is a high-performance character detection library for the JVM that uses SIMD instructions to find ASCII and multi-byte UTF-8 characters in byte sequences at speeds around 1.8 GB/s per core. The library uses a unified UTF-8 engine architecture where the Z3 theorem prover solves for optimal SIMD vector configurations at build time, and a bytecode-compiled engine executes the operations at runtime.

Key innovation: Uses the Z3 theorem prover to solve complex equation systems (hundreds of bitwise operations) to generate optimal vector shuffle masks. This enables fast character detection through lookup table hacks with minimal vectors (typically 2).

## Build Commands

```bash
# Build entire project
mvn clean install

# Build without tests
mvn clean install -DskipTests

# Run all tests
mvn test

# Run tests for a specific module
mvn test -pl libfindchars-compiler

# Run a single test class
mvn test -Dtest=LiteralCompilerTest -pl libfindchars-compiler

# Run a single test method
mvn test -Dtest=LiteralCompilerTest#testMethodName -pl libfindchars-compiler
```

**Important**: This project requires JDK 25 installed (for `jdk.incubator.vector` access) with preview features enabled. The build compiles with `--release 25`. Maven coordinates: `org.knownhosts:libfindchars-parent:0.3.0`.

## Module Architecture

The project is a multi-module Maven build with 4 modules:

### libfindchars-api
Core runtime engine, API, and `@Inline` annotation:
- `FindEngine` — Interface for engines that process memory segments and return match results
- `Utf8EngineTemplate` — Readable Java template implementing FindEngine. Serves as both C2 JIT fallback (works as-is with arrays) and compiled engine template (specialized by TemplateTransformer)
- `Utf8Kernel` — Static SIMD helpers for UTF-8 classify, round mask application, ASCII/multi-byte gating, range matching, and decode (all `@Inline`-annotated)
- `@Inline` — Annotation marking methods for bytecode inlining and fields for specialization
- `MatchStorage` — Auto-growing storage for match positions and literal identifiers
- `MatchView` — View interface for accessing match results

### libfindchars-compiler
Compiles character detection requirements into optimized engine configurations:
- **LiteralCompiler**: Uses Z3 theorem prover (via java-smt) to solve constraint systems and generate shuffle masks for character sets. This is the heart of the system.
- **Utf8EngineBuilder**: Unified entry point for building engines. Supports `.codepoints()` for ASCII groups, `.codepoint()` for multi-byte UTF-8, and `.range()` for byte range operations. Returns `Utf8BuildResult(FindEngine, Map<String, Byte>)`.
- **`compiler.inline` subpackage**: Bytecode specialization infrastructure using the JDK ClassFile API:
  - `BytecodeInliner` — inlines `@Inline`-annotated static method bodies at invokestatic call sites
  - `MethodInliner` — core algorithm for transplanting method bytecodes with slot/label remapping
  - `TemplateTransformer` — orchestrates specialization pipeline (private method inlining, constant folding)
  - `ConstantFolder` — replaces `@Inline int` field loads with constant values
  - `SpecializationConfig` — configuration for template specialization (constants, array sizes)
  - `FieldExpander`, `LoopUnroller`, `DeadCodeEliminator` — additional transformation passes

Engine construction flow:
1. Define character sets via `Utf8EngineBuilder.builder()` with `.codepoints()`, `.codepoint()`, and `.range()` API
2. `LiteralCompiler.solve()` uses Z3 to find optimal shuffle mask vectors per round
3. Range operations get random non-conflicting literal byte assignments
4. `TemplateTransformer.transform()` specializes `Utf8EngineTemplate` bytecode (constant folding, private method inlining)
5. `BytecodeInliner.inline()` transplants `Utf8Kernel` method bodies
6. `defineHiddenClass()` loads the specialized bytecode, returning a ready-to-use `FindEngine` instance

### libfindchars-examples
Working examples showing how to:
- Configure and build a UTF-8 engine with ASCII codepoints and ranges
- Detect multi-byte UTF-8 characters (2-byte, 3-byte, 4-byte)
- Use the engine to find characters in memory-mapped files
- Process match results using literal byte mappings

### libfindchars-bench
JMH benchmarks and Spring Boot application for performance testing against regex and bitset approaches.

## Key Design Patterns

**Unified UTF-8 Pipeline**: Single entry point via `Utf8EngineBuilder`. The UTF-8 engine is a superset of ASCII — its fast path handles pure-ASCII data efficiently (skips classify and multi-byte gating), while supporting multi-byte characters and range operations in the same engine.

**Constraint Solving**: Character detection is modeled as a constraint satisfaction problem solved by Z3. The compiler builds equations where each character must map to a unique literal ID through bitwise operations on shuffle masks. This mathematical approach finds solutions that would be impractical to discover manually.

**Annotation-Driven Template**: `Utf8EngineTemplate` is readable Java annotated with `@Inline`. The `TemplateTransformer` specializes it by constant-folding `@Inline int` fields and inlining `@Inline` private methods. Then `BytecodeInliner` transplants `@Inline`-annotated static method bodies from `Utf8Kernel`, producing flat inlined bytecodes.

## Development Notes

- The `--add-modules=jdk.incubator.vector` flag is required for both compilation and test execution
- Maven Surefire is configured with these JVM args automatically
- The Z3 native libraries are bundled via `javasmt-solver-z3` dependency
- Literal byte values are determined at runtime by the Z3 solver (not compile-time constants)
