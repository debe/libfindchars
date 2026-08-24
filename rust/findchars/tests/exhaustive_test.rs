//! Exhaustive sequence sweeps (UTF8-008, ENGINE-004).
//!
//! Enumerates *every* N-byte sequence, malformed ones included, and checks that
//! the detected SIMD backend agrees with the scalar reference. Sequences are
//! placed at the offsets that historically break: the head of the buffer, a
//! chunk boundary, and the very end of an exactly sized buffer — the last of
//! which is where the main loop's round lookahead runs past the input.
//!
//! The 2-byte sweep (65,536 cases) runs by default. The 3-byte (16.7M) and
//! 4-byte (4.29G) sweeps are `#[ignore]`d; run them in release mode:
//!
//! ```text
//! cargo test --release --test exhaustive_test -- --ignored --nocapture
//! ```

use findchars::{EngineBuilder, MatchStorage, SimdBackend};

/// Sweep every `seq_len`-byte sequence past both backends and compare.
///
/// `offsets` are byte positions within the probe buffer; a `None` entry means
/// "flush against the end of the buffer".
fn sweep_sequences(seq_len: usize, offsets: &[Option<usize>]) {
    let simd = SimdBackend::detect();
    if simd == SimdBackend::Scalar {
        eprintln!("no SIMD backend on this host — sweep is vacuous, skipping");
        return;
    }

    let build = |backend: SimdBackend| {
        EngineBuilder::new()
            .codepoints("comma", b",")
            .codepoint("eaccute", 0xE9)
            .codepoint("cjk", 0x65E5)
            .codepoint("emoji", 0x1F600)
            .backend(backend)
            .build()
            .unwrap()
    };
    let scalar_engine = build(SimdBackend::Scalar);
    let simd_engine = build(simd);

    let vbs = simd.vector_byte_size();
    let buf_len = vbs * 2;
    let mut buf = vec![b'a'; buf_len];

    let mut scalar_storage = MatchStorage::new(256);
    let mut simd_storage = MatchStorage::new(256);

    let total: u64 = 1u64 << (8 * seq_len);
    for value in 0..total {
        for &offset in offsets {
            let at = offset.unwrap_or(buf_len - seq_len);
            for k in 0..seq_len {
                buf[at + k] = (value >> (8 * k)) as u8;
            }

            let scalar_view = scalar_engine.engine.find(&buf, &mut scalar_storage);
            let scalar_positions: Vec<u32> =
                (0..scalar_view.len()).map(|i| scalar_view.position(i)).collect();

            let simd_view = simd_engine.engine.find(&buf, &mut simd_storage);
            let simd_positions: Vec<u32> =
                (0..simd_view.len()).map(|i| simd_view.position(i)).collect();

            if scalar_positions != simd_positions {
                let seq: Vec<u8> = (0..seq_len).map(|k| (value >> (8 * k)) as u8).collect();
                panic!(
                    "backend divergence for sequence {seq:02x?} at offset {at}: \
                     scalar={scalar_positions:?} {simd:?}={simd_positions:?}"
                );
            }

            // Restore filler so the next iteration starts from a clean buffer.
            for k in 0..seq_len {
                buf[at + k] = b'a';
            }
        }
    }
}

/// UTF8-008: all 65,536 two-byte sequences at head, chunk boundary, and end.
#[test]
fn utf8_008_all_2byte_sequences() {
    let vbs = SimdBackend::detect().vector_byte_size();
    sweep_sequences(2, &[Some(0), Some(vbs - 1), None]);
}

/// UTF8-008: all 16,777,216 three-byte sequences, flush against the end of an
/// exactly sized buffer.
#[test]
#[ignore = "16.7M cases — run with --release --ignored"]
fn utf8_008_all_3byte_sequences() {
    sweep_sequences(3, &[None]);
}

/// UTF8-008: all 4,294,967,296 four-byte sequences, flush against the end of an
/// exactly sized buffer.
#[test]
#[ignore = "4.29G cases — run with --release --ignored, takes a long while"]
fn utf8_008_all_4byte_sequences() {
    sweep_sequences(4, &[None]);
}
