//! package — NX package manifest and release-asset policy (spec §27).
//!
//! Pure policy checks used by the package engine before downloads,
//! extraction or registry updates: asset naming, architecture matching,
//! registry sanity. Untrusted registry content is rejected here first.

use crate::validation;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Arch {
    Aarch64,
    X86_64,
    Armv7,
    Universal,
}

impl Arch {
    pub fn parse(value: &str) -> Option<Arch> {
        Some(match value {
            "aarch64" => Arch::Aarch64,
            "x86_64" => Arch::X86_64,
            "armv7" => Arch::Armv7,
            "universal" => Arch::Universal,
            _ => return None,
        })
    }
}

/// `<name>.nx.pkg.<version>[.<arch>].tar.xz` — the only accepted asset shape.
pub fn valid_asset_name(asset: &str) -> bool {
    let Some(stripped) = asset.strip_suffix(".tar.xz") else {
        return false;
    };
    let parts: Vec<&str> = stripped.split('.').collect();
    if parts.len() < 4 {
        return false;
    }
    let name = parts[0];
    if !valid_package_name(name) {
        return false;
    }
    if parts[1] != "nx" || parts[2] != "pkg" {
        return false;
    }
    if !validation::valid_semver(parts[3]) {
        return false;
    }
    if parts.len() == 5 {
        return Arch::parse(parts[4]).is_some();
    }
    parts.len() == 4
}

/// Package names: lowercase, no traversal, no shell metacharacters —
/// mirrored from the shell-side validator so both layers agree.
pub fn valid_package_name(name: &str) -> bool {
    validation::valid_package_id(name)
}

/// Pick the release asset for the running architecture: exact arch first,
/// then universal; anything else is "no compatible release".
pub fn pick_asset<'a>(assets: &[&'a str], arch: Arch) -> Option<&'a str> {
    let exact = assets.iter().copied().find(|asset| {
        asset.contains(&format!(".{}.", arch_name(arch))) && asset.ends_with(".tar.xz")
    });
    if let Some(found) = exact {
        return Some(found);
    }
    assets
        .iter()
        .copied()
        .find(|asset| asset.contains(".universal.") && asset.ends_with(".tar.xz"))
}

fn arch_name(arch: Arch) -> &'static str {
    match arch {
        Arch::Aarch64 => "aarch64",
        Arch::X86_64 => "x86_64",
        Arch::Armv7 => "armv7",
        Arch::Universal => "universal",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn asset_names() {
        assert!(valid_asset_name("tree.nx.pkg.1.0.0.aarch64.tar.xz"));
        assert!(valid_asset_name("tree.nx.pkg.1.0.0.tar.xz"));
        assert!(!valid_asset_name("tree.nx.pkg.01.0.0.aarch64.tar.xz"));
        assert!(!valid_asset_name("tree.nx.pkg.1.0.0.arm64.tar.xz"));
        assert!(!valid_asset_name("tree.zip"));
        assert!(!valid_asset_name("../tree.nx.pkg.1.0.0.tar.xz"));
    }

    #[test]
    fn asset_picking_prefers_exact_arch() {
        let assets = [
            "tree.nx.pkg.1.0.0.x86_64.tar.xz",
            "tree.nx.pkg.1.0.0.universal.tar.xz",
        ];
        assert_eq!(
            pick_asset(&assets, Arch::X86_64),
            Some("tree.nx.pkg.1.0.0.x86_64.tar.xz")
        );
        assert_eq!(
            pick_asset(&assets, Arch::Armv7),
            Some("tree.nx.pkg.1.0.0.universal.tar.xz")
        );
        assert_eq!(pick_asset(&["tree.nx.pkg.1.0.0.x86_64.tar.xz"], Arch::Aarch64), None);
    }
}
