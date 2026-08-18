# findchars

High-performance SIMD character detection in byte sequences. Detects ASCII and
multi-byte UTF-8 characters at ~2 GB/s per core using `std::arch` intrinsics
(AVX-512, AVX2, NEON, with a scalar reference fallback). A Z3 constraint solver
finds optimal shuffle-mask configurations at engine-construction time; the
runtime engine executes detection at full vector width.

Part of [libfindchars](https://github.com/debe/libfindchars), which also ships a
Java implementation conforming to the same language-agnostic specification.

## Z3 dependency

`findchars` depends on [`findchars-solver`](https://crates.io/crates/findchars-solver),
which runs the Z3 SMT solver when an engine is built (not per `find()` call).
Z3 is compiled from vendored source on first build — expect ~5 minutes and a
working C++ toolchain plus CMake; the result is cached. A future direction is a
runtime-only core that consumes precompiled search configurations, which would
make the solver optional.

## Quick start

```rust
use findchars::{EngineBuilder, MatchStorage};

let result = EngineBuilder::new()
    .codepoints("whitespace", &[b'\t', b'\n', b' '])
    .range("digits", b'0', b'9')
    .build()
    .expect("solver failed");

let mut storage = MatchStorage::new(256);
let view = result.engine.find(b"hello\t42\n", &mut storage);

for i in 0..view.len() {
    println!("match at {} literal {}", view.position(i), view.literal(i));
}
```

`EngineBuilder` also supports `.codepoint("name", cp)` for individual ASCII or
multi-byte UTF-8 codepoints, and `.chunk_filter(...)` for stateful VPA filters.
`result.literals` maps each target name to its solver-assigned literal byte.

## SIMD backends

Auto-detected at engine construction time, overridable with
`EngineBuilder::backend(...)`:

| Backend | Vector width | Selection |
|---------|--------------|-----------|
| AVX-512 (VBMI2) | 64 bytes | preferred on x86_64 when available |
| AVX2 | 32 bytes | x86_64 fallback |
| NEON | 16 bytes | aarch64 |
| Scalar | 16 bytes | reference fallback (testing) |

## License

Apache-2.0
