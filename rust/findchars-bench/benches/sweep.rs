use criterion::{criterion_group, criterion_main, Criterion};

fn sweep_benchmarks(_c: &mut Criterion) {
    // TODO: Phase 7 — 4D parameter sweep
}

criterion_group!(benches, sweep_benchmarks);
criterion_main!(benches);
