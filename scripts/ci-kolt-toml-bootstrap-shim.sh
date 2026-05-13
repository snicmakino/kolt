#!/usr/bin/env bash
# Pre-v0.20.0 bootstrap shim: converts [repositories.<name>] sub-table
# declarations in a kolt.toml back to the legacy flat-form ([repositories] /
# <name> = "<url>") that pre-#320 kolt binaries understand. Used by CI to
# bootstrap-build current HEAD with KOLT_BOOTSTRAP_TAG (still on v0.19.x)
# until a release shipping the new schema lands.
#
# Remove this script, drop the CI wrappers calling it, and bump
# KOLT_BOOTSTRAP_TAG once v0.20.0 (or any subsequent release that ships the
# sub-table schema) is published.
#
# Usage:
#   ci-kolt-toml-bootstrap-shim.sh apply    <path>...    # backs up <path> to <path>.bak, rewrites <path>
#   ci-kolt-toml-bootstrap-shim.sh restore  <path>...    # moves <path>.bak back to <path>

set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 {apply|restore} <path>..." >&2
  exit 2
fi

mode="$1"
shift

case "$mode" in
  apply)
    for path in "$@"; do
      [ -f "$path" ] || { echo "shim: $path not found" >&2; exit 1; }
      cp "$path" "$path.bak"
      python3 - "$path" <<'PY'
import re
import sys

path = sys.argv[1]
content = open(path).read()

pattern = re.compile(r"^\[repositories\.([^\]]+)\]\nurl = \"([^\"]+)\"\n?", re.MULTILINE)
entries = pattern.findall(content)
if not entries:
    sys.exit(0)

# Remove every sub-table block, then append one flat [repositories] section.
new_content = pattern.sub("", content).rstrip() + "\n\n"
flat = "[repositories]\n" + "\n".join(f'{name} = "{url}"' for name, url in entries) + "\n"
open(path, "w").write(new_content + flat)
PY
    done
    ;;
  restore)
    for path in "$@"; do
      [ -f "$path.bak" ] || { echo "shim: $path.bak not found; cannot restore" >&2; exit 1; }
      mv "$path.bak" "$path"
    done
    ;;
  *)
    echo "usage: $0 {apply|restore} <path>..." >&2
    exit 2
    ;;
esac
