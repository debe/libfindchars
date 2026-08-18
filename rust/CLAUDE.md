# Rust implementation

All Rust build commands run from the `rust/` directory (requires Rust 1.94+):

```bash
# Run all tests (excludes slow solver auto-split test)
cd rust && cargo test -p findchars -p findchars-csv

# Run solver tests (includes Z3, first build compiles Z3 from source ~5 min)
cd rust && cargo test -p findchars-solver -- --skip auto_split_many

# Quick benchmark (short warmup)
scripts/run-sweep-rust.sh --quick
scripts/run-csv-sweep-rust.sh --quick

# Clippy (matches CI — lints tests, examples, and benches too)
cd rust && cargo clippy --workspace --all-targets -- -D warnings
```

**Important**: The `findchars-solver` crate depends on Z3 via the `z3` crate with `static-link-z3`. The first build downloads and compiles Z3 from source (~5 minutes), cached thereafter. No system Z3 installation required.

**SIMD backends**: Auto-detected at engine construction time. AVX-512 (with VBMI2) preferred, AVX2 fallback, scalar reference. NEON for aarch64.
