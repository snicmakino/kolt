#!/usr/bin/env bash
# Cold-path test compile bench: cross-scope shrunk-snapshot cache on vs off.
#
# Measures the "kolt build → daemon stop → kolt test" cold-path test compile
# wall-time on `kolt-jvm-compiler-daemon` (the issue's representative project)
# under two conditions:
#
#   arm A (cache-off): build main, then wipe `<v>/shrunk-snapshots/`, then
#     stop the daemon, then `kolt test`. The fresh daemon cannot find a
#     prior shrunk snapshot for test's classpath, so BTA recomputes from
#     scratch — this matches pre-#380 cold-path behavior.
#
#   arm B (cache-on): build main (populates `<v>/shrunk-snapshots/<key>.bin`
#     for main's classpath), stop the daemon, then `kolt test`. The fresh
#     daemon finds the cache file (cache survives daemon restart per
#     the IcReaper skip-set) and pre-places it at test's `bta/` path,
#     letting BTA's internal incremental shrink optimization fire.
#
# Both arms use synthetic-epoch mtime manipulation rather than plain `touch`
# to avoid WSL2 9p 1s mtime granularity silently invalidating the cache being
# measured (memory: feedback_bench_mtime_granularity).
#
# This script is a sibling of `spike/bench-scaling/run-bench.sh`. It does NOT
# share its modes — those measure end-to-end `kolt build` scaling on synthetic
# fixtures, while this measures `kolt test` cold-path on the real
# `kolt-jvm-compiler-daemon` self-host project so the wall-time number is
# directly comparable to the #376 dogfood baseline (~12s).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$(cd "$(dirname "$0")" && pwd)"
RESULTS_DIR="${HERE}/cold-test-results"
FIXTURE_DIR="${REPO_ROOT}/kolt-jvm-compiler-daemon"

# In-tree kolt + daemon thin jars. Per memory reference_kolt_daemon_jar_env,
# KOLT_DAEMON_JAR / KOLT_NATIVE_DAEMON_JAR point the native binary at the
# branch's freshly-built daemon thin jars (the branch's wire format may be
# ahead of the system kolt).
KOLT_BIN="${REPO_ROOT}/build/debug/kolt.kexe"
DAEMON_JAR="${REPO_ROOT}/kolt-jvm-compiler-daemon/build/debug/kolt-jvm-compiler-daemon.jar"
NATIVE_DAEMON_JAR="${REPO_ROOT}/kolt-native-compiler-daemon/build/debug/kolt-native-compiler-daemon.jar"
export KOLT_DAEMON_JAR="${DAEMON_JAR}"
export KOLT_NATIVE_DAEMON_JAR="${NATIVE_DAEMON_JAR}"

KOTLIN_VERSION="2.3.20"
PROJECT_ID="$(printf '%s' "${FIXTURE_DIR}" | sha256sum | cut -c1-32)"
IC_ROOT="${HOME}/.kolt/daemon/ic/${KOTLIN_VERSION}"
PROJECT_IC="${IC_ROOT}/${PROJECT_ID}"
SHRUNK_DIR="${IC_ROOT}/shrunk-snapshots"

RUNS=5

mkdir -p "${RESULTS_DIR}"

log() { printf '[bench] %s\n' "$*" >&2; }

require_kolt() {
  [ -x "${KOLT_BIN}" ] || { log "kolt binary missing: ${KOLT_BIN} — run 'kolt build' first"; exit 1; }
  [ -f "${DAEMON_JAR}" ] || { log "daemon jar missing: ${DAEMON_JAR} — run 'cd kolt-jvm-compiler-daemon && kolt build' first"; exit 1; }
  "${KOLT_BIN}" --version >&2
  log "KOLT_DAEMON_JAR=${DAEMON_JAR}"
}

stop_daemon() {
  "${KOLT_BIN}" daemon stop --all >/dev/null 2>&1 || true
}

wipe_project_ic_and_state() {
  rm -rf "${PROJECT_IC}"
  # kolt's own up-to-date short-circuit reads `.kolt-{state,test-state}.json`
  # in the project's `build/` dir. Without removing those, a wiped IC dir
  # combined with an unchanged source tree makes kolt skip BTA entirely
  # ("up to date 0.0s") and the experiment never runs through the daemon.
  rm -f "${FIXTURE_DIR}/build/.kolt-state.json"
  rm -f "${FIXTURE_DIR}/build/.kolt-test-state.json"
}

