#!/bin/sh
# Verify install.sh announces every download step on stderr (#405).
#
# Runs the full installer end-to-end against file:// URLs so no network is
# touched. Asserts a "fetching ..." line and a completion line for each of
# the four fetches: release listing, YANKED manifest, tarball, sha256.
set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
INSTALL_SH="${INSTALL_SH:-$SCRIPT_DIR/../install.sh}"

TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT

VERSION="0.18.1"
PLATFORM="linux-x64"
EXTRACTED_NAME="kolt-${VERSION}-${PLATFORM}"
TARBALL_NAME="${EXTRACTED_NAME}.tar.gz"

RELEASES_DIR="$TESTDIR/releases"
mkdir -p "$RELEASES_DIR"

WORK="$TESTDIR/work"
mkdir -p "$WORK/$EXTRACTED_NAME/bin"
mkdir -p "$WORK/$EXTRACTED_NAME/libexec/classpath"
printf '#!/bin/sh\necho mock\n' > "$WORK/$EXTRACTED_NAME/bin/kolt"
chmod +x "$WORK/$EXTRACTED_NAME/bin/kolt"
(cd "$WORK" && tar czf "$RELEASES_DIR/$TARBALL_NAME" "$EXTRACTED_NAME")
(cd "$RELEASES_DIR" && sha256sum "$TARBALL_NAME" > "$TARBALL_NAME.sha256")

: > "$TESTDIR/YANKED"

cat > "$TESTDIR/releases.json" <<EOF
[{"tag_name":"v$VERSION"}]
EOF

TEST_HOME="$TESTDIR/home"
mkdir -p "$TEST_HOME"

STDERR_LOG="$TESTDIR/stderr.log"
STDOUT_LOG="$TESTDIR/stdout.log"

HOME="$TEST_HOME" \
KOLT_TEST_BASE_URL="file://$RELEASES_DIR" \
KOLT_TEST_YANKED_URL="file://$TESTDIR/YANKED" \
KOLT_TEST_RELEASES_URL="file://$TESTDIR/releases.json" \
sh "$INSTALL_SH" >"$STDOUT_LOG" 2>"$STDERR_LOG"

fail() {
    echo "FAIL: $1" >&2
    echo "--- stderr was: ---" >&2
    cat "$STDERR_LOG" >&2
    echo "--- stdout was: ---" >&2
    cat "$STDOUT_LOG" >&2
    exit 1
}

grep -q "fetching .*release" "$STDERR_LOG" || fail "missing release-list announcement"
grep -q "fetching .*YANKED" "$STDERR_LOG" || fail "missing YANKED announcement"
grep -q "fetching .*tarball" "$STDERR_LOG" || fail "missing tarball announcement"
grep -q "fetching .*checksum" "$STDERR_LOG" || fail "missing checksum announcement"

completions=$(grep -c "^  done (" "$STDERR_LOG" || true)
[ "$completions" -ge 4 ] || fail "expected >= 4 completion lines, got $completions"

echo "PASS"
