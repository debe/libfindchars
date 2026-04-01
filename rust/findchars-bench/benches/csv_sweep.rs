//! 3D CSV parameter sweep benchmark.
//!
//! Dimensions: column count, quote percentage, average field length.

use criterion::{black_box, criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};
use findchars::MatchStorage;
use findchars_bench::generate_csv_data;
use findchars_csv::CsvParser;

const DATA_SIZE: usize = 10 * 1024 * 1024; // 10 MB
const SEED: u64 = 42;

fn csv_sweep_columns(c: &mut Criterion) {
    let mut group = c.benchmark_group("csv_sweep_columns");

    for columns in [5, 10, 25, 50] {
        let data = generate_csv_data(DATA_SIZE, columns, 5, 16, SEED);
        group.throughput(Throughput::Bytes(data.len() as u64));

        let parser = CsvParser::builder().has_header(true).build().unwrap();

        group.bench_with_input(
            BenchmarkId::new("simd_parse", columns),
            &data,
            |b, data| {
                let mut storage = MatchStorage::new(data.len() / 4);
                b.iter(|| {
                    let r = parser.parse(black_box(data), &mut storage).unwrap();
                    black_box(r.row_count())
                });
            },
        );
    }
    group.finish();
}

fn csv_sweep_quotes(c: &mut Criterion) {
    let mut group = c.benchmark_group("csv_sweep_quotes");

    for quote_pct in [0, 5, 25, 50] {
        let data = generate_csv_data(DATA_SIZE, 10, quote_pct, 16, SEED);
        group.throughput(Throughput::Bytes(data.len() as u64));

        let parser = CsvParser::builder().has_header(true).build().unwrap();

        group.bench_with_input(
            BenchmarkId::new("simd_parse", format!("{quote_pct}pct")),
            &data,
            |b, data| {
                let mut storage = MatchStorage::new(data.len() / 4);
                b.iter(|| {
                    let r = parser.parse(black_box(data), &mut storage).unwrap();
                    black_box(r.row_count())
                });
            },
        );
    }
    group.finish();
}

fn csv_sweep_field_len(c: &mut Criterion) {
    let mut group = c.benchmark_group("csv_sweep_field_len");

    for field_len in [4, 16, 32, 64] {
        let data = generate_csv_data(DATA_SIZE, 10, 5, field_len, SEED);
        group.throughput(Throughput::Bytes(data.len() as u64));

        let parser = CsvParser::builder().has_header(true).build().unwrap();

        group.bench_with_input(
            BenchmarkId::new("simd_parse", field_len),
            &data,
            |b, data| {
                let mut storage = MatchStorage::new(data.len() / 4);
                b.iter(|| {
                    let r = parser.parse(black_box(data), &mut storage).unwrap();
                    black_box(r.row_count())
                });
            },
        );
    }
    group.finish();
}

fn csv_backend_compare(c: &mut Criterion) {
    let mut group = c.benchmark_group("csv_backend_compare");

    let data = generate_csv_data(DATA_SIZE, 10, 5, 16, SEED);
    group.throughput(Throughput::Bytes(data.len() as u64));

    for &(name, backend) in &[
        ("avx512", findchars::SimdBackend::Avx512),
        ("avx2", findchars::SimdBackend::Avx2),
        ("scalar", findchars::SimdBackend::Scalar),
    ] {
        let parser = CsvParser::builder()
            .has_header(true)
            .backend(backend)
            .build()
            .unwrap();

        group.bench_function(name, |b| {
            let mut storage = MatchStorage::new(data.len() / 4);
            b.iter(|| {
                let r = parser.parse(black_box(&data), &mut storage).unwrap();
                black_box(r.row_count())
            });
        });
    }
    group.finish();
}

criterion_group!(benches, csv_sweep_columns, csv_sweep_quotes, csv_sweep_field_len, csv_backend_compare);
criterion_main!(benches);
