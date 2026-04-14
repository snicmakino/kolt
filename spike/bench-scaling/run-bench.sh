#!/usr/bin/env bash
# Scaling benchmark harness for issues #96 and #103.
#
# Measures `kolt build` wall-clock wall time across
#   (fixture size × mode) ∈ ({1,10,25,50} × {nodaemon, daemon-cold,
#     daemon-warm, gradle, daemon-warm-noop, daemon-warm-touch})
# using the release kolt binary. N=10 per cell, median/min/max reported;
# warm-family modes discard the first 5 runs as JIT warmup.
#
# Issue #103's "Scenario F" is implemented as a pair of modes rather than a
# single cell. Splitting it keeps the harness shape uniform (one mode per
# run_cell call, one row per mode in the output table) and lets the noop
# number be reused as a fixed-cost reference by other analyses later.
# The two warm-* scenarios added for #103 frame the incremental-build ceiling:
#   - daemon-warm-noop: warm daemon, build/ preserved, no source change.
#     Measures the fixed-cost floor (binary startup + dep resolve + kolt's
#     coarse BuildCache up-to-date check + exit). No correct incremental
#     scheme can go below this.
#   - daemon-warm-touch: warm daemon, build/ preserved, touch one designated
#     mid-graph file before each measured build. Today kolt has no real IC,
#     so this exercises the full-recompile path with a warm daemon. The gap
#     (touch − noop) is the incremental ceiling: the maximum wall time a
#     Phase B implementation could ever hope to recover.
#
# Output: raw per-run table appended into results-YYYY-MM-DD.md next to this
# script. Does not modify kolt source or ADRs.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
FIX_DIR="$HERE/fixtures"
KOLT_BIN="$REPO_ROOT/build/bin/linuxX64/releaseExecutable/kolt.kexe"

SIZES=(1 10 25 50)
N_RUNS=10
WARM_DISCARD=5

# Pick an output path that does not clobber a previous run on the same day.
# Previous runs survive as results-YYYY-MM-DD.md, results-YYYY-MM-DD-2.md, ...
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

# Pattern used to find kolt-compiler-daemon JVM processes (avoid matching
# our own shell invocations that merely mention the daemon in their cmdline).
DAEMON_PATTERN='java.*kolt-compiler-daemon-all\.jar'

# Relative path (from the fixture root) of the source file to touch in the
# daemon-warm-touch scenario. Picks a mid-graph file so the downstream chain
# of `work${i+1}` references into `work${i}` is exercised — not a leaf.
# See gen.sh: files form a linear chain Main → File2 → ... → FileN.
designated_touch_file() {
  local n="$1"
  if (( n == 1 )); then
    echo "src/Main.kt"
  else
    echo "src/File$((n / 2)).kt"
  fi
}

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
  local fixture_size="$4"
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
    daemon-warm-noop)
      # Fixed-cost floor (#103). Warm daemon, build/ populated once, then
      # N measured builds with no source change. BuildCache sees the state
      # file matches and takes the up-to-date fast path (BuildCommands.kt
      # doBuild: up-to-date return happens before resolveDependencies).
      # We are timing: binary startup + config parse + toolchain resolve +
      # mtime scan + short-circuit exit. resolveDependencies and the daemon
      # round-trip are NOT exercised on this path. This is the absolute
      # lower bound any kolt build invocation can hit today.
      prime="$WARM_DISCARD"
      kill_daemon
      rm -rf "$fixture_dir/build" "$HOME/.kolt/daemon"
      for i in $(seq 1 "$prime"); do
        (cd "$fixture_dir" && "$KOLT_BIN" build >/dev/null 2>&1)
      done
      for i in $(seq 1 "$total_runs"); do
        local t; t=$(cd "$fixture_dir" && measure "$KOLT_BIN" build)
        samples+=("$t")
      done
      ;;
    daemon-warm-touch)
      # Incremental ceiling baseline (#103). Warm daemon, build/ populated,
      # but each iteration touches one designated mid-graph source file
      # before building. Today's kolt has no file-level incremental, so
      # this is a full recompile on the warm daemon path. Phase B's target
      # is to push this number toward daemon-warm-noop.
      prime="$WARM_DISCARD"
      kill_daemon
      rm -rf "$fixture_dir/build" "$HOME/.kolt/daemon"
      local touch_rel
      touch_rel=$(designated_touch_file "$fixture_size")
      if [[ ! -f "$fixture_dir/$touch_rel" ]]; then
        echo "designated touch file missing: $fixture_dir/$touch_rel" >&2
        return 1
      fi
      # Force the touched file's mtime strictly forward each iteration.
      # WSL2 9p (and several other filesystems) expose 1-second mtime
      # granularity, so a plain `touch` run within the same wall-clock
      # second as the previous build's cached sourcesNewestMtime leaves
      # the mtime value unchanged, BuildCache short-circuits to the
      # up-to-date fast path, and the "touch → full recompile" signal
      # we are trying to measure is lost. A monotonically-increasing
      # synthetic epoch sidesteps the clock entirely.
      local bump_counter=0
      local bump_base
      bump_base=$(( $(date +%s) + 10 ))
      for i in $(seq 1 "$prime"); do
        bump_counter=$(( bump_counter + 1 ))
        (cd "$fixture_dir" && touch -d "@$((bump_base + bump_counter))" "$touch_rel")
        (cd "$fixture_dir" && "$KOLT_BIN" build >/dev/null 2>&1)
      done
      for i in $(seq 1 "$total_runs"); do
        bump_counter=$(( bump_counter + 1 ))
        (cd "$fixture_dir" && touch -d "@$((bump_base + bump_counter))" "$touch_rel")
        local t; t=$(cd "$fixture_dir" && measure "$KOLT_BIN" build)
        samples+=("$t")
      done
      ;;
    gradle)
      # Fair comparison with kolt: `rm -rf build` happens OUTSIDE the
      # timed region (kolt's `rm -rf build` does too), and `-x test`
      # skips the test task family so we are comparing compileKotlin
      # + jar assembly only.
      prime="$WARM_DISCARD"
      for i in $(seq 1 "$((total_runs + prime))"); do
        rm -rf "$fixture_dir/build"
        local t; t=$(cd "$fixture_dir" && measure ./gradlew build -x test -q)
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
  run_cell "$fix" nodaemon          "jvm-${n}" "$n"
  run_cell "$fix" daemon-cold       "jvm-${n}" "$n"
  run_cell "$fix" daemon-warm       "jvm-${n}" "$n"
  run_cell "$fix" gradle            "jvm-${n}" "$n"
  run_cell "$fix" daemon-warm-noop  "jvm-${n}" "$n"
  run_cell "$fix" daemon-warm-touch "jvm-${n}" "$n"
done

echo
echo "results written to $OUT"
