# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

libfindchars is a high-performance character detection library for the JVM that uses SIMD instructions to find ASCII characters in byte sequences at speeds around 1 GiB/s per core. The library uses a runtime engine architecture where the Z3 theorem prover solves for optimal SIMD vector configurations at build time, and a sealed-interface engine executes the operations at runtime.

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
mvn test -Dtest=AsciiLiteralTest -pl libfindchars-compiler

# Run a single test method
mvn test -Dtest=AsciiLiteralTest#testMethodName -pl libfindchars-compiler
```

**Important**: This project requires JDK 25 installed (for `jdk.incubator.vector` access) with preview features enabled. The build compiles with `--release 25`. Maven coordinates: `org.knownhosts:libfindchars-parent:0.0.4`.

## Module Architecture

The project is a multi-module Maven build with 4 modules:

### libfindchars-api
Core runtime engine and API:
- `FindCharsEngine` - Runtime SIMD engine that processes memory segments using composable `FindOp` operations
- `FindOp` - Sealed interface for SIMD operations, with two implementations:
  - `ShuffleMaskOp` - Vector shuffle acting as a lookup table (for arbitrary character sets)
  - `RangeOp` - Fast vector range comparisons (for contiguous character ranges like `<=>`)
- `FindMask` - Interface for character detection masks (produced by Z3 solver)
- `MatchStorage` - Auto-growing storage for match positions and literal identifiers
- `MatchView` - View interface for accessing match results
- `MatchDecoder` - Utilities for decoding vector match results

### libfindchars-compiler
Compiles character detection requirements into optimized engine configurations:
- **LiteralCompiler**: Uses Z3 theorem prover (via java-smt) to solve constraint systems and generate shuffle masks for character sets. This is the heart of the system - it converts character detection requirements into optimal SIMD vector configurations.
- **EngineBuilder**: Orchestrates engine construction. `EngineBuilder.build()` returns a `BuildResult` containing a live `FindCharsEngine` instance and a `Map<String, Byte>` of literal name-to-byte mappings.

Engine construction flow:
1. Define character sets via `EngineConfiguration` (record with builder) with `AsciiLiteralGroup` and operations
2. `LiteralCompiler.solve()` uses Z3 to find optimal shuffle mask vectors
3. `EngineBuilder.build()` creates `ShuffleMaskOp`/`RangeOp` instances and returns a ready-to-use `FindCharsEngine`

### libfindchars-examples
Working examples showing how to:
- Configure and build a runtime engine
- Use the engine to find characters in memory-mapped files
- Process match results using literal byte mappings

### libfindchars-bench
JMH benchmarks and Spring Boot application for performance testing against regex and bitset approaches.

## Key Design Patterns

**Runtime Engine Pattern**: Users configure their character detection needs via `EngineConfiguration`, then `EngineBuilder.build()` returns a live `FindCharsEngine` instance. The engine uses a sealed interface (`FindOp`) with exactly two implementations, enabling C2 bimorphic inlining on JDK 25.

**Constraint Solving**: Character detection is modeled as a constraint satisfaction problem solved by Z3. The compiler builds equations where each character must map to a unique literal ID through bitwise operations on shuffle masks. This mathematical approach finds solutions that would be impractical to discover manually.

**SIMD Operations**: The engine uses two operation types via `FindOp`:
- **ShuffleMaskOp**: Vector shuffle acting as a lookup table (for arbitrary character sets)
- **RangeOp**: Fast vector range comparisons (for contiguous character ranges like `<=>`)

## Development Notes

- The `--add-modules=jdk.incubator.vector` flag is required for both compilation and test execution
- Maven Surefire is configured with these JVM args automatically
- The Z3 native libraries are bundled via `javasmt-solver-z3` dependency
- Literal byte values are determined at runtime by the Z3 solver (not compile-time constants)
