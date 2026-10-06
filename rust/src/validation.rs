//! validation — URL and identifier policy for the Noxs core.
//!
//! Mirrors the Kotlin NoxsUrlGuard policy exactly (spec §18): only http/https
//! are loadable; javascript/data/file/content/intent/chrome/android-app and
//! friends are rejected; hosts are required; whitespace/control input dies.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UrlDecision {
    Allowed,
    Rejected,
}

fn scheme_of(url: &str) -> Option<&str> {
    let index = url.find(':')?;
    let scheme = &url[..index];
    if scheme.is_empty() {
        return None;
    }
    Some(scheme)
}

/// Full load policy for a URL that would reach a Noxs web view.
pub fn check_url(url: &str) -> UrlDecision {
    if url.is_empty() || url.len() > 2048 {
        return UrlDecision::Rejected;
    }
    if url.chars().any(|c| c.is_whitespace() || (c as u32) < 0x20 || (c as u32) == 0x7F) {
        return UrlDecision::Rejected;
    }
    let Some(scheme) = scheme_of(url) else {
        return UrlDecision::Rejected;
    };
    let scheme = scheme.to_ascii_lowercase();
    if scheme != "http" && scheme != "https" {
        return UrlDecision::Rejected;
    }
    // A scheme must be followed by a non-empty, non-root-only host section.
    let rest = &url[url.find(':').unwrap() + 1..];
    let rest = rest.trim_start_matches("//");
    let host = rest.split(['/', '?', '#']).next().unwrap_or("");
    if host.is_empty() || host.starts_with('.') {
        return UrlDecision::Rejected;
    }
    UrlDecision::Allowed
}

/// Package identifiers: lowercase alnum plus `.`, `_`, `-` (no traversal,
/// no shell metacharacters — the same grammar the shell side enforces).
pub fn valid_package_id(id: &str) -> bool {
    let bytes = id.as_bytes();
    if bytes.is_empty() || bytes.len() > 64 {
        return false;
    }
    let first = bytes[0];
    if !(first.is_ascii_lowercase() || first.is_ascii_digit()) {
        return false;
    }
    bytes.iter().all(|&b| {
        b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'.' || b == b'-' || b == b'_'
    })
}

/// Semantic version acceptance (MAJOR.MINOR.PATCH, no leading zeros).
pub fn valid_semver(version: &str) -> bool {
    let parts: Vec<&str> = version.split('.').collect();
    if parts.len() != 3 {
        return false;
    }
    parts.iter().all(|part| {
        !part.is_empty()
            && part.len() <= 10
            && part.bytes().all(|b| b.is_ascii_digit())
            && (part.len() == 1 || !part.starts_with('0'))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn urls_follow_the_noxs_policy() {
        assert_eq!(check_url("https://example.com/x"), UrlDecision::Allowed);
        assert_eq!(check_url("http://localhost:8080"), UrlDecision::Allowed);
        assert_eq!(check_url("javascript:alert(1)"), UrlDecision::Rejected);
        assert_eq!(check_url("data:text/html,x"), UrlDecision::Rejected);
        assert_eq!(check_url("file:///etc/passwd"), UrlDecision::Rejected);
        assert_eq!(check_url("intent://x"), UrlDecision::Rejected);
        assert_eq!(check_url("chrome://flags"), UrlDecision::Rejected);
        assert_eq!(check_url("https://exa\tmple.com"), UrlDecision::Rejected);
        assert_eq!(check_url(""), UrlDecision::Rejected);
        assert_eq!(check_url("https://"), UrlDecision::Rejected);
    }

    #[test]
    fn package_ids_and_semver() {
        assert!(valid_package_id("tree"));
        assert!(valid_package_id("nx-info.tool"));
        assert!(!valid_package_id("../escape"));
        assert!(!valid_package_id("Bad"));
        assert!(!valid_package_id(""));
        assert!(!valid_package_id("a;b"));
        assert!(valid_semver("1.0.0"));
        assert!(valid_semver("10.20.30"));
        assert!(!valid_semver("01.0.0"));
        assert!(!valid_semver("1.0"));
        assert!(!valid_semver("1.0.x"));
    }
}
