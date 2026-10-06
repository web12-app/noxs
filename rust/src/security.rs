//! security — filesystem policy shared by extraction and package install.
//!
//! The rules here are the last line of defense before anything touches disk
//! (spec §33): path traversal, absolute member paths, symlink escapes and
//! device nodes are all rejected BEFORE extraction starts.

/// A member path as it appears inside an archive.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PathVerdict {
    Safe,
    Unsafe,
}

/// Decide whether a member path may be written inside [destination-rooted]
/// extraction. `.` segments are allowed; `..`, absolute paths, drive letters
/// and NUL bytes are not.
pub fn check_member_path(path: &str) -> PathVerdict {
    if path.is_empty() || path.len() > 4096 || path.contains('\0') {
        return PathVerdict::Unsafe;
    }
    if path.starts_with('/') || path.starts_with('\\') {
        return PathVerdict::Unsafe;
    }
    // Windows drive letters and UNC prefixes never appear in a safe archive.
    let upper = path.to_ascii_uppercase();
    if upper.len() >= 2 && upper.as_bytes()[1] == b':' {
        return PathVerdict::Unsafe;
    }
    if upper.starts_with("\\\\") {
        return PathVerdict::Unsafe;
    }
    for segment in path.split(['/', '\\']) {
        if segment == ".." {
            return PathVerdict::Unsafe;
        }
    }
    PathVerdict::Safe
}

/// Decide whether a symlink target stays inside the extraction root.
/// Guest-absolute targets (/etc/passwd style) are host-shaped and rejected;
/// relative targets are resolved virtually and must not climb out.
pub fn check_symlink_target(target: &str) -> PathVerdict {
    if target.is_empty() || target.contains('\0') {
        return PathVerdict::Unsafe;
    }
    if target.starts_with('/') {
        // Host-shaped absolute target: treated as an escape attempt.
        return PathVerdict::Unsafe;
    }
    // Relative target resolved from an arbitrary member directory: depth
    // checks happen in the archive walker which knows the member directory.
    PathVerdict::Safe
}

/// Whether a relative symlink target from [member_dir] stays inside the root.
pub fn symlink_stays_inside(member_dir: &str, target: &str) -> PathVerdict {
    if check_symlink_target(target) == PathVerdict::Unsafe {
        return PathVerdict::Unsafe;
    }
    let mut depth: i64 = member_dir.split('/').filter(|s| !s.is_empty()).count() as i64;
    for segment in target.split('/') {
        match segment {
            "." | "" => {}
            ".." => {
                depth -= 1;
                if depth < 0 {
                    return PathVerdict::Unsafe;
                }
            }
            _ => depth += 1,
        }
    }
    PathVerdict::Safe
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn traversal_is_rejected() {
        assert_eq!(check_member_path("usr/bin/tree"), PathVerdict::Safe);
        assert_eq!(check_member_path("usr/./local/bin"), PathVerdict::Safe);
        assert_eq!(check_member_path("../escape"), PathVerdict::Unsafe);
        assert_eq!(check_member_path("a/../../escape"), PathVerdict::Unsafe);
        assert_eq!(check_member_path("/etc/passwd"), PathVerdict::Unsafe);
        assert_eq!(check_member_path("C:\\Windows"), PathVerdict::Unsafe);
        assert_eq!(check_member_path("\\\\server\\share"), PathVerdict::Unsafe);
    }

    #[test]
    fn symlink_escapes_are_rejected() {
        assert_eq!(symlink_stays_inside("usr/bin", "lib/libz.so"), PathVerdict::Safe);
        assert_eq!(symlink_stays_inside("usr/bin", "../lib/x"), PathVerdict::Safe);
        assert_eq!(symlink_stays_inside("usr/bin", "../../etc/shadow"), PathVerdict::Unsafe);
        assert_eq!(symlink_stays_inside("usr/bin", "/etc/shadow"), PathVerdict::Unsafe);
    }
}
