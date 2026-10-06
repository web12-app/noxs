//! runtime — build and runtime metadata for the Noxs core.

/// Core version, aligned with the Noxs release that carries it.
pub const CORE_VERSION: &str = env!("CARGO_PKG_VERSION");

/// Supported architectures (spec §12).
pub const SUPPORTED_ARCHES: [&str; 3] = ["aarch64", "x86_64", "armv7"];

/// Stable description used by diagnostics (never includes internal paths).
pub fn describe() -> String {
    format!("noxs-core {} (arches: {})", CORE_VERSION, SUPPORTED_ARCHES.join(", "))
}

#[cfg(test)]
mod tests {
    #[test]
    fn describe_mentions_no_internal_paths() {
        let text = super::describe();
        assert!(text.starts_with("noxs-core "));
        assert!(!text.contains('/'));
    }
}
