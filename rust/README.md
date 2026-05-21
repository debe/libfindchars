# libfindchars — Rust

High-performance SIMD character detection in byte sequences. Detects ASCII and
multi-byte UTF-8 characters at ~2 GB/s per core using `std::arch` intrinsics. A
Z3 constraint solver finds optimal shuffle-mask configurations at build time; the
runtime engine executes detection at full vector width.

This is the Rust implementation of [libfindchars](../README.md). It conforms to
the language-agnostic [specification](../spec/00-index.md) (73 requirements). For
the project overview, benchmarks, and design notes, see the
[root README](../README.md).

## Crates

| Crate | Published | Purpose |
|-------|-----------|---------|
| [`findchars`](findchars/) | yes | Core detection engine, SIMD backends, VPA filters |
| [`findchars-solver`](findchars-solver/) | yes | Z3 constraint solver (used as a build dependency) |
| [`findchars-csv`](findchars-csv/) | yes | SIMD-accelerated CSV parser |
| `findchars-bench` | no | Criterion benchmarks |
| `findchars-examples` | no | Runnable usage examples |

## Installation

Requires **Rust 1.94+**. `findchars-solver` depends on Z3 via the `z3` crate with
`static-link-z3`; the first build compiles Z3 from source (~5 min, cached
thereafter) — no system Z3 installation required.

```toml
# Cargo.toml
[dependencies]
findchars = "0.1"

# For CSV parsing
findchars-csv = "0.1"
```

## Quick Start

### Character detection

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

### CSV parsing

```rust
use findchars_csv::CsvParser;

let parser = CsvParser::builder()
    .delimiter(b',')
    .quote(b'"')
    .has_header(true)
    .build()
    .expect("build failed");

let data = b"name,age\nAlice,30\nBob,25\n";
let mut storage = findchars::MatchStorage::new(data.len() / 4);
let result = parser.parse(data, &mut storage).unwrap();

assert_eq!(result.row_count(), 2);
assert_eq!(result.row(0).get(0, data), "Alice");
```

## SIMD Backends

The backend is auto-detected at engine construction time and can be overridden
with `EngineBuilder::backend(...)`:

| Backend | Vector width | Selection |
|---------|--------------|-----------|
| AVX-512 (VBMI2) | 64 bytes | preferred on x86_64 when available |
| AVX2 | 32 bytes | x86_64 fallback |
| NEON | 16 bytes | aarch64 |
| Scalar | 16 bytes | reference fallback (testing) |

## Building & Testing

All commands run from this `rust/` directory:

```bash
# Build the workspace
cargo build

# Run engine and CSV tests (excludes the slow solver suite)
cargo test -p findchars -p findchars-csv

# Run solver tests (compiles Z3 on first build)
cargo test -p findchars-solver -- --skip auto_split_many

# Lint (matches CI)
cargo clippy --workspace --all-targets -- -D warnings
```

## Examples

```bash
cargo run --example find_literals_and_positions -p findchars-examples
cargo run --example find_utf8_characters -p findchars-examples
cargo run --example parse_csv -p findchars-examples
```

## Benchmarks

Criterion benchmarks live in `findchars-bench`:

```bash
cargo bench -p findchars-bench --bench sweep
cargo bench -p findchars-bench --bench csv_sweep

# Quick runs via the helper scripts
../scripts/run-sweep-rust.sh --quick
../scripts/run-csv-sweep-rust.sh --quick
```

## License

MIT — see the project [root](../README.md).
