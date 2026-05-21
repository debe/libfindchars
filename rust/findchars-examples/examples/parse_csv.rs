//! Parse CSV with the SIMD-accelerated parser: header detection, quoted
//! fields containing delimiters, and escaped quotes.
//!
//! Run with:
//! ```text
//! cargo run --example parse_csv -p findchars-examples
//! ```

use findchars::MatchStorage;
use findchars_csv::CsvParser;

fn main() {
    let parser = CsvParser::builder()
        .delimiter(b',')
        .quote(b'"')
        .has_header(true)
        .build()
        .expect("build failed");

    // A quoted field may contain the delimiter (`Paris, France`) or escaped
    // quotes (`""hello""` → `"hello"`); both are handled during parsing.
    let data: &[u8] = b"name,city,greeting\n\
                        Alice,\"New York\",hi\n\
                        Bob,\"Paris, France\",\"says \"\"hello\"\"\"\n";

    // `MatchStorage` is reusable; `data.len() / 4` is a reasonable capacity.
    let mut storage = MatchStorage::new(data.len() / 4 + 16);
    let result = parser.parse(data, &mut storage).expect("parse failed");

    println!("headers: {:?}", result.headers());
    println!("data rows: {}", result.row_count());

    for i in 0..result.row_count() {
        let row = result.row(i);
        print!("  row {i}:");
        for col in 0..row.field_count() {
            print!(" [{}]", row.get(col, data));
        }
        println!();
    }
}
