//! 4D parameter sweep benchmark for findchars.
//!
//! Dimensions: ASCII target count, match density, multi-byte count, group count.
//! Matches Java's SweepBenchmark parameter format.

use criterion::{criterion_group, criterion_main, BenchmarkId, Criterion, Throughput};
use findchars::{EngineBuilder, MatchStorage};
use findchars_bench::generate_ascii_data;

const DATA_SIZE: usize = 10 * 1024 * 1024; // 10 MB
const SEED: u64 = 42;

/// Pick N target characters from printable ASCII.
fn pick_targets(count: usize) -> Vec<u8> {
    let pool: Vec<u8> = vec![
        b',', b'.', b';', b':', b'!', b'?', b'@', b'#',
        b'$', b'%', b'^', b'&', b'*', b'(', b')', b'-',
        b'+', b'=', b'[', b']', b'{', b'}', b'|', b'~',
    ];
    pool[..count.min(pool.len())].to_vec()
}

fn sweep_ascii_count(c: &mut Criterion) {
    let mut group = c.benchmark_group("sweep_ascii_count");
    group.throughput(Throughput::Bytes(DATA_SIZE as u64));

    for ascii_count in [2, 4, 8, 12] {
        let targets = pick_targets(ascii_count);
        let data = generate_ascii_data(DATA_SIZE, &targets, 0.15, SEED);

        let result = EngineBuilder::new()
            .codepoints("targets", &targets)
            .build()
            .unwrap();
        let engine = result.engine;
        let mut storage = MatchStorage::new(DATA_SIZE / 4);

        group.bench_with_input(
            BenchmarkId::new("simd", ascii_count),
            &data,
            |b, data| {
                let mut s = MatchStorage::new(DATA_SIZE / 4);
                b.iter(|| {
                    let v = engine.find(data, &mut s);
                    v.len()
                });
            },
        );

        // Regex baseline
        let pattern = targets
            .iter()
            .map(|&b| regex::escape(&String::from(b as char)))
            .collect::<Vec<_>>()
            .join("|");
        let re = regex::bytes::Regex::new(&pattern).unwrap();

        group.bench_with_input(
            BenchmarkId::new("regex", ascii_count),
            &data,
            |b, data| {
                b.iter(|| {
                    re.find_iter(data).count()
                });
            },
        );
    }
    group.finish();
}

fn sweep_density(c: &mut Criterion) {
    let mut group = c.benchmark_group("sweep_density");
    group.throughput(Throughput::Bytes(DATA_SIZE as u64));

    let targets = pick_targets(8);

    for density_pct in [5, 15, 30, 50] {
        let density = density_pct as f64 / 100.0;
        let data = generate_ascii_data(DATA_SIZE, &targets, density, SEED);

        let result = EngineBuilder::new()
            .codepoints("targets", &targets)
            .build()
            .unwrap();
        let engine = result.engine;

        group.bench_with_input(
            BenchmarkId::new("simd", format!("{density_pct}pct")),
            &data,
            |b, data| {
                let mut s = MatchStorage::new(DATA_SIZE / 2);
                b.iter(|| {
                    let v = engine.find(data, &mut s);
                    v.len()
                });
            },
        );
    }
    group.finish();
}

fn sweep_range(c: &mut Criterion) {
    let mut group = c.benchmark_group("sweep_range");
    group.throughput(Throughput::Bytes(DATA_SIZE as u64));

    let data = generate_ascii_data(DATA_SIZE, &[b'5'], 0.10, SEED);

    let result = EngineBuilder::new()
        .range("digits", b'0', b'9')
        .build()
        .unwrap();
    let engine = result.engine;

    group.bench_function("simd_range", |b| {
        let mut s = MatchStorage::new(DATA_SIZE / 4);
        b.iter(|| {
            let v = engine.find(&data, &mut s);
            v.len()
        });
    });

    // Regex baseline
    let re = regex::bytes::Regex::new("[0-9]").unwrap();
    group.bench_function("regex_range", |b| {
        b.iter(|| re.find_iter(&data).count());
    });

    group.finish();
}

criterion_group!(benches, sweep_ascii_count, sweep_density, sweep_range);
criterion_main!(benches);
