//! Detect multi-byte UTF-8 codepoints (2-, 3-, and 4-byte) alongside ASCII.
//!
//! Run with:
//! ```text
//! cargo run --example find_utf8_characters -p findchars-examples
//! ```
//!
//! Rust equivalent of the Java `FindUtf8Characters` example.

use findchars::{EngineBuilder, MatchStorage};

fn main() {
    // `codepoint` accepts any Unicode scalar value; the builder encodes it to
    // UTF-8 and the engine detects the full byte sequence.
    let result = EngineBuilder::new()
        .codepoints("whitespace", b" \n")
        .codepoint("eacute", 0x00E9) // é  — 2-byte: C3 A9
        .codepoint("trademark", 0x2122) // ™  — 3-byte: E2 84 A2
        .codepoint("grin", 0x1F600) // 😀 — 4-byte: F0 9F 98 80
        .build()
        .expect("solver failed");

    let engine = &result.engine;
    let literals = &result.literals;

    let whitespace = literals["whitespace"];
    let eacute = literals["eacute"];
    let trademark = literals["trademark"];
    let grin = literals["grin"];

    // Positions are reported as byte offsets into the UTF-8 stream — a
    // multi-byte match is reported at its lead byte.
    let text = "café ™ 😀 fin\n";
    let data = text.as_bytes();

    let mut storage = MatchStorage::new(64);
    let view = engine.find(data, &mut storage);

    println!("input: {}", text.trim_end());
    println!("utf-8 bytes: {}", data.len());
    println!("matches found: {}", view.len());

    for i in 0..view.len() {
        let pos = view.position(i);
        let lit = view.literal(i);

        let kind = if lit == whitespace {
            "whitespace"
        } else if lit == eacute {
            "é (U+00E9)"
        } else if lit == trademark {
            "™ (U+2122)"
        } else if lit == grin {
            "😀 (U+1F600)"
        } else {
            "unknown"
        };

        println!("  {kind} at byte {pos}");
    }
}
