#!/usr/bin/env bash
#
# fmt.sh — run `kolt fmt` across the three top-level kolt.toml projects.
#
# kolt has no multi-module support yet, so we loop explicitly over root,
# kolt-jvm-compiler-daemon, and kolt-native-compiler-daemon. Spike projects
# under spike/ are intentionally excluded.
#
# All arguments are forwarded to `kolt fmt`. The pre-commit hook passes
# `--check`. Override the binary with `KOLT=./build/kolt.kexe`.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

KOLT="${KOLT:-kolt}"

for project in "." "kolt-jvm-compiler-daemon" "kolt-native-compiler-daemon"; do
  echo "fmt: $project"
  (
    cd "$project"
    "$KOLT" fmt "$@"
  )
done
