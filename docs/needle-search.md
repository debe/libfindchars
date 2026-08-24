# Finding a 4 KB needle: don't read the haystack

Prompted by [Lemire, *Java's String.indexOf can be slow (quadratic)*](https://www.linkedin.com/pulse/javas-stringindexof-can-slow-quadratic-daniel-lemire-wj6mc/).
Measured on OpenJDK 25 / Apple M4 Max, 1 MB haystacks, JMH `AverageTime`, 1 fork, 3×1s warmup, 5×1s measurement.

## Problem

`String.indexOf` is O(n·m) — scan for the first needle byte, verify, repeat. The JDK
intrinsic is AVX2/NEON-accelerated; the algorithm is not. Haystack of 1 MB `'a'`, needle
`'a'×4095 + 'b'`: **946 ms for one search**.

## The deciding fact

Yao's bound — exact matching examines only **Ω(n · log_σ(m) / m)** characters on average:

```
n = 1 MB, σ = 256, m = 4096  →  1048576 × 1.5 / 4096  ≈  384 bytes
```

**384 bytes out of 1,048,576.** Every full-scan design — `indexOf`, Two-Way, KMP, a SIMD
rare-byte prefilter, Teddy, libfindchars — is ~1000× above that floor. SIMD makes reading
everything *fast*; it does not make reading everything *necessary*.

## Algorithm: stride-sampled q-gram filter

An occurrence at `s` spans `[s, s+m)`, so the q-grams entirely inside it start at the
`m-q+1` positions `[s, s+m-q]`. Probe at exactly that stride and every occurrence is hit
**exactly once**:

> if the q-gram at a probe point is not one of the needle's, no occurrence covers it —
> skip `m-q+1` bytes unread.

`q = 8`: one unaligned 64-bit load per probe, a splitmix64 hash, one open-addressed lookup.

**Fixed stride, not a skip loop.** Boyer-Moore-Horspool and HASHq derive the *next* probe
address from the *current* lookup — a serial load→hash→load chain, and at a 4096-byte stride
every probe is a fresh page. Fixed stride makes all addresses known in advance, so the loads
stay independent. Measured: probes cost ~5 ns each, not the ~200 ns a TLB-miss chain would.

**The stride is not free.** A repetitive needle has q-grams at hundreds of offsets, and a hit
on one costs an alignment trial per offset. Dropping those offsets is *not allowed* — a probe
can land at any phase, so every offset the grid can produce must be resolvable. Fix: keep the
longest **run** of consecutive low-multiplicity offsets `[a, a+L)` and use `L` as the stride.
A run of `L` consecutive integers hits every residue mod `L`, so coverage still holds exactly.

Needles with no usable run fall through to a rare-byte SIMD scan; blowing a verification
budget falls through to Two-Way (O(n), O(1) space).

| m | strategy |
|---|---|
| usable stride | stride-sampled q-gram filter |
| otherwise (short or repetitive) | rare-byte-pair SIMD full scan |
| verification budget exceeded | Two-Way (Crochemore-Perrin) |

## Results

µs per search, 1 MB haystack. `simdScan` is the honest control: a competent SIMD full scan,
so it isolates the value of *skipping* from the value of *vectorizing*.

| m = 4096 | lemire | random | text |
|---|---|---|---|
| `String.indexOf` | 946 334 | 138.8 | 713.5 |
| Two-Way | 6 059 | 73.8 | 428.2 |
| SIMD full scan | 111.5 | 153.9 | 153.7 |
| **this** | **112.6** | **1.26** | **1.71** |
| **vs indexOf** | **8 405×** | **110×** | **417×** |

| vs indexOf | m=16 | m=256 | m=4096 |
|---|---|---|---|
| lemire | 57× | 555× | 8 405× |
| random | 1.2× | 8.7× | 110× |
| text | 2.7× | 36× | 417× |

The win scales with `m`, as it must: the stride *is* the needle length.
(The `text`/m=16 cell is the one unreliable number: `indexOf` measured 391 ± 852 µs there.)

**Bytes actually read** at m=4096 — the number the wall clock hides:

| | bytes read | of 1 MB | probes |
|---|---|---|---|
| random | 5 952 | 0.57% | 232 |
| text | 6 019 | 0.57% | 232 |
| Yao floor | ~384 | 0.04% | — |

~15× above the information-theoretic floor, ~175× below a full scan. Timing landed at
1.2–1.7 µs, the *low* end of the 2–25 µs range predicted before measuring — the probes do
overlap in the out-of-order window, so this is not TLB-latency-bound.

## Three honest caveats

**On Lemire's exact input, the stride filter does nothing.** `'a'×4095+'b'` has the 8-gram
`"aaaaaaaa"` at 4088 offsets; the only informative gram is the one holding `'b'`, so `L = 1`
and sampling is impossible. The 8 405× there comes entirely from the rare-byte SIMD scan
picking `'b'` — which occurs zero times, so the whole haystack dies in one pass. The stride
filter's win is on *ordinary* data (110–417×), not on his adversarial case.

**Low-entropy text erodes the stride.** At m = 65536 over a 100-word vocabulary, repeated
8-grams cut the longest clean run down and probes rise from 16 (random) to 7 932 (text).
Selectivity depends on needle entropy, not just needle length.

**The Two-Way fallback is not tuned.** 6.1 ms on the adversarial case vs the ~0.3 ms Lemire
reports for a good C implementation — ~20× off, likely the byte-at-a-time skip loop through
`MemorySegment`. It is a correctness backstop, not a competitor; it never runs on the fast paths.

## Code

`java/libfindchars-bench/src/main/java/org/knownhosts/libfindchars/bench/needle/`

| file | |
|---|---|
| `QGramFilter` | stride selection (longest clean run), probe loop, offset table |
| `SimdScan` | rare-byte-pair full scan, Vector API |
| `TwoWay` | Crochemore-Perrin, musl-shaped, vectorized half-comparisons |
| `NeedleVerifier` | `ByteVector` compare with early exit — shared by all three |
| `NeedleFinder` | dispatch + verification budget |
| `NeedleProfile` / `NeedleBenchmark` | bytes-read profile, JMH |

14 tests green, including the coverage argument tested at every offset mod stride (that
*is* the correctness proof), periodic needles, repeated q-grams, full byte range, budget
handoff, and a 400-round fuzz against a naive oracle with all strategies cross-checked.

**Uses none of libfindchars** — no shuffle masks, no Z3, no `ChunkFilter`. A `long` load, a
hash, a `ByteVector` memcmp. Per-needle build is O(m), microseconds. libfindchars stays right
for character classes and short literals, where there is nothing to skip.

```bash
cd java && ./mvnw -pl libfindchars-bench -am test
java --add-modules=jdk.incubator.vector -jar libfindchars-bench/target/libfindchars-bench-*.jar NeedleBenchmark
```

---

**Refs** — [Yao's bound](https://arxiv.org/pdf/1407.0950) ·
[SSEF (Külekci, PSC'09)](http://www.stringology.org/event/2009/p11.html) ·
[Faro & Lecroq survey](https://arxiv.org/pdf/1012.2547) ·
[2025 re-benchmark, 107 variants](https://shape-of-code.com/2025/03/30/benchmarking-string-search-algorithms/) ·
[Wu-Manber / HASHq](https://users.aalto.fi/~tarhio/papers/jea.pdf) ·
[ripgrep on Boyer-Moore](https://github.com/BurntSushi/blog/blob/master/content/post/ripgrep.md) ·
[packed string matching](https://cs.haifa.ac.il/~oren/Publications/bpsm.pdf) ·
[Teddy](https://github.com/BurntSushi/aho-corasick/blob/master/src/packed/teddy/README.md)
