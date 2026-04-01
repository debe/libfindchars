//! SIMD backend detection and dispatch.

#[cfg(target_arch = "x86_64")]
pub mod avx2;
pub mod scalar;

use crate::engine::FindFn;

/// Available SIMD instruction set backends.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SimdBackend {
    /// AVX-512 (512-bit vectors, 64 bytes per chunk).
    Avx512,
    /// AVX2 (256-bit vectors, 32 bytes per chunk).
    Avx2,
    /// ARM NEON (128-bit vectors, 16 bytes per chunk).
    Neon,
    /// Scalar fallback (for testing only — not a production backend).
    Scalar,
}

impl SimdBackend {
    /// Detect the best available SIMD backend for this platform.
    pub fn detect() -> Self {
        #[cfg(target_arch = "x86_64")]
        {
            if is_x86_feature_detected!("avx512bw") && is_x86_feature_detected!("avx512vbmi") {
                return SimdBackend::Avx512;
            }
            if is_x86_feature_detected!("avx2") && is_x86_feature_detected!("ssse3") {
                return SimdBackend::Avx2;
            }
        }
        #[cfg(target_arch = "aarch64")]
        {
            return SimdBackend::Neon;
        }
        SimdBackend::Scalar
    }

    /// Vector byte width for this backend.
    pub fn vector_byte_size(self) -> usize {
        match self {
            SimdBackend::Avx512 => 64,
            SimdBackend::Avx2 => 32,
            SimdBackend::Neon => 16,
            SimdBackend::Scalar => 16,
        }
    }

    /// Maximum number of distinct literals (vectorByteSize - 1).
    pub fn max_literals(self) -> usize {
        self.vector_byte_size() - 1
    }

    /// Get the find function pointer for this backend.
    pub(crate) fn find_fn(self) -> FindFn {
        match self {
            #[cfg(target_arch = "x86_64")]
            SimdBackend::Avx2 => |engine, data, storage| {
                // SAFETY: AVX2 availability is checked at engine build time
                unsafe { avx2::find_avx2(engine, data, storage) }
            },
            SimdBackend::Avx512 => {
                // TODO: Phase 6
                scalar::find_scalar
            }
            SimdBackend::Neon => {
                // TODO: Phase 6
                scalar::find_scalar
            }
            SimdBackend::Scalar => scalar::find_scalar,
            #[cfg(not(target_arch = "x86_64"))]
            SimdBackend::Avx2 => scalar::find_scalar,
        }
    }
}
