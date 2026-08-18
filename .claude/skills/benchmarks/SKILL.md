---
name: benchmarks
description: Run libfindchars parameter-sweep and CSV benchmarks (JMH for Java, Criterion for Rust), fit the cost model, and regenerate the gnuplot charts. Use when benchmarking, measuring throughput, or updating docs/sweep-data.
---

# Benchmarks

Run from the repo root — the scripts handle their own `cd`.

```bash
# Parameter sweep benchmarks (~6 min full, ~1 min quick)
scripts/run-sweep.sh              # Full: 4D sweep (ascii, density, mb, groups)
scripts/run-sweep.sh --quick      # Smoke test (1 fork, 1 warmup, 1 measurement)
scripts/run-sweep.sh --perfnorm   # With hardware counters (Linux only)

# CSV sweep benchmarks (SIMD scan/parse vs FastCSV)
scripts/run-csv-sweep.sh              # Full: 3D sweep (columns, quote%, field length)
scripts/run-csv-sweep.sh --quick      # Smoke test (1 fork, 1 warmup, 1 measurement)
scripts/run-csv-sweep.sh --perfnorm   # With hardware counters (Linux only)

# Rust equivalents
scripts/run-sweep-rust.sh --quick
scripts/run-csv-sweep-rust.sh --quick

# Cost model fitting (requires numpy)
python3 scripts/fit-cost-model.py docs/sweep-data/

# Regenerate plots
gnuplot java/libfindchars-bench/sweep-overview.gnuplot
gnuplot java/libfindchars-bench/csv-sweep-overview.gnuplot
```
