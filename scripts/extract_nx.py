#!/usr/bin/env python3
"""extract_nx.py — shared extractor for the NX Package System Kotlin templates.

The canonical source of every `nx` shell artifact is a Kotlin template file
(raw strings where a literal bash `$` is written as `§` and expanded by
.replace('§', '$') at load time). This module extracts and expands those
strings so the rest of the tooling (sync check, functional tests, mirrors)
can work with the real script content.

Usage:
    from extract_nx import nx_constants, nx_template_files
"""
import pathlib
import re

RUNTIME_DIR = "app/src/main/java/com/crossberry/noxs/runtime"

# constant name -> (kotlin file, relative mirror path)
SHELL_CONSTANTS = {
    "NX_CLI": ("NoxsNxTemplate.kt", "linux-runtime/launcher/nx"),
    "PKG_LIB": ("NoxsNxPkgLib.kt", "linux-runtime/nx/pkg-lib.sh"),
    "PKG_INIT": ("NoxsNxPkgInit.kt", "linux-runtime/nx/pkg-init.sh"),
    "PKG_DEV": ("NoxsNxPkgDev.kt", "linux-runtime/nx/pkg-dev.sh"),
    "PKG_INSTALL": ("NoxsNxPkgInstall.kt", "linux-runtime/nx/pkg-install.sh"),
    "WEB_LIB": ("NoxsNxWebTemplate.kt", "linux-runtime/nx/web-lib.sh"),
}

WORKFLOW_FILE = "NoxsNxWorkflow.kt"
TEMPLATES_FILE = "NoxsNxPackageTemplates.kt"
API_TEMPLATES_FILE = "NoxsNxApiTemplate.kt"
WEB_FILE = "NoxsNxWebTemplate.kt"


def _expand(raw: str) -> str:
    return raw.replace("${'$'}", "$").replace("\u00a7", "$")


def _read(repo_root: pathlib.Path, name: str) -> str:
    return (repo_root / RUNTIME_DIR / name).read_text(encoding="utf-8")


def nx_constants(repo_root: pathlib.Path) -> dict:
    """Extract all `val X = \"\"\"...\"\"\"` constants from the nx Kotlin files.

    Returns {constant_name: expanded_content} including WORKFLOW_YML and the
    private per-file template constants (everything raw-string shaped).
    """
    out = {}
    kt_files = sorted(
        {v[0] for v in SHELL_CONSTANTS.values()}
        | {WORKFLOW_FILE, TEMPLATES_FILE, API_TEMPLATES_FILE, WEB_FILE}
    )
    for kt in kt_files:
        text = _read(repo_root, kt)
        for m in re.finditer(r'val\s+(\w+)\s*=\s*"""(.*?)"""', text, re.S):
            out[m.group(1)] = _expand(m.group(2))
    return out


def nx_shell_scripts(repo_root: pathlib.Path):
    """[(const_name, content, mirror_relative_path), ...] for shell scripts."""
    consts = nx_constants(repo_root)
    result = []
    for const, (kt, mirror) in SHELL_CONSTANTS.items():
        result.append((const, consts[const], mirror))
    return result


def nx_template_files(repo_root: pathlib.Path) -> dict:
    """{relative_template_path: content} replicating NoxsNxPackageTemplates.FILES
    plus the per-language assembled copies (workflow + common files)."""
    consts = nx_constants(repo_root)
    files = {}
    for m in re.finditer(
        r'"([^"]+)"\s+to\s+(\w+),', _read(repo_root, TEMPLATES_FILE)
    ):
        rel, const = m.group(1), m.group(2)
        if const not in consts:
            raise KeyError(f"template constant {const} not found for {rel}")
        files[rel] = consts[const]

    # Assemble what installNxPackageSystem builds on-device.
    workflow = consts.get("WORKFLOW_YML")
    if workflow is None:
        raise KeyError("WORKFLOW_YML not found")
    langs = ["nodejs", "rust", "cpp", "python", "go"]
    commons = ["registry.json", "VERSION", "README.md", "LICENSE"]
    for lang in langs:
        files[f"templates/{lang}/files/.github/workflows/pkg.yml"] = workflow
        for name in commons:
            files[f"templates/{lang}/files/{name}"] = files[f"templates/_shared/{name}"]
    return files


def materialize_template_root(repo_root: pathlib.Path, dest: pathlib.Path) -> None:
    """Write the assembled template tree under dest/ (as on a device)."""
    files = nx_template_files(repo_root)
    for rel, content in files.items():
        # rel already starts with "templates/"
        target = dest / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    import sys
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    for name, content, mirror in nx_shell_scripts(root):
        print(f"{name}: {len(content.splitlines())} lines -> {mirror}")
    for rel, content in sorted(nx_template_files(root).items()):
        print(f"template {rel}: {len(content.splitlines())} lines")
