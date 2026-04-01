//! ARM NEON backend — 128-bit (16 bytes per chunk) SIMD detection.
//!
//! Uses NEON `vqtbl1q_u8` for 16-byte shuffle lookup, `vceqq_u8` for
//! byte comparison, and `vmaxvq_u8` for fast rejection.
//!
//! TODO: Implement when cross-compilation testing is available.
//! The structure mirrors AVX2 but with 128-bit operations and no lane splitting.

#[cfg(target_arch = "aarch64")]
use std::arch::aarch64::*;

#[cfg(target_arch = "aarch64")]
use crate::engine::{EngineData, MatchStorage};

/// NEON find implementation. Processes 16 bytes per chunk.
///
/// # Safety
/// Caller must ensure NEON is available (always on aarch64).
#[cfg(target_arch = "aarch64")]
#[target_feature(enable = "neon")]
pub(crate) unsafe fn find_neon(
    _engine: &EngineData,
    _data: &[u8],
    _storage: &mut MatchStorage,
) -> usize {
    // TODO: implement NEON backend
    // For now, fall back to scalar via the dispatch in mod.rs
    unimplemented!("NEON backend not yet implemented")
}
