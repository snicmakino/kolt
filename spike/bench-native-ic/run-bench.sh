#!/usr/bin/env bash
# Native IC spike benchmark harness (#160).
#
# Measures `kolt build` wall-clock time across
#   (fixture size x mode) in ({1,10,25,50} x {cold, noop, touch, abi-neutral})
#
# Modes:
#   cold        — rm -rf build/ before each run (full recompile baseline)
#   noop        — build/ preserved, no source change (cache hit floor)
#   touch       — build/ preserved, touch a mid-graph source file (1-file edit)
#   abi-neutral — build/ preserved, append/remove a comment in a mid-graph file
#
# N=5 per cell (native compiles are slow), median/min/max reported.
# Output: results-YYYY-MM-DD.md next to this script.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
FIX_DIR="$HERE/fixtures"
KOLT_BIN="$REPO_ROOT/build/bin/linuxX64/releaseExecutable/kolt.kexe"

SIZES=(1 10 25 50)
MODES=(cold noop touch abi-neutral)
N_RUNS=5

# Pick an output path that does not clobber a previous run on the same day.
base="$HERE/results-$(date +%Y-%m-%d).md"
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

if [[ ! -x "$KOLT_BIN" ]]; then
  echo "release kolt binary not found: $KOLT_BIN" >&2
  echo "build it first: (cd $REPO_ROOT && ./gradlew linkReleaseExecutableLinuxX64)" >&2
  exit 1
fi

# Mid-graph source file to touch/edit for incremental scenarios.
designated_touch_file() {
  local n="$1"
  if (( n == 1 )); then
    echo "src/Main.kt"
  else
    echo "src/File$((n / 2)).kt"
  fi
}

# Append a comment to file (for abi-neutral mode).
inject_comment() {
  local file="$1"
  echo "// abi-neutral-marker $(date +%s%N)" >>"$file"
}

# Remove injected comment (restore file for next run).
remove_comment() {
  local file="$1"
  sed -i '/^\/\/ abi-neutral-marker /d' "$file"
}

# Monotonic bump counter to defeat WSL2 1s mtime granularity.
# Each call produces a unique epoch so consecutive touches within the same
# wall-clock second still register as distinct mtimes.
BUMP_BASE=$(date +%s)
BUMP_COUNTER=0

touch_with_bump() {
  local file="$1"
  BUMP_COUNTER=$((BUMP_COUNTER + 1))
  touch -d "@$((BUMP_BASE + BUMP_COUNTER))" "$file"
}

# Run kolt build and return wall time in milliseconds.
# Fails loudly if kolt exits non-zero.
time_kolt_build() {
  local dir="$1"
  local start end rc
  start=$(date +%s%N)
  rc=0
  (cd "$dir" && "$KOLT_BIN" build >/dev/null 2>&1) || rc=$?
  end=$(date +%s%N)
  if [[ "$rc" -ne 0 ]]; then
    echo "FAIL: kolt build exited $rc in $dir" >&2
    exit 1
  fi
  echo $(( (end - start) / 1000000 ))
}

# Ensure a warm build exists (for noop/touch/abi-neutral modes).
ensure_warm_build() {
  local dir="$1"
  (cd "$dir" && rm -rf build/ && "$KOLT_BIN" build >/dev/null 2>&1) || {
    echo "FAIL: warm-up build failed in $dir" >&2
    exit 1
  }
}

# Compute median, min, max from a space-separated list of numbers.
stats() {
  local nums=($@)
  local sorted
  sorted=($(printf '%s\n' "${nums[@]}" | sort -n))
  local count=${#sorted[@]}
  local mid=$((count / 2))
  local median=${sorted[$mid]}
  local min=${sorted[0]}
  local max=${sorted[$((count - 1))]}
  echo "$median $min $max"
}

# --- Main ---

{
  echo "# Native IC spike — $(date +%Y-%m-%d)"
  echo
  echo "kolt: \`$KOLT_BIN\` ($(stat -c '%y' "$KOLT_BIN" | cut -d. -f1))"
  echo "kotlin: \`$(head -5 "$FIX_DIR/native-1/kolt.toml" | grep kotlin | cut -d'"' -f2)\`"
  echo "host: \`$(uname -srm)\`"
  echo "runs per cell: $N_RUNS"
  echo
  echo "| size | mode | median (ms) | min (ms) | max (ms) | raw (ms) |"
  echo "|------|------|-------------|----------|----------|----------|"
} >"$OUT"

for n in "${SIZES[@]}"; do
  dir="$FIX_DIR/native-${n}"
  if [[ ! -d "$dir" ]]; then
    echo "SKIP native-${n}: fixture not found" >&2
    continue
  fi

  touch_file="$dir/$(designated_touch_file "$n")"

  for mode in "${MODES[@]}"; do
    times=()
    echo "--- native-${n} / ${mode} ---" >&2

    for ((r = 1; r <= N_RUNS; r++)); do
      case "$mode" in
        cold)
          (cd "$dir" && rm -rf build/)
          ;;
        noop)
          if [[ "$r" -eq 1 ]]; then
            ensure_warm_build "$dir"
          fi
          # no source change
          ;;
        touch)
          if [[ "$r" -eq 1 ]]; then
            ensure_warm_build "$dir"
          fi
          touch_with_bump "$touch_file"
          ;;
        abi-neutral)
          if [[ "$r" -eq 1 ]]; then
            ensure_warm_build "$dir"
          fi
          inject_comment "$touch_file"
          ;;
      esac

      ms=$(time_kolt_build "$dir")
      times+=("$ms")
      echo "  run $r: ${ms}ms" >&2

      # Clean up abi-neutral injection after timing.
      if [[ "$mode" == "abi-neutral" ]]; then
        remove_comment "$touch_file"
      fi
    done

    read -r median min max <<<"$(stats "${times[@]}")"
    raw=$(IFS=,; echo "${times[*]}")
    echo "| ${n} | ${mode} | ${median} | ${min} | ${max} | ${raw} |" >>"$OUT"
  done
done

echo "" >>"$OUT"
echo "Results written to $OUT" >&2
