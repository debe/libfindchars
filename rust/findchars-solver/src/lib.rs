//! Z3-based constraint solver for findchars shuffle LUT generation.
//!
//! Solves the nibble matrix problem: given a set of target bytes, find two
//! 16-entry LUT vectors whose AND yields a unique literal byte for each target
//! and, for every non-target, a value that collides with no literal. Non-target
//! results are not required to be zero — a secondary clean LUT zeroes them at
//! runtime — so the guarantee here is non-collision, not zero output.
//!
//! Invoked by `findchars` at engine-construction time (`EngineBuilder::build()`),
//! outside the hot detection path.

pub mod literal;
pub mod solver;

pub use literal::{AsciiFindMask, AsciiLiteralGroup, ByteLiteral};
pub use solver::{LiteralCompiler, SolveError};