wipe_shrunk_cache() {
  rm -rf "${SHRUNK_DIR}"
}

build_main() {
  (cd "${FIXTURE_DIR}" && "${KOLT_BIN}" build) >/dev/null
}

# Run `kolt test` and emit elapsed wall milliseconds on stdout. The log file
# captures stdout+stderr for post-mortem inspection.
run_test() {
  local logfile="$1"
  local start_ns end_ns
  start_ns=$(date +%s%N)
  (cd "${FIXTURE_DIR}" && "${KOLT_BIN}" test) > "${logfile}" 2>&1
  end_ns=$(date +%s%N)
  echo "$(( (end_ns - start_ns) / 1000000 ))"
}

# arm A: cache-off cold path. Each iteration:
#   1) stop daemon, wipe project IC + state
#   2) wipe `<v>/shrunk-snapshots/` (so any earlier iter's main-build cache
#      cannot be reused)
#   3) build main (cache populates in step 3, but step 4 wipes it again)
#   4) wipe `<v>/shrunk-snapshots/` AGAIN — this is the key step that turns
#      the cache off for the test compile
#   5) stop daemon (so the next test invocation spawns a fresh daemon)
#   6) run test, capture wall_ms
run_arm_cache_off() {
  local samples=()
  local i ms
  for i in $(seq 1 ${RUNS}); do
    stop_daemon
    wipe_project_ic_and_state
    wipe_shrunk_cache
    build_main
    wipe_shrunk_cache
    stop_daemon
    ms="$(run_test "${RESULTS_DIR}/arm-off-run${i}.log")"
    samples+=("${ms}")
    log "arm=cache-off run=${i} wall_ms=${ms}"
  done
  printf '%s\n' "${samples[@]}"
}

# arm B: cache-on cold path. Each iteration:
#   1) stop daemon, wipe project IC + state
#   2) wipe `<v>/shrunk-snapshots/` (start each iter from a clean cache so
#      the bench is symmetric with arm A — the cache benefit must come from
#      THIS iter's main build, not a prior iter's leftovers)
#   3) build main (populates `<v>/shrunk-snapshots/<key>.bin` with a
#      classpath that test will hit as either exact-match or prefix-match)
#   4) stop daemon (forces fresh daemon for the test step — proves the
#      cache survives daemon restart, exercising the IcReaper skip-set)
#   5) run test, capture wall_ms
run_arm_cache_on() {
  local samples=()
  local i ms
  for i in $(seq 1 ${RUNS}); do
    stop_daemon
    wipe_project_ic_and_state
    wipe_shrunk_cache
    build_main
    stop_daemon
    ms="$(run_test "${RESULTS_DIR}/arm-on-run${i}.log")"
    samples+=("${ms}")
    log "arm=cache-on run=${i} wall_ms=${ms}"
  done
  printf '%s\n' "${samples[@]}"
}

# Median of N values. For N=5, picks the 3rd-smallest sample. NR==int(n/2)+1
# matches the existing harness.sh convention (zero-based middle index for
# odd N).
median_of() {
  printf '%s\n' "$@" | sort -n | awk -v n=${RUNS} 'NR==int(n/2)+1'
}

# Warm-rebuild measurement (target ≤540 ms BTA wall). Daemon hot, IC warm.
# Touch a single test source with a synthetic epoch mtime that is strictly
# greater than the previous mtime — plain `touch` would land at
# whole-second resolution on WSL2 9p and could collide with the previous
# build's cached sourcesNewestMtime.
measure_warm_rebuild() {
  local target="${FIXTURE_DIR}/src/test/kotlin/kolt/daemon/MainCliArgsTest.kt"
  local logfile="${RESULTS_DIR}/warm-rebuild.log"
  log "=== warm rebuild measurement ==="
  if [ ! -f "${target}" ]; then
    log "FATAL: warm-rebuild target missing: ${target}"
    exit 1
  fi
  # Ensure daemon is hot and IC is warm: stop, wipe, do a fresh build+test
  # cycle so the daemon process is alive and IC state is fully populated.
  stop_daemon
  wipe_project_ic_and_state
  wipe_shrunk_cache
  (cd "${FIXTURE_DIR}" && "${KOLT_BIN}" build) >/dev/null
  (cd "${FIXTURE_DIR}" && "${KOLT_BIN}" test)  >/dev/null 2>&1 || true

  # Bump the test source mtime monotonically into the future (synthetic
  # epoch). Issue an explicit `+2` to clear any same-second collision.
  local bump_base
  bump_base=$(( $(date +%s) + 5 ))
  local samples=()
  local i ms
  for i in $(seq 1 ${RUNS}); do
    touch -d "@$((bump_base + i))" "${target}"
    ms="$(run_test "${logfile}.run${i}")"
    samples+=("${ms}")
    log "warm-rebuild run=${i} wall_ms=${ms}"
  done
  median_of "${samples[@]}"
}

