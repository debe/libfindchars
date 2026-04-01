/// Errors that can occur during engine construction or detection.
#[derive(Debug, thiserror::Error)]
pub enum FindCharsError {
    /// The constraint solver failed to find a valid LUT pair.
    #[error("solver failed: {0}")]
    SolverFailed(String),

    /// Too many literals for the platform's vector width.
    #[error("literal namespace exceeded: {configured} configured, max {max}")]
    NamespaceExceeded { configured: usize, max: usize },

    /// Invalid configuration.
    #[error("invalid configuration: {0}")]
    InvalidConfig(String),
}
