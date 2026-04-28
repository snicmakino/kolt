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
    iy_manifest="$1"
    iy_version="$2"
    awk -F'\t' -v v="$iy_version" '
        $1 == v { print $2 "\t" $3; found = 1; exit }
        END { if (!found) exit 1 }
    ' "$iy_manifest"
}

select_version() {
    sv_platform="$1"
    sv_manifest="$2"

    if [ -n "$KOLT_VERSION" ]; then
        printf '%s' "${KOLT_VERSION#v}"
        return
    fi

    sv_response=$(mktemp)
    if ! curl -fsSL -o "$sv_response" "$DEFAULT_RELEASES_API"; then
        rm -f "$sv_response"
        echo "select_version: failed to fetch $DEFAULT_RELEASES_API" >&2
        exit 6
    fi

    sv_tags=$(grep -oE '"tag_name"[[:space:]]*:[[:space:]]*"[^"]+"' "$sv_response" \
        | sed -E 's/^"tag_name"[[:space:]]*:[[:space:]]*"([^"]+)"$/\1/')
    rm -f "$sv_response"

    if [ -z "$sv_tags" ]; then
        echo "select_version: no tags found in API response" >&2
        exit 6
    fi

    sv_filtered=$(printf '%s\n' "$sv_tags" | grep -v -- '-' || true)
    sv_sorted=$(printf '%s\n' "$sv_filtered" | sort -V -r)

    for sv_tag in $sv_sorted; do
        sv_version="${sv_tag#v}"
        if ! is_yanked "$sv_manifest" "$sv_version" >/dev/null; then
            printf '%s' "$sv_version"
            return
        fi
    done

    echo "select_version: no non-yanked release found" >&2
    exit 6
}

yank_check() {
    yc_manifest="$1"
    yc_version="$2"
    if result=$(is_yanked "$yc_manifest" "$yc_version"); then
        yc_replacement=$(printf '%s' "$result" | cut -f1)
        yc_reason=$(printf '%s' "$result" | cut -f2)
        if [ "$KOLT_ALLOW_YANKED" = "1" ]; then
            echo "warning: $yc_version is yanked, replacement is $yc_replacement ($yc_reason)" >&2
            return
        fi
        echo "version $yc_version is yanked, replacement is $yc_replacement ($yc_reason)" >&2
        echo "set KOLT_ALLOW_YANKED=1 to install anyway" >&2
        exit 3
    fi
}

download_and_verify() {
    dv_version="$1"
    dv_platform="$2"

    if [ -n "$KOLT_TEST_BASE_URL" ]; then
        dv_base="$KOLT_TEST_BASE_URL"
    else
        dv_base="https://github.com/snicmakino/kolt/releases/download/v${dv_version}"
    fi

    dv_tarball_name="kolt-${dv_version}-${dv_platform}.tar.gz"
    dv_tarball_url="${dv_base}/${dv_tarball_name}"
    dv_sha256_url="${dv_tarball_url}.sha256"

    dv_tempdir=$(mktemp -d)
    dv_tarball_path="${dv_tempdir}/${dv_tarball_name}"
    dv_sha256_path="${dv_tarball_path}.sha256"

    if ! curl -fsSL -o "$dv_tarball_path" "$dv_tarball_url"; then
        rm -rf "$dv_tempdir"
        echo "download_and_verify: failed to fetch $dv_tarball_url" >&2
        echo "(version $dv_version may predate the installer rollout — see #230)" >&2
        exit 4
    fi

    if ! curl -fsSL -o "$dv_sha256_path" "$dv_sha256_url"; then
        rm -rf "$dv_tempdir"
        echo "download_and_verify: failed to fetch $dv_sha256_url" >&2
        exit 4
    fi

    if ! (cd "$dv_tempdir" && sha256sum -c "${dv_tarball_name}.sha256" >/dev/null 2>&1); then
        dv_expected=$(awk '{print $1}' "$dv_sha256_path")
        dv_actual=$(sha256sum "$dv_tarball_path" | awk '{print $1}')
        rm -rf "$dv_tempdir"
        echo "download_and_verify: SHA-256 mismatch for $dv_tarball_name" >&2
        echo "  expected: $dv_expected" >&2
        echo "  actual:   $dv_actual" >&2
        exit 5
    fi

    echo "$dv_tarball_path"
}

extract_and_link() {
    el_tarball="$1"
    el_version="$2"

    el_share_dir="$HOME/.local/share/kolt"
    el_bin_dir="$HOME/.local/bin"
    el_install_dir="$el_share_dir/$el_version"
    el_symlink="$el_bin_dir/kolt"

    mkdir -p "$el_share_dir" "$el_bin_dir"

    if [ -e "$el_symlink" ] && [ ! -L "$el_symlink" ]; then
        echo "extract_and_link: $el_symlink exists and is not a symlink" >&2
        echo "remove this file manually then re-run install.sh" >&2
        exit 8
    fi

    if [ -d "$el_install_dir" ]; then
        rm -rf "$el_install_dir"
    fi

    tar xzf "$el_tarball" -C "$el_share_dir"

    el_extracted="$el_share_dir/kolt-${el_version}-linux-x64"
    mv "$el_extracted" "$el_install_dir"

    el_libexec_abs="$el_install_dir/libexec"
    for el_argfile in "$el_install_dir/libexec/classpath/"*.argfile; do
        if [ -f "$el_argfile" ]; then
            sed -i "s|@KOLT_LIBEXEC@|$el_libexec_abs|g" "$el_argfile"
        fi
    done

    ln -sf "$el_install_dir/bin/kolt" "$el_symlink"
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
