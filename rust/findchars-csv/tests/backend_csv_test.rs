//! Backend-specific CSV tests to catch CI failures on non-AVX-512 machines.

use findchars::{MatchStorage, SimdBackend};
use findchars_csv::CsvParser;

fn parse_with_backend(data: &[u8], backend: SimdBackend) -> findchars_csv::CsvResult {
    let parser = CsvParser::builder().backend(backend).build().unwrap();
    let mut storage = MatchStorage::new(data.len() / 4 + 16);
    parser.parse(data, &mut storage).unwrap()
}

#[test]
fn csv_009_escaped_quotes_scalar() {
    let data = b"\"He said \"\"hello\"\"\",b\n";
    let result = parse_with_backend(data, SimdBackend::Scalar);
    let val = result.row(0).get(0, data);
    assert_eq!(val, "He said \"hello\"", "scalar: got '{val}'");
}

#[test]
fn csv_009_escaped_quotes_avx2() {
    let data = b"\"He said \"\"hello\"\"\",b\n";
    let result = parse_with_backend(data, SimdBackend::Avx2);
    let val = result.row(0).get(0, data);
    assert_eq!(val, "He said \"hello\"", "avx2: got '{val}'");
}

#[test]
fn csv_012_large_field_scalar() {
    let mut data = Vec::new();
    data.push(b'"');
    data.extend_from_slice(&[b'x'; 1000]);
    data.push(b'"');
    data.push(b'\n');
    let result = parse_with_backend(&data, SimdBackend::Scalar);
    let val = result.row(0).get(0, &data);
    assert_eq!(val.len(), 1000, "scalar: got len {}", val.len());
}

#[test]
fn csv_012_large_field_avx2() {
    let mut data = Vec::new();
    data.push(b'"');
    data.extend_from_slice(&[b'x'; 1000]);
    data.push(b'"');
    data.push(b'\n');
    let result = parse_with_backend(&data, SimdBackend::Avx2);
    let val = result.row(0).get(0, &data);
    assert_eq!(val.len(), 1000, "avx2: got len {}", val.len());
}