# No-op test measurement (target ≤50 ms BTA wall). Daemon hot, IC warm,
# no source change. The previous warm-rebuild measurement leaves daemon
# and IC in the right state.
measure_noop_test() {
  log "=== no-op test measurement ==="
  local logfile="${RESULTS_DIR}/noop-test.log"
  local samples=()
  local i ms
  for i in $(seq 1 ${RUNS}); do
    ms="$(run_test "${logfile}.run${i}")"
    samples+=("${ms}")
    log "noop-test run=${i} wall_ms=${ms}"
  done
  median_of "${samples[@]}"
}

require_kolt
log "fixture: ${FIXTURE_DIR}"
log "projectId: ${PROJECT_ID}"
log "IC root: ${IC_ROOT}"
log "shrunk cache dir: ${SHRUNK_DIR}"

OUT="${RESULTS_DIR}/cold-test-after-main.md"

{
  echo "# Cold-path test compile bench (issue #380)"
  echo
  echo "- Date: $(date --iso-8601=seconds)"
  echo "- Host: $(uname -srm)"
  echo "- JDK: $(java -version 2>&1 | head -1)"
  echo "- kolt binary: \`${KOLT_BIN}\` ($(${KOLT_BIN} --version | head -1))"
  echo "- KOLT_DAEMON_JAR mtime: $(date -r "${DAEMON_JAR}" --iso-8601=seconds)"
  echo "- Fixture: \`${FIXTURE_DIR}\`"
  echo "- Runs per arm: ${RUNS}"
  echo
  echo "## Cold-path test compile (kolt build → daemon stop → kolt test)"
  echo

  log "=== arm A: cache-off cold path ==="
  ARM_OFF=$(run_arm_cache_off)

  log "=== arm B: cache-on cold path ==="
  ARM_ON=$(run_arm_cache_on)

  echo "### arm A — cache-off"
  echo
  echo "${ARM_OFF}"
  echo

  echo "### arm B — cache-on"
  echo
  echo "${ARM_ON}"
  echo

  MEDIAN_OFF=$(median_of ${ARM_OFF})
  MEDIAN_ON=$(median_of ${ARM_ON})
  DELTA=$(( MEDIAN_OFF - MEDIAN_ON ))
  if [ "${MEDIAN_OFF}" -gt 0 ]; then
    PCT_NUM=$(( DELTA * 1000 / MEDIAN_OFF ))
    PCT_INT=$(( PCT_NUM / 10 ))
    PCT_FRAC=$(( PCT_NUM % 10 ))
    if [ "${PCT_NUM}" -lt 0 ]; then
      PCT_FRAC=$(( -PCT_FRAC ))
    fi
  else
    PCT_NUM=0
    PCT_INT=0
    PCT_FRAC=0
  fi

  echo "### Summary"
  echo
  echo "| metric | value (ms) |"
  echo "|---|---:|"
  echo "| cache-off median | ${MEDIAN_OFF} |"
  echo "| cache-on median  | ${MEDIAN_ON}  |"
  echo "| delta            | ${DELTA}      |"
  echo "| reduction        | ${PCT_INT}.${PCT_FRAC}% |"
  echo

  WARM_MEDIAN=$(measure_warm_rebuild)
  NOOP_MEDIAN=$(measure_noop_test)
  echo "## Warm rebuild and no-op test"
  echo
  echo "| metric | median (ms) | target | pass |"
  echo "|---|---:|---:|:---:|"
  if [ "${WARM_MEDIAN}" -le 540 ]; then WARM_PASS="yes"; else WARM_PASS="no"; fi
  if [ "${NOOP_MEDIAN}" -le 50 ]; then NOOP_PASS="yes"; else NOOP_PASS="no"; fi
  echo "| warm rebuild (target 540 ms BTA wall) | ${WARM_MEDIAN} | 540 | ${WARM_PASS} |"
  echo "| no-op test  (target 50 ms BTA wall)   | ${NOOP_MEDIAN} | 50  | ${NOOP_PASS} |"
} > "${OUT}"

log "results written to ${OUT}"
cat "${OUT}" >&2
