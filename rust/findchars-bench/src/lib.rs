//! Benchmark utilities: deterministic data generators for findchars benchmarks.

use rand::rngs::StdRng;
use rand::{Rng, SeedableRng};

/// Generate benchmark data with a target match density.
///
/// # Arguments
/// * `size` - Output size in bytes
/// * `targets` - Target bytes that should appear at the given density
/// * `density` - Fraction of bytes that should be targets (0.0 to 1.0)
/// * `seed` - RNG seed for reproducibility
pub fn generate_ascii_data(size: usize, targets: &[u8], density: f64, seed: u64) -> Vec<u8> {
    let mut rng = StdRng::seed_from_u64(seed);
    let mut data = Vec::with_capacity(size);

    // Build filler set: all printable ASCII except targets
    let filler: Vec<u8> = (0x20..=0x7Eu8)
        .filter(|b| !targets.contains(b))
        .collect();

    for _ in 0..size {
        if rng.random::<f64>() < density {
            data.push(targets[rng.random_range(0..targets.len())]);
        } else {
            data.push(filler[rng.random_range(0..filler.len())]);
        }
    }
    data
}

/// Generate CSV benchmark data.
///
/// # Arguments
/// * `target_size` - Approximate output size in bytes
/// * `columns` - Number of columns per row
/// * `quote_percent` - Percentage of fields that are quoted (0-100)
/// * `avg_field_len` - Average field length in bytes
/// * `seed` - RNG seed
pub fn generate_csv_data(
    target_size: usize,
    columns: usize,
    quote_percent: u32,
    avg_field_len: usize,
    seed: u64,
) -> Vec<u8> {
    let mut rng = StdRng::seed_from_u64(seed);
    let mut data = Vec::with_capacity(target_size + 1024);

    // Header row
    for c in 0..columns {
        if c > 0 { data.push(b','); }
        data.extend_from_slice(format!("col{c}").as_bytes());
    }
    data.push(b'\n');

    // Data rows
    while data.len() < target_size {
        for c in 0..columns {
            if c > 0 { data.push(b','); }

            let field_len = (avg_field_len / 2) + rng.random_range(0..avg_field_len);
            let quoted = rng.random_range(0..100) < quote_percent;

            if quoted {
                data.push(b'"');
                for _ in 0..field_len {
                    let ch = b'a' + rng.random_range(0..26u8);
                    data.push(ch);
                    // Occasionally embed a comma or quote
                    if rng.random_range(0..20) == 0 {
                        data.push(b',');
                    }
                    if rng.random_range(0..40) == 0 {
                        data.push(b'"');
                        data.push(b'"'); // escaped
                    }
                }
                data.push(b'"');
            } else {
                for _ in 0..field_len {
                    data.push(b'a' + rng.random_range(0..26u8));
                }
            }
        }
        data.push(b'\n');
    }

    data
}
