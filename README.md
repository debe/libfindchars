[![Build](https://github.com/debe/libfindchars/actions/workflows/build.yml/badge.svg)](https://github.com/debe/libfindchars/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![JDK](https://img.shields.io/badge/JDK-25-orange.svg)](https://jdk.java.net/25/)

libfindchars
====

libfindchars is a character detection library that can find ASCII and multi-byte UTF-8 characters in byte sequences really fast using SIMD instructions on the JVM.
Use cases are tokenizers, parsers or various pre-processing steps involving fast character detection.
As it heavily utilizes the SIMD instruction set it's more useful when the input is not smaller than the typical vector size e.g. 32 bytes.

See the [Benchmark](#benchmark) how fast it is. It typically reaches around **2 GB/s** throughput for pure ASCII
and **1.5 GB/s** for mixed ASCII/UTF-8 on a single core.

Here are some tricks it uses:
 * Vector shuffle mask operation which acts as a lookup table hack.
   To do this the compiler builds and solves an equation system containing hundreds of bitwise operations
   in equations and inequations to actually find a working vector configuration (only two vectors needed most of the time).
   This is done by using the theorem prover [z3](https://github.com/Z3Prover/z3) as normal SAT solving systems
   are simply not clever enough to find a solution.
 * UTF-8 multi-byte character detection via per-round shuffle mask solving across continuation bytes.
 * Bytecode-compiled engine with `BytecodeInliner` for zero-overhead inlined SIMD — the engine operations are compiled into a single class, eliminating virtual dispatch entirely.
 * Auto-split: when Z3 can't solve all literals in a single shuffle mask (>16 nibble entries), the compiler automatically splits into multiple groups and combines results with OR.
 * Vector range operation to find character ranges quickly (e.g. `<=>`, `0-9`).
 * Bit hacks to calculate the positions quickly.
 * Auto growing native arrays and memory segments.

## Usage

### ASCII characters with range operations

```java
var result = Utf8EngineBuilder.builder()
        .codepoints("whitespaces", '\r', '\n', '\t', '\f', ' ')
        .codepoints("punctiations", ':', ';', '{', '}', '[', ']')
        .codepoints("star", '*')
        .codepoints("plus", '+')
        .codepoints("nums", '0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
        .range("comparison", (byte) 0x3c, (byte) 0x3e)
        .build();
var findCharsEngine = result.engine();
var literals = result.literals();
```

### Multi-byte UTF-8 characters

```java
var result = Utf8EngineBuilder.builder()
        .codepoints("whitespace", ' ', '\n')
        .codepoint("eacute", 0xE9)       // é  2-byte
        .codepoint("trademark", 0x2122)   // ™  3-byte
        .codepoint("grin", 0x1F600)       // 😀 4-byte
        .build();
var engine = result.engine();
var literals = result.literals();
```

### Using the engine

```java
byte STAR = literals.get("star");
byte WHITESPACES = literals.get("whitespaces");
byte COMPARISON = literals.get("comparison");

try (Arena arena = Arena.ofConfined();
     var channel = FileChannel.open(Path.of(fileURI), StandardOpenOption.READ)) {
    var mappedFile = channel.map(MapMode.READ_ONLY, 0, channel.size(), arena);
    var matchStorage = new MatchStorage((int) channel.size() / 4, 32);
    var match = findCharsEngine.find(mappedFile, matchStorage);

    for (int i = 0; i < match.size(); i++) {
        byte lit = match.getLiteralAt(matchStorage, i);
        int pos = match.getPositionAt(matchStorage, i);

        if (lit == STAR) {
            System.out.println("* at: " + pos);
        } else if (lit == WHITESPACES) {
            System.out.println("\\w at: " + pos);
        } else if (lit == COMPARISON) {
            System.out.println("<>= at: " + pos);
        }
    }
}
```

## Building

Requires JDK 25 with `--enable-preview` and `--add-modules=jdk.incubator.vector`.

```bash
mvn clean install
```

Benchmark
---------

JMH benchmark on a 3 MB mixed ASCII/UTF-8 file. ASCII benchmark detects 26 ASCII characters
(2 shuffle groups + 1 range operation). UTF-8 benchmark adds 5 multi-byte characters (2-byte and 3-byte).
Environment: JDK 25, AVX-512, single core.

![benchmark](./libfindchars-bench/benchmark.png)

| Engine             | Ops/s | Throughput |
|--------------------|------:|------------|
| ASCII compiled     |   665 | ~2.0 GB/s  |
| UTF-8 compiled     |   497 | ~1.5 GB/s  |
| UTF-8 C2 JIT       |   139 | ~0.4 GB/s  |
| Regex baseline     |    26 | ~0.1 GB/s  |

The compiled engines use bytecode-generated SIMD kernels via `BytecodeInliner`,
eliminating all virtual dispatch overhead. The compiled engines outperform
compiled regex by roughly **19-26x**.
