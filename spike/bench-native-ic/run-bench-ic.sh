#!/usr/bin/env bash
# Native IC candidate benchmark harness (#160).
#
# Measures konanc directly (bypassing kolt) to test IC/cache flags.
# Baseline results from run-bench.sh (kolt build) are the control group.
#
# Candidates:
#   ic             — -Xenable-incremental-compilation -Xic-cache-dir
#   lib-cache      — -Xcache-directory (pre-built stdlib/dep cache)
#   lib-cache-auto — -Xauto-cache-from + -Xauto-cache-dir
#   ic+lib-cache   — ic + lib-cache combined
#
# Edit modes per candidate: cold, touch, abi-neutral (noop skipped — BuildCache
# handles that at 11ms, not a konanc concern).
#
# IC modes run sequentially within one fixture: cold populates the cache,
# then touch/abi-neutral measure incremental rebuilds against that cache.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
FIX_DIR="$HERE/fixtures"

SIZES=(1 10 25 50)
CANDIDATES=(ic lib-cache lib-cache-auto ic+lib-cache)
EDIT_MODES=(cold touch abi-neutral)
N_RUNS=5

KOTLIN_VERSION="2.3.20"
KONANC="$HOME/.konan/kotlin-native-prebuilt-linux-x86_64-${KOTLIN_VERSION}/bin/konanc"
NATIVE_TARGET="linux_x64"

# Output file.
base="$HERE/results-ic-$(date +%Y-%m-%d).md"
if [[ ! -e "$base" ]]; then
  OUT="$base"
else
  for i in 2 3 4 5 6 7 8 9; do
    candidate="${base%.md}-${i}.md"
    if [[ ! -e "$candidate" ]]; then
      OUT="$candidate"
      break
    fi
  done
  : "${OUT:?too many same-day runs, clean up manually}"
fi

if [[ ! -x "$KONANC" ]]; then
  echo "konanc not found: $KONANC" >&2
  exit 1
fi

DIST_CACHE="$HOME/.konan/kotlin-native-prebuilt-linux-x86_64-${KOTLIN_VERSION}/klib/cache"
if [[ ! -d "$DIST_CACHE" ]]; then
  echo "distribution stdlib cache not found: $DIST_CACHE" >&2
  exit 1
fi

# --- Helpers ---

BUMP_BASE=$(date +%s)
BUMP_COUNTER=0

touch_with_bump() {
  local file="$1"
  BUMP_COUNTER=$((BUMP_COUNTER + 1))
  touch -d "@$((BUMP_BASE + BUMP_COUNTER))" "$file"
}

inject_comment() {
  local file="$1"
  echo "// abi-neutral-marker $(date +%s%N)" >>"$file"
}

remove_comment() {
  local file="$1"
  sed -i '/^\/\/ abi-neutral-marker /d' "$file"
}

designated_touch_file() {
  local n="$1"
  if (( n == 1 )); then echo "src/Main.kt"; else echo "src/File$((n / 2)).kt"; fi
}

stats() {
  local nums=($@)
  local sorted
  sorted=($(printf '%s\n' "${nums[@]}" | sort -n))
  local count=${#sorted[@]}
  local mid=$((count / 2))
  echo "${sorted[$mid]} ${sorted[0]} ${sorted[$((count - 1))]}"
}

# --- konanc two-stage build (mirrors kolt's Builder.kt) ---

# Stage 1: compile sources → klib
konanc_library() {
  local dir="$1"; shift
  local name="$1"; shift
  # remaining args are extra flags
  local extra=("$@")
  local sources
  sources=($(find "$dir/src" -name '*.kt' | sort))
  "$KONANC" -target "$NATIVE_TARGET" \
    "${sources[@]}" \
    -p library -nopack \
    -o "$dir/build/${name}-klib" \
    "${extra[@]}" \
    2>&1
}

# Stage 2: link klib → kexe
konanc_link() {
  local dir="$1"; shift
  local name="$1"; shift
  local extra=("$@")
  "$KONANC" -target "$NATIVE_TARGET" \
    -p program \
    -e "bench.main" \
    "-Xinclude=$dir/build/${name}-klib" \
    -o "$dir/build/${name}" \
    "${extra[@]}" \
    2>&1
}

# Full two-stage build with candidate-specific flags.
# Returns wall time in ms via stdout. Build output goes to stderr.
do_build() {
  local dir="$1" name="$2" candidate="$3"
  local ic_cache="$dir/build/.ic-cache"
  local lib_cache="$dir/build/.lib-cache"
  local stage1_extra=() stage2_extra=()

  mkdir -p "$ic_cache" "$lib_cache"

  case "$candidate" in
    ic)
      stage1_extra=()
      stage2_extra=(-Xenable-incremental-compilation "-Xic-cache-dir=$ic_cache")
      ;;
    lib-cache)
      # Point to the distribution's pre-built stdlib cache.
      stage2_extra=("-Xcache-directory=$DIST_CACHE")
      ;;
    lib-cache-auto)
      stage2_extra=("-Xauto-cache-dir=$lib_cache" "-Xauto-cache-from=$HOME/.konan/kotlin-native-prebuilt-linux-x86_64-${KOTLIN_VERSION}/klib")
      ;;
    ic+lib-cache)
      stage2_extra=(-Xenable-incremental-compilation "-Xic-cache-dir=$ic_cache" "-Xcache-directory=$DIST_CACHE")
      ;;
  esac

  local start end rc
  start=$(date +%s%N)
  rc=0
  {
    konanc_library "$dir" "$name" "${stage1_extra[@]}" >&2 && \
    konanc_link "$dir" "$name" "${stage2_extra[@]}" >&2
  } || rc=$?
  end=$(date +%s%N)

  if [[ "$rc" -ne 0 ]]; then
    echo "FAIL: build exited $rc in $dir (candidate=$candidate)" >&2
    exit 1
  fi
  echo $(( (end - start) / 1000000 ))
}

