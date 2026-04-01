//! CSV parser tests. Covers CSV-001..014 from the spec.

use findchars::MatchStorage;
use findchars_csv::CsvParser;

fn parse(data: &[u8]) -> (findchars_csv::CsvResult, Vec<u8>) {
    let parser = CsvParser::builder().build().unwrap();
    let mut storage = MatchStorage::new(data.len() / 4 + 16);
    let result = parser.parse(data, &mut storage).unwrap();
    (result, data.to_vec())
}

fn parse_with_header(data: &[u8]) -> (findchars_csv::CsvResult, Vec<u8>) {
    let parser = CsvParser::builder().has_header(true).build().unwrap();
    let mut storage = MatchStorage::new(data.len() / 4 + 16);
    let result = parser.parse(data, &mut storage).unwrap();
    (result, data.to_vec())
}

// --- CSV-001: RFC 4180 Compliance ---

#[test]
fn csv_001_basic_parsing() {
    let data = b"a,b,c\n1,2,3\n";
    let (result, d) = parse(data);
    assert_eq!(result.row_count(), 2);
    assert_eq!(result.row(0).get(0, &d), "a");
    assert_eq!(result.row(0).get(1, &d), "b");
    assert_eq!(result.row(0).get(2, &d), "c");
    assert_eq!(result.row(1).get(0, &d), "1");
    assert_eq!(result.row(1).get(1, &d), "2");
    assert_eq!(result.row(1).get(2, &d), "3");
}

#[test]
fn csv_001_quoted_fields() {
    let data = br#""hello","world""#;
    let data = b"\"hello\",\"world\"\n";
    let (result, d) = parse(data);
    assert_eq!(result.row_count(), 1);
    assert_eq!(result.row(0).get(0, &d), "hello");
    assert_eq!(result.row(0).get(1, &d), "world");
}

// --- CSV-003: Quote Filtering ---

#[test]
fn csv_003_comma_inside_quotes() {
    let data = b"\"a,b\",c\n";
    let (result, d) = parse(data);
    assert_eq!(result.row_count(), 1);
    assert_eq!(result.row(0).field_count(), 2);
    assert_eq!(result.row(0).get(0, &d), "a,b");
    assert_eq!(result.row(0).get(1, &d), "c");
}

#[test]
fn csv_003_newline_inside_quotes() {
    let data = b"\"line1\nline2\",b\n";
    let (result, d) = parse(data);
    assert_eq!(result.row_count(), 1);
    assert_eq!(result.row(0).field_count(), 2);
    assert_eq!(result.row(0).get(0, &d), "line1\nline2");
    assert_eq!(result.row(0).get(1, &d), "b");
}

// --- CSV-005: Configurable Delimiter ---

#[test]
fn csv_005_tab_delimiter() {
    let parser = CsvParser::builder().delimiter(b'\t').build().unwrap();
    let data = b"a\tb\tc\n";
    let mut storage = MatchStorage::new(64);
    let result = parser.parse(data, &mut storage).unwrap();
    assert_eq!(result.row_count(), 1);
    assert_eq!(result.row(0).field_count(), 3);
    assert_eq!(result.row(0).get(0, data), "a");
    assert_eq!(result.row(0).get(1, data), "b");
}

// --- CSV-007: Header Detection ---

#[test]
fn csv_007_headers() {
    let data = b"name,age\nAlice,30\nBob,25\n";
    let (result, d) = parse_with_header(data);
    assert_eq!(result.headers(), &["name", "age"]);
    assert_eq!(result.row_count(), 2);
    assert_eq!(result.row(0).get(0, &d), "Alice");
    assert_eq!(result.row(1).get(1, &d), "25");
}

// --- CSV-008: Zero-Copy Fields ---

#[test]
fn csv_008_raw_field() {
    let data = b"hello,world\n";
    let (result, _) = parse(data);
    let raw = result.row(0).raw_field(0, data);
    assert_eq!(raw, b"hello");
}

// --- CSV-009: Escaped Quote Handling ---

#[test]
fn csv_009_escaped_quotes() {
    let data = b"\"He said \"\"hello\"\"\",b\n";
    let (result, d) = parse(data);
    assert_eq!(result.row(0).get(0, &d), "He said \"hello\"");
}

// --- CSV-010: Row Boundaries ---

#[test]
fn csv_010_lf_rows() {
    let data = b"a\nb\nc\n";
    let (result, _) = parse(data);
    assert_eq!(result.row_count(), 3);
}

#[test]
fn csv_010_crlf_rows() {
    let data = b"a\r\nb\r\nc\r\n";
    let (result, _) = parse(data);
    assert_eq!(result.row_count(), 3);
}

#[test]
fn csv_010_no_trailing_newline() {
    let data = b"a,b\nc,d";
    let (result, d) = parse(data);
    assert_eq!(result.row_count(), 2);
    assert_eq!(result.row(1).get(1, &d), "d");
}

// --- CSV-012: Large Field Support ---

#[test]
fn csv_012_large_field() {
    let mut data = Vec::new();
    data.push(b'"');
    data.extend_from_slice(&[b'x'; 1000]); // 1000 bytes
    data.push(b'"');
    data.push(b'\n');
    let (result, d) = parse(&data);
    assert_eq!(result.row_count(), 1);
    let val = result.row(0).get(0, &d);
    assert_eq!(val.len(), 1000);
}

// --- CSV-013: Empty Fields ---

#[test]
fn csv_013_empty_fields() {
    let data = b"a,,c\n";
    let (result, d) = parse(data);
    assert_eq!(result.row(0).field_count(), 3);
    assert_eq!(result.row(0).get(0, &d), "a");
    assert_eq!(result.row(0).get(1, &d), "");
    assert_eq!(result.row(0).get(2, &d), "c");
}

#[test]
fn csv_013_trailing_delimiter() {
    let data = b"a,b,\n";
    let (result, d) = parse(data);
    assert_eq!(result.row(0).field_count(), 3);
    assert_eq!(result.row(0).get(2, &d), "");
}

// --- CSV-014: Result Iteration ---

#[test]
fn csv_014_row_iteration() {
    let data = b"a,1\nb,2\nc,3\n";
    let (result, d) = parse(data);
    assert_eq!(result.row_count(), 3);
    for i in 0..result.row_count() {
        let row = result.row(i);
        assert_eq!(row.field_count(), 2);
        assert!(!row.get(0, &d).is_empty());
    }
}

// --- Empty input ---

#[test]
fn csv_empty_input() {
    let (result, _) = parse(b"");
    assert_eq!(result.row_count(), 0);
}
