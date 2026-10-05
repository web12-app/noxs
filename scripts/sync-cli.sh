#!/usr/bin/env bash
# shellcheck disable=SC1090,SC1091,SC2012,SC2034,SC2086,SC2164,SC2295
# scripts/sync-cli.sh — regenerate the canonical noxs CLI and SAF setup shortcut
# from the Kotlin templates (single source of truth), then verify the sync.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import pathlib, re, sys
root = pathlib.Path(sys.argv[1])
kt = (root / 'app/src/main/java/com/crossberry/noxs/runtime/NoxsCliTemplate.kt').read_text()
m = re.search(r'val CLI = """\n?(.*?)"""', kt, re.S)
assert m, 'template markers not found'
content = m.group(1).replace("${'$'}", "$")
out = root / 'linux-runtime/launcher/noxs-cli'
out.write_text(content)
m2 = re.search(r'val STORAGE_SETUP = """\n?(.*?)"""', kt, re.S)
assert m2, 'storage setup template markers not found'
storage_setup = m2.group(1).replace("${'$'}", "$")
setup_out = root / 'linux-runtime/launcher/noxs-setup-storage'
setup_out.write_text(storage_setup)
print(f'[noxs] canonical noxs-cli regenerated ({len(content.splitlines())} lines)')
print(f'[noxs] storage setup shortcut regenerated ({len(storage_setup.splitlines())} lines)')
PY
chmod +x "$ROOT/linux-runtime/launcher/noxs-cli" "$ROOT/linux-runtime/launcher/noxs-setup-storage"
