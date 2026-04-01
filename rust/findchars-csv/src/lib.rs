//! SIMD-accelerated CSV parser built on findchars.
//!
//! Two-phase architecture:
//! 1. SIMD scan detects structural characters (delimiter, quote, newline, CR)
//! 2. Linear match walk extracts field and row boundaries
//!
//! Zero-copy: field boundaries are stored as byte offsets into the original data.
