#!/usr/bin/env python3
"""sync_nx.py — keep the linux-runtime mirrors of the nx Kotlin templates
in sync (the Kotlin files are the single source of truth).

    sync_nx.py --write    regenerate linux-runtime/ mirrors from Kotlin
    sync_nx.py --check    exit 1 when a mirror differs (CI sync-check)
"""
import pathlib
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import extract_nx  # noqa: E402


def expected_files(root: pathlib.Path):
    """{mirror_relative_path: content} from the Kotlin source of truth."""
    out = {}
    for _name, content, mirror in extract_nx.nx_shell_scripts(root):
        out[mirror] = content
    for rel, content in extract_nx.nx_template_files(root).items():
        # template rel paths start with "templates/" -> mirror under
        # linux-runtime/nx/templates/
        out["linux-runtime/nx/" + rel] = content
    for rel, content in extract_nx.nx_ai_python_files(root).items():
        out[rel] = content
    # The § escape must be fully expanded everywhere: a leftover § means a
    # literal section-sign leaked into the artifact.
    for rel, content in out.items():
        if "\u00a7" in content:
            raise ValueError(f"unexpanded § in {rel}")
    return out


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else "--check"
    root = pathlib.Path(__file__).resolve().parent.parent
    expected = expected_files(root)

    if mode == "--write":
        for rel, content in sorted(expected.items()):
            target = root / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
            if rel.endswith(".sh") or "/nx" == rel[-3:]:
                target.chmod(0o755)
        print(f"[nx] wrote {len(expected)} mirror files")
        return 0

    if mode == "--check":
        bad = []
        for rel, content in sorted(expected.items()):
            target = root / rel
            if not target.is_file():
                bad.append(f"missing: {rel}")
            elif target.read_text(encoding="utf-8") != content:
                bad.append(f"differs: {rel}")
        if bad:
            print("nx mirror sync FAILED — run scripts/sync_nx.py --write")
            for item in bad:
                print(f"  {item}")
            return 1
        print(f"  OK {len(expected)} nx mirrors match their Kotlin templates")
        return 0

    print(f"usage: {sys.argv[0]} --check|--write", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