# --- Main ---

{
  echo "# Native IC candidate measurement — $(date +%Y-%m-%d)"
  echo
  echo "konanc: \`$KONANC\`"
  echo "kotlin: \`$KOTLIN_VERSION\`"
  echo "host: \`$(uname -srm)\`"
  echo "runs per cell: $N_RUNS"
  echo
  echo "## Results"
  echo
  echo "| size | candidate | edit | median (ms) | min (ms) | max (ms) | raw (ms) |"
  echo "|------|-----------|------|-------------|----------|----------|----------|"
} >"$OUT"

for n in "${SIZES[@]}"; do
  dir="$FIX_DIR/native-${n}"
  if [[ ! -d "$dir" ]]; then
    echo "SKIP native-${n}: fixture not found" >&2
    continue
  fi

  # Read project name from kolt.toml.
  name="native-${n}"
  touch_file="$dir/$(designated_touch_file "$n")"

  for candidate in "${CANDIDATES[@]}"; do
    echo "=== native-${n} / ${candidate} ===" >&2

    for edit in "${EDIT_MODES[@]}"; do
      times=()
      echo "--- ${edit} ---" >&2

      for ((r = 1; r <= N_RUNS; r++)); do
        case "$edit" in
          cold)
            (cd "$dir" && rm -rf build/)
            ;;
          touch)
            if [[ "$r" -eq 1 ]]; then
              (cd "$dir" && rm -rf build/)
              ms_warmup=$(do_build "$dir" "$name" "$candidate")
              echo "  warmup: ${ms_warmup}ms" >&2
            fi
            touch_with_bump "$touch_file"
            ;;
          abi-neutral)
            if [[ "$r" -eq 1 ]]; then
              (cd "$dir" && rm -rf build/)
              ms_warmup=$(do_build "$dir" "$name" "$candidate")
              echo "  warmup: ${ms_warmup}ms" >&2
            fi
            inject_comment "$touch_file"
            ;;
        esac

        ms=$(do_build "$dir" "$name" "$candidate")
        times+=("$ms")
        echo "  run $r: ${ms}ms" >&2

        if [[ "$edit" == "abi-neutral" ]]; then
          remove_comment "$touch_file"
        fi
      done

      read -r median min max <<<"$(stats "${times[@]}")"
      raw=$(IFS=,; echo "${times[*]}")
      echo "| ${n} | ${candidate} | ${edit} | ${median} | ${min} | ${max} | ${raw} |" >>"$OUT"
    done
  done

  # Record cache sizes.
  echo "" >&2
  echo "Cache sizes for native-${n}:" >&2
  du -sh "$dir/build/.ic-cache" 2>/dev/null >&2 || echo "  .ic-cache: n/a" >&2
  du -sh "$dir/build/.lib-cache" 2>/dev/null >&2 || echo "  .lib-cache: n/a" >&2
  {
    echo ""
    echo "Cache sizes (native-${n}): ic-cache=$(du -sh "$dir/build/.ic-cache" 2>/dev/null | cut -f1 || echo n/a), lib-cache=$(du -sh "$dir/build/.lib-cache" 2>/dev/null | cut -f1 || echo n/a)"
  } >>"$OUT"
done

echo "" >>"$OUT"
echo "Results written to $OUT" >&2
