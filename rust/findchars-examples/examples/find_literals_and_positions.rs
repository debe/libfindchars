//! Detect several groups of ASCII literals and byte ranges in one pass,
//! then report each match's group and byte position.
//!
//! Run with:
//! ```text
//! cargo run --example find_literals_and_positions -p findchars-examples
//! ```
//!
//! Rust equivalent of the Java `FindLiteralsAndPositions` example.

use findchars::{EngineBuilder, MatchStorage};

fn main() {
    // Configure the detection targets. Each `codepoints` group and each
    // `range` is assigned its own literal byte by the solver at build time.
    let result = EngineBuilder::new()
        .codepoints("whitespace", &[b'\r', b'\n', b'\t', 0x0C, b' '])
        .codepoints("punctuation", b":;{}[]")
        .codepoints("star", b"*")
        .codepoints("plus", b"+")
        .range("digits", b'0', b'9')
        .range("comparison", 0x3C, 0x3E) // < = >
        .build()
        .expect("solver failed");

    let engine = &result.engine;
    let literals = &result.literals;

    // Resolve the literal byte assigned to each named group. Literal byte
    // values are chosen by the solver, so look them up rather than hard-coding.
    let whitespace = literals["whitespace"];
    let punctuation = literals["punctuation"];
    let star = literals["star"];
    let plus = literals["plus"];
    let digits = literals["digits"];
    let comparison = literals["comparison"];

    // The engine scans any `&[u8]`. To scan a file instead, read it first:
    //   let data = std::fs::read("input.txt").expect("read failed");
    //   let view = engine.find(&data, &mut storage);
    let data = b"x = a + b * 3;\nif (count >= 10) { total += count; }\n";

    // `MatchStorage` is reusable across `find()` calls; size it roughly.
    let mut storage = MatchStorage::new(data.len() / 4 + 16);
    let view = engine.find(data, &mut storage);

    println!("scanned {} bytes, found {} matches", data.len(), view.len());

    for i in 0..view.len() {
        let pos = view.position(i);
        let lit = view.literal(i);

        let kind = if lit == star {
            "*"
        } else if lit == whitespace {
            "whitespace"
        } else if lit == punctuation {
            "punctuation"
        } else if lit == plus {
            "+"
        } else if lit == digits {
            "digit"
        } else if lit == comparison {
            "<>="
        } else {
            "unknown"
        };

        println!("  {kind} at byte {pos}");
    }
}
