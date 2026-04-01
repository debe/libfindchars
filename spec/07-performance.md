# 07 — Performance

This document specifies throughput targets, benchmark methodology, and allocation constraints for conforming implementations.

Performance targets are guidelines, not hard pass/fail criteria. They represent expected throughput on modern hardware (2023+ server-class x86-64 or ARM) at a single core. Implementations should benchmark against these targets and document any deviations.

---

## Throughput Targets

#### PERF-001: ASCII Scan Throughput

**Priority:** SHOULD

Pure ASCII character detection should achieve at least 1.5 GB/s per core on modern x86-64 (AVX2 or AVX-512) or ARM (NEON) hardware, for a typical target set of 4-8 characters.

**Acceptance Criteria:**
1. Benchmark with 4-8 ASCII targets on 100 MB input shows >= 1.5 GB/s
2. Throughput does not drop below 1.0 GB/s for up to 12 targets

**Test derivation:** Run JMH/Criterion benchmark with varying target counts, report throughput.

---

#### PERF-002: Mixed UTF-8 Throughput

**Priority:** SHOULD

Detection on mixed ASCII/UTF-8 input (containing 2, 3, and 4-byte codepoints) should achieve at least 1.0 GB/s per core. The multi-byte gating pipeline adds overhead compared to pure ASCII.

**Acceptance Criteria:**
1. Benchmark with mixed UTF-8 input (10-20% multi-byte content) shows >= 1.0 GB/s
2. The overhead of multi-byte gating is bounded: no more than 2x slowdown vs pure ASCII

**Test derivation:** Generate input with configurable multi-byte density, benchmark at various densities.

---

#### PERF-003: CSV Parse Throughput

**Priority:** SHOULD

Full CSV parsing (SIMD scan + quote filter + field extraction) should achieve at least 1.0 GB/s per core on representative CSV data (10-50 columns, 10-20% quoted fields, average field length 8-16 bytes).

**Acceptance Criteria:**
1. Benchmark on 100 MB generated CSV shows >= 1.0 GB/s for scan phase
2. Full parse (including field boundary extraction) shows >= 0.5 GB/s

**Test derivation:** Run CSV sweep benchmark across parameter combinations, report throughput.

---

#### PERF-004: Sublinear Scaling

**Priority:** SHOULD

Throughput degrades sublinearly as the number of target characters increases. Doubling the target count should not halve throughput. The primary cost increase comes from additional detection rounds.

**Acceptance Criteria:**
1. 12 targets (1 round) vs 24 targets (2 rounds): throughput decrease < 50%
2. Each additional round adds approximately one shuffle operation per chunk

**Test derivation:** Benchmark at 4, 8, 12, 16, 20, 24 targets, plot throughput curve.

---

## Benchmark Methodology

#### PERF-005: Benchmark Methodology

**Priority:** MUST

All performance claims are backed by reproducible benchmarks using a rigorous micro-benchmarking framework (e.g., JMH for Java, Criterion for Rust). Benchmarks specify forks, warmup iterations, and measurement iterations.

**Acceptance Criteria:**
1. Benchmark harness handles JIT warmup (or equivalent) before measurement
2. Results report mean throughput with confidence intervals
3. Benchmark parameters (input size, target count, data characteristics) are documented
4. Results are reproducible on the same hardware

**Test derivation:** Run benchmarks with default settings, verify results are within 10% across runs.

---

#### PERF-006: Parameter Sweep

**Priority:** SHOULD

A standard 4-dimensional parameter sweep covers: ASCII target count (4-24), match density (0.1%-10%), multi-byte codepoint count (0-4), and detection group count (1-2). This provides regression coverage across the performance surface.

**Acceptance Criteria:**
1. The sweep script is automated and reproducible
2. Results are stored in machine-readable format (JSON/TSV)
3. Visualization is available (gnuplot or equivalent)

**Test derivation:** Run the sweep script, verify output files are generated.

---

#### PERF-007: CSV Sweep

**Priority:** SHOULD

A standard 3-dimensional CSV sweep covers: column count (5-100), quote percentage (0%-50%), and average field length (4-64 bytes). This benchmarks CSV-specific performance characteristics.

**Acceptance Criteria:**
1. The sweep includes comparison against a baseline parser (e.g., a popular CSV library)
2. Results are stored in machine-readable format
3. Data generation is deterministic (seeded RNG)

**Test derivation:** Run the CSV sweep script, verify output files and comparison data.

---

## Allocation Constraints

#### PERF-008: No Hot-Path Allocation

**Priority:** MUST

The detection loop (chunk processing, shuffle, gating, decode) must not allocate heap memory. All buffers (storage positions, storage literals, decode temporaries, filter state, filter scratchpad) are pre-allocated before the detection loop begins. The only permitted allocation is storage auto-grow on unexpectedly large inputs.

**Acceptance Criteria:**
1. A `find()` call on pre-sized storage allocates zero heap objects
2. Storage auto-grow is the only allocation path in the detection pipeline
3. Filter processing allocates zero heap objects

**Test derivation:** Profile allocation during `find()` on a large input with pre-sized storage, verify zero allocations.

---
