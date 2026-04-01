//! Z3-based constraint solver for findchars shuffle LUT generation.
//!
//! Solves the nibble matrix problem: given a set of target bytes, find two
//! 16-entry LUT vectors whose AND yields a unique literal byte for each target
//! and zero for all non-targets.
//!
//! Used as a build-dependency (via `build.rs`) or at runtime.

pub mod literal;
pub mod solver;

pub use literal::{AsciiFindMask, AsciiLiteralGroup, ByteLiteral};
pub use solver::LiteralCompiler;
