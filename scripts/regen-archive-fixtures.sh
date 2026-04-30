#!/bin/sh
# regen-archive-fixtures.sh
#
# Regenerate fixture archives used by ArchiveExtractionTest under
# src/nativeTest/resources/archive-fixtures/.
#
# This script is rerun manually by a developer when the fixture set changes
# (entry layout, mode bits, symlink targets, security violations, etc.).
# It is NOT on the build path and is not invoked by Gradle or CI.
#
# Fixtures produced (under src/nativeTest/resources/archive-fixtures/):
#   happy.zip               - regular.txt (0644), executable.sh (0755),
#                             subdir/nested.txt, link-to-regular -> regular.txt.
#                             Used by extractArchive happy-path tests.
#   happy.tar.gz            - same logical contents as happy.zip but in
#                             tar+gzip form, exercising libarchive's
#                             format / filter dispatch.
#   path-traversal.zip      - contains an entry named '../escape.txt'.
#                             ARCHIVE_EXTRACT_SECURE_NODOTDOT must reject it.
#   absolute-path.zip       - contains an entry named '/tmp/evil.txt'.
#                             ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS must
#                             reject it.
#   external-symlink.tar.gz - contains a symlink whose target escapes the
#                             extraction root ('../../outside').
#                             ARCHIVE_EXTRACT_SECURE_SYMLINKS must reject
#                             extraction-time follow.
#   corrupt.zip             - first 64 bytes of happy.zip; libarchive read
#                             must fail with ExtractError.ReadFailed.
#
# Determinism:
#   We set SOURCE_DATE_EPOCH=0 and pass --mtime=@0 / --sort=name to GNU tar,
#   and -X (no extra fields) to Info-ZIP. Bit-exact reproducibility across
#   tool versions and umask is NOT guaranteed (per tasks.md 2.1 the byte
#   match is explicitly optional). The tests assert entry contents and mode
#   bits, not archive bytes, so this is sufficient.
#
# Idempotency:
#   The script wipes any prior fixtures via 'rm -f' for each output file
#   before regenerating. Running it twice in a row produces the same set
#   of files (logically equivalent; not necessarily byte-equal).
#
# Requirements: POSIX sh, GNU tar (for --mtime / --sort), Info-ZIP zip,
# python3 (used to write zip entries that GNU zip refuses to store, e.g.
# '..' and absolute paths), ln, head, install.

set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
OUT_DIR="$REPO_ROOT/src/nativeTest/resources/archive-fixtures"

mkdir -p "$OUT_DIR"

# Determinism knob. Honored by Info-ZIP zip (>= 3.0 with -X) and GNU tar.
SOURCE_DATE_EPOCH=0
export SOURCE_DATE_EPOCH

STAGING=$(mktemp -d)
trap 'rm -rf "$STAGING"' EXIT INT TERM

# ---------- happy.zip ----------------------------------------------------------
HAPPY_DIR="$STAGING/happy"
mkdir -p "$HAPPY_DIR/subdir"
printf 'regular content\n' > "$HAPPY_DIR/regular.txt"
printf '#!/bin/sh\necho ok\n'  > "$HAPPY_DIR/executable.sh"
printf 'nested content\n'      > "$HAPPY_DIR/subdir/nested.txt"
chmod 0644 "$HAPPY_DIR/regular.txt" "$HAPPY_DIR/subdir/nested.txt"
chmod 0755 "$HAPPY_DIR/executable.sh"
ln -s regular.txt "$HAPPY_DIR/link-to-regular"

rm -f "$OUT_DIR/happy.zip"
(
    cd "$HAPPY_DIR"
    # -X strips extra metadata for a more reproducible archive.
    # -y preserves symlinks instead of dereferencing them.
    # -r recurses into subdir/.
    zip -X -y -r -q "$OUT_DIR/happy.zip" \
        regular.txt executable.sh subdir link-to-regular
)

# ---------- happy.tar.gz -------------------------------------------------------
rm -f "$OUT_DIR/happy.tar.gz"
(
    cd "$HAPPY_DIR"
    # --sort=name + --mtime=@0 + --owner/--group=0 normalize ordering and
    # metadata so the tar payload is reproducible across runs. The gzip
    # filter still embeds an mtime; we rely on GZIP=-n via env-less invocation
    # plus 'gzip -n' style behavior of GNU tar's -z which sets mtime=0 when
    # SOURCE_DATE_EPOCH is in env (GNU tar 1.34+).
    tar \
        --sort=name \
        --mtime=@0 \
        --owner=0 --group=0 --numeric-owner \
        --format=ustar \
        -czf "$OUT_DIR/happy.tar.gz" \
        regular.txt executable.sh subdir link-to-regular
)

# ---------- path-traversal.zip -------------------------------------------------
# Info-ZIP's `zip` strips leading '../' segments by default, so we author
# the entry directly with python3's zipfile module. The stored name is
# literally '../escape.txt'.
rm -f "$OUT_DIR/path-traversal.zip"
python3 - "$OUT_DIR/path-traversal.zip" <<'PY'
import sys, zipfile
out = sys.argv[1]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
    info = zipfile.ZipInfo("../escape.txt")
    info.external_attr = (0o644 & 0xFFFF) << 16
    info.date_time = (1980, 1, 1, 0, 0, 0)
    zf.writestr(info, b"escaped\n")
PY

# ---------- absolute-path.zip --------------------------------------------------
# Info-ZIP also normalizes leading slashes; author the entry directly.
rm -f "$OUT_DIR/absolute-path.zip"
python3 - "$OUT_DIR/absolute-path.zip" <<'PY'
import sys, zipfile
out = sys.argv[1]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
    info = zipfile.ZipInfo("/tmp/evil.txt")
    info.external_attr = (0o644 & 0xFFFF) << 16
    info.date_time = (1980, 1, 1, 0, 0, 0)
    zf.writestr(info, b"absolute\n")
PY

# ---------- external-symlink.tar.gz --------------------------------------------
# tar preserves symlink targets verbatim; we just stage a symlink that
# escapes the staging root and pack it.
EXT_DIR="$STAGING/external"
mkdir -p "$EXT_DIR"
ln -s ../../outside "$EXT_DIR/escape-link"
rm -f "$OUT_DIR/external-symlink.tar.gz"
(
    cd "$EXT_DIR"
    tar \
        --sort=name \
        --mtime=@0 \
        --owner=0 --group=0 --numeric-owner \
        --format=ustar \
        -czf "$OUT_DIR/external-symlink.tar.gz" \
        escape-link
)

# ---------- corrupt.zip --------------------------------------------------------
# Truncated copy of happy.zip; libarchive read must surface a ReadFailed.
rm -f "$OUT_DIR/corrupt.zip"
head -c 64 "$OUT_DIR/happy.zip" > "$OUT_DIR/corrupt.zip"

echo "Regenerated archive fixtures in: $OUT_DIR"
ls -1 "$OUT_DIR"
