# findchars-csv

SIMD-accelerated, zero-copy CSV parser built on
[findchars](https://crates.io/crates/findchars). Two-phase parse — SIMD scan,
then a linear match walk — with RFC 4180 quote handling done branchlessly via a
prefix-XOR quote filter. Results are flat offset arrays into the original input;
field strings are only materialized on access.

Supports configurable delimiter and quote characters, headers, CRLF and LF line
endings, empty fields, escaped (doubled) quotes, and fields spanning multiple
SIMD chunks. Caller-provided storage keeps allocation under your control.

## Quick start

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

Note: building a parser runs the Z3 solver once (via `findchars`); the first
compile of the workspace builds vendored Z3 (~5 min, needs a C++ toolchain and
CMake, cached thereafter).

## License

Apache-2.0
