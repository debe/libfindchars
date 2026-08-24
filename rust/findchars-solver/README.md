# findchars-solver

Z3-based constraint solver for [findchars](https://crates.io/crates/findchars)
shuffle-LUT generation. Models character detection as a satisfiability problem:
it finds two 16-entry shuffle vectors whose AND yields a unique literal byte for
every target character and, for every non-target, a value that collides with no
literal — non-target results need not be zero, since a secondary clean LUT zeroes
them at runtime. Groups split automatically when a single solve cannot cover the
requested character set, and a deterministic bit-disjoint construction covers any
set up to `log2(vector_byte_size)` literals without invoking Z3 at all.

This crate is a normal dependency of `findchars`, invoked at
**engine-construction time** (when `EngineBuilder::build()` runs) — not during
the hot detection path, and not as a `build.rs` build dependency.

## Z3 build expectations

Z3 is linked via the [`z3`](https://crates.io/crates/z3) crate with the
`vendored` feature: the first build compiles Z3 from bundled source, which takes
around **5 minutes** and requires a **C++ toolchain and CMake**. The result is
cached by Cargo, and no system Z3 installation is needed.

## License

Apache-2.0
