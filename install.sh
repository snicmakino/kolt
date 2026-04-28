#!/bin/sh
# kolt installer for Linux x64.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/snicmakino/kolt/main/install.sh | sh
#   KOLT_VERSION=0.16.0 sh install.sh
#   KOLT_ALLOW_YANKED=1 sh install.sh
#
# POSIX sh, not bash. Avoid bash-isms ([[ ]], local, arrays, pipefail) so
# the script runs unchanged on BusyBox and macOS bash 3.2.
#
# References: ADR 0018 §4, ADR 0028 §1/§5.

set -eu

KOLT_VERSION="${KOLT_VERSION:-}"
KOLT_ALLOW_YANKED="${KOLT_ALLOW_YANKED:-0}"
KOLT_TEST_BASE_URL="${KOLT_TEST_BASE_URL:-}"
KOLT_TEST_YANKED_URL="${KOLT_TEST_YANKED_URL:-}"

DEFAULT_YANKED_URL="https://raw.githubusercontent.com/snicmakino/kolt/main/YANKED"
DEFAULT_RELEASES_API="https://api.github.com/repos/snicmakino/kolt/releases?per_page=100"

platform_detect() {
    os=$(uname -s)
    arch=$(uname -m)
    case "$os/$arch" in
        Linux/x86_64)
            echo "linux-x64"
            ;;
        Darwin/*)
            echo "macOS support is tracked in #82, not yet released" >&2
            exit 1
            ;;
        Linux/*)
            echo "linuxArm64 support is tracked in #83, not yet released" >&2
            exit 1
            ;;
        *)
            echo "unsupported platform: $os/$arch" >&2
            exit 1
            ;;
    esac
}

fetch_yanked_and_validate() {
    url="${KOLT_TEST_YANKED_URL:-$DEFAULT_YANKED_URL}"
    tempfile=$(mktemp)

    if ! curl -fsSL -o "$tempfile" "$url"; then
        rm -f "$tempfile"
        echo "fetch_yanked: failed to fetch $url" >&2
        exit 6
    fi

    if ! err=$(awk -F'\t' '
        {
            if (length($0) == 0) { msg = "blank line not allowed" }
            else if ($0 ~ /^#/) { msg = "comments not allowed" }
            else if ($0 ~ /^[ \t]/ || $0 ~ /[ \t]$/) { msg = "leading or trailing whitespace not allowed" }
            else if (NF != 3) { msg = "expected 3 tab-separated fields, got " NF }
            else if ($1 == "" || $2 == "" || $3 == "") { msg = "empty field" }
            else { next }
            print "YANKED parse error at line " NR ": " msg
            exit 1
        }
    ' "$tempfile"); then
        rm -f "$tempfile"
        echo "$err" >&2
        exit 2
    fi

    echo "$tempfile"
}

is_yanked() {
    echo "TODO: is_yanked" >&2
    exit 1
}

select_version() {
    echo "TODO: select_version" >&2
    exit 1
}

yank_check() {
    echo "TODO: yank_check" >&2
    exit 1
}

download_and_verify() {
    echo "TODO: download_and_verify" >&2
    exit 1
}

extract_and_link() {
    echo "TODO: extract_and_link" >&2
    exit 1
}

print_path_hint() {
    echo "TODO: print_path_hint" >&2
    exit 1
}

main() {
    platform=$(platform_detect)
    yanked_manifest=$(fetch_yanked_and_validate)
    version=$(select_version "$platform" "$yanked_manifest")
    yank_check "$yanked_manifest" "$version"
    tarball=$(download_and_verify "$version" "$platform")
    extract_and_link "$tarball" "$version"
    print_path_hint
    echo "kolt $version installed at ~/.local/bin/kolt"
}

main "$@"
