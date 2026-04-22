#!/usr/bin/env bash
#
# install-hooks.sh — symlink .git/hooks/pre-commit to scripts/pre-commit.
# Idempotent: re-running overwrites the existing symlink without error.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ln -sf ../../scripts/pre-commit .git/hooks/pre-commit
echo "installed .git/hooks/pre-commit -> scripts/pre-commit"
