//! SIMD backend detection and dispatch.

pub mod scalar;

/// Available SIMD instruction set backends.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SimdBackend {
    /// AVX-512 (512-bit vectors, 64 bytes per chunk).
    Avx512,
    /// AVX2 (256-bit vectors, 32 bytes per chunk).
    Avx2,
    /// SSE 4.2 + SSSE3 (128-bit vectors, 16 bytes per chunk).
    Sse42,
    /// ARM NEON (128-bit vectors, 16 bytes per chunk).
    Neon,
    /// Scalar fallback (no SIMD, 1 byte per iteration).
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
            if is_x86_feature_detected!("sse4.2") && is_x86_feature_detected!("ssse3") {
                return SimdBackend::Sse42;
            }
        }
        #[cfg(target_arch = "aarch64")]
        {
            // NEON is always available on aarch64
            return SimdBackend::Neon;
        }
        SimdBackend::Scalar
    }

    /// Vector byte width for this backend.
    pub fn vector_byte_size(self) -> usize {
        match self {
            SimdBackend::Avx512 => 64,
            SimdBackend::Avx2 => 32,
            SimdBackend::Sse42 | SimdBackend::Neon => 16,
            SimdBackend::Scalar => 16, // process in 16-byte logical chunks
        }
    }

    /// Maximum number of distinct literals (vectorByteSize - 1).
    pub fn max_literals(self) -> usize {
        self.vector_byte_size() - 1
    }
}
