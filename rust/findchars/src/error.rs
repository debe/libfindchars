/// Errors that can occur during engine construction or detection.
#[derive(Debug, thiserror::Error)]
pub enum FindCharsError {
    /// The constraint solver failed to find a valid LUT pair.
    #[error("solver failed: {0}")]
    SolverFailed(String),

    /// A solved LUT pair failed the exhaustive 256-byte check.
    #[error("solver produced an invalid LUT: {0}")]
    SolverVerificationFailed(String),

    /// Too many literals for the platform's vector width.
    #[error("literal namespace exceeded: {configured} configured, max {max}")]
    NamespaceExceeded { configured: usize, max: usize },

    /// Invalid configuration.
    #[error("invalid configuration: {0}")]
    InvalidConfig(String),
}
