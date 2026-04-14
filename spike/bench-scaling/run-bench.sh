#!/usr/bin/env bash
# Scaling benchmark harness for issue #96.
#
# Measures `kolt build` wall-clock wall time across
#   (fixture size × mode) ∈ ({1,10,25,50} × {nodaemon, daemon-cold, daemon-warm, gradle})
# using the release kolt binary. N=7 per cell, median/min/max reported;
# daemon-warm and gradle modes discard the first 2 runs as JIT warmup.
#
# Output: raw per-run table appended into results-YYYY-MM-DD.md next to this
# script.  Does not modify kolt source or ADRs.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
FIX_DIR="$HERE/fixtures"
KOLT_BIN="$REPO_ROOT/build/bin/linuxX64/releaseExecutable/kolt.kexe"

SIZES=(1 10 25 50)
N_RUNS=7
WARM_DISCARD=2

OUT="$HERE/results-$(date +%Y-%m-%d).md"

if [[ ! -x "$KOLT_BIN" ]]; then
  echo "release kolt binary not found: $KOLT_BIN" >&2
  echo "build it first: (cd $REPO_ROOT && ./gradlew linkReleaseExecutableLinuxX64)" >&2
  exit 1
fi

# Pattern used to find kolt-compiler-daemon JVM processes (avoid matching
# our own shell invocations that merely mention the daemon in their cmdline).
DAEMON_PATTERN='java.*kolt-compiler-daemon-all\.jar'

kill_daemon() {
  pkill -f "$DAEMON_PATTERN" 2>/dev/null || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if ! pgrep -f "$DAEMON_PATTERN" >/dev/null; then
      return 0
    fi
    sleep 0.2
  done
  pkill -9 -f "$DAEMON_PATTERN" 2>/dev/null || true
  sleep 0.3
}

# Run a command and print elapsed wall seconds (%e) on stdout.
# /usr/bin/time writes its format line to stderr, so we capture stderr only
# by redirecting the child's stdout to /dev/null before merging.
measure() {
  local t
  t=$({ /usr/bin/time -f '%e' "$@" >/dev/null; } 2>&1 | tail -1)
  echo "$t"
}

median_minmax() {
  # args: list of decimal seconds
  local arr=("$@")
  local sorted
  sorted=$(printf '%s\n' "${arr[@]}" | sort -n)
  local n=${#arr[@]}
  local min max med
  min=$(echo "$sorted" | head -1)
  max=$(echo "$sorted" | tail -1)
  med=$(echo "$sorted" | awk -v n="$n" 'NR==int((n+1)/2)')
  echo "$med $min $max"
}

run_cell() {
  local fixture_dir="$1" mode="$2"
  local label="$3"
  local samples=()
  local prime=0
  local total_runs="$N_RUNS"

  case "$mode" in
    nodaemon)
      for i in $(seq 1 "$total_runs"); do
        kill_daemon
        rm -rf "$fixture_dir/build"
        local t; t=$(cd "$fixture_dir" && measure "$KOLT_BIN" build --no-daemon)
        samples+=("$t")
      done
      ;;
    daemon-cold)
      for i in $(seq 1 "$total_runs"); do
        kill_daemon
        rm -rf "$fixture_dir/build" "$HOME/.kolt/daemon"
        local t; t=$(cd "$fixture_dir" && measure "$KOLT_BIN" build)
        samples+=("$t")
      done
      ;;
    daemon-warm)
      prime="$WARM_DISCARD"
      kill_daemon
      rm -rf "$fixture_dir/build" "$HOME/.kolt/daemon"
      for i in $(seq 1 "$((total_runs + prime))"); do
        rm -rf "$fixture_dir/build"
        local t; t=$(cd "$fixture_dir" && measure "$KOLT_BIN" build)
        if (( i > prime )); then
          samples+=("$t")
        fi
      done
      ;;
    gradle)
      prime="$WARM_DISCARD"
      for i in $(seq 1 "$((total_runs + prime))"); do
        local t; t=$(cd "$fixture_dir" && measure ./gradlew clean build -q)
        if (( i > prime )); then
          samples+=("$t")
        fi
      done
      ;;
    *)
      echo "unknown mode $mode" >&2
      return 1
      ;;
  esac

  read -r med mn mx <<<"$(median_minmax "${samples[@]}")"
  local raw
  raw=$(IFS=,; echo "${samples[*]}")
  printf '| %s | %s | %d | %s | %s | %s | %s |\n' \
    "$label" "$mode" "${#samples[@]}" "$med" "$mn" "$mx" "$raw" \
    >>"$OUT"
  echo "  $label $mode → median=${med}s min=${mn}s max=${mx}s (n=${#samples[@]})"
}

{
  echo "# Phase A daemon scaling benchmark — #96"
  echo
  echo "- Date: $(date --iso-8601=seconds)"
  echo "- Host: $(uname -srm)"
  echo "- JDK: $(java -version 2>&1 | head -1)"
  echo "- kolt binary: \`$KOLT_BIN\`"
  echo "- kolt binary mtime: $(date -r "$KOLT_BIN" --iso-8601=seconds)"
  echo "- N per cell: $N_RUNS (warm modes discard first $WARM_DISCARD)"
  echo
  echo "| Fixture | Mode | N | median (s) | min (s) | max (s) | raw |"
  echo "|---|---|---|---|---|---|---|"
} >"$OUT"

for n in "${SIZES[@]}"; do
  fix="$FIX_DIR/jvm-${n}"
  [[ -d "$fix" ]] || { echo "missing fixture $fix — run gen.sh first" >&2; exit 1; }
  echo "==> jvm-${n}"
  run_cell "$fix" nodaemon     "jvm-${n}"
  run_cell "$fix" daemon-cold  "jvm-${n}"
  run_cell "$fix" daemon-warm  "jvm-${n}"
  run_cell "$fix" gradle       "jvm-${n}"
done

echo
echo "results written to $OUT"
