#!/usr/bin/env python3
"""extract_payload.py — assemble the nx-installer payload layout from the
canonical Kotlin templates (single source of truth).

    extract_payload.py <repo_root> <payload_dir>

Produces:
    payload/bin/nx
    payload/lib/noxs-pkg/{pkg-lib,pkg-init,pkg-dev,pkg-install,web-lib}.sh
    payload/lib/noxs/nx-api/{nx-api.js,package.json,README.md}
    payload/share/noxs-pkg/templates/...   (assembled per-language trees)
    payload/share/noxs-pkg/.nx-version
"""
import pathlib
import shutil
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import extract_nx  # noqa: E402

SHELL_MIRRORS = {
    "pkg-lib.sh": "PKG_LIB",
    "pkg-init.sh": "PKG_INIT",
    "pkg-dev.sh": "PKG_DEV",
    "pkg-install.sh": "PKG_INSTALL",
    "web-lib.sh": "WEB_LIB",
}


def main() -> int:
    root = pathlib.Path(sys.argv[1]).resolve()
    payload = pathlib.Path(sys.argv[2]).resolve()

    consts = extract_nx.nx_constants(root)

    # nx CLI
    nx_path = payload / "bin" / "nx"
    nx_path.parent.mkdir(parents=True, exist_ok=True)
    nx_path.write_text(consts["NX_CLI"], encoding="utf-8")
    nx_path.chmod(0o755)

    # package + web modules
    lib_dir = payload / "lib" / "noxs-pkg"
    lib_dir.mkdir(parents=True, exist_ok=True)
    for name, const in SHELL_MIRRORS.items():
        target = lib_dir / name
        target.write_text(consts[const], encoding="utf-8")
        target.chmod(0o755)

    # @noxs/nx-api SDK
    api_dir = payload / "lib" / "noxs" / "nx-api"
    api_dir.mkdir(parents=True, exist_ok=True)
    (api_dir / "nx-api.js").write_text(consts["API_JS"], encoding="utf-8")
    (api_dir / "package.json").write_text(consts["PACKAGE_JSON"], encoding="utf-8")
    (api_dir / "README.md").write_text(consts["API_README"], encoding="utf-8")

    # language templates (assembled exactly like installNxPackageSystem)
    share = payload / "share" / "noxs-pkg"
    templates = share / "templates"
    staging = pathlib.Path(str(payload) + ".templates")
    shutil.rmtree(staging, ignore_errors=True)
    staging.mkdir(parents=True)
    extract_nx.materialize_template_root(root, staging)
    if templates.exists():
        shutil.rmtree(templates)
    shutil.copytree(staging / "templates", templates)
    # The version marker lives next to templates/ (as on a device).
    import re
    configurator = (root / "app/src/main/java/com/crossberry/noxs/runtime/RootfsConfigurator.kt").read_text(encoding="utf-8")
    match = re.search(r'NX_PACKAGE_SYSTEM_VERSION\s*=\s*"(\d+)"', configurator)
    version = match.group(1) if match else "0"
    (share / ".nx-version").write_text(version + "\n", encoding="utf-8")
    shutil.rmtree(staging)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
