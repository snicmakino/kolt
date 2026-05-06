#!/usr/bin/env bash
# Spec #380 task 1.1 — BTA shrunk-classpath-snapshot.bin probe harness.
#
# Throwaway. Uses kolt-jvm-compiler-daemon as the fixture and the system kolt
# (with daemon) as the BTA driver. Manipulates IC state under
# ~/.kolt/daemon/ic/<v>/<projectId>/{main,test}/bta/ directly to set up
# experimental conditions for Q1/Q2/Q3.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SPIKE_DIR="${REPO_ROOT}/spike/bta-shrunk-portability"
RESULTS_DIR="${SPIKE_DIR}/results"
FIXTURE_DIR="${REPO_ROOT}/kolt-jvm-compiler-daemon"

# Use in-tree kolt + daemon thin jar (system kolt may be too old to read the
# current branch's lockfile format; per memory reference_kolt_daemon_jar_env,
# KOLT_DAEMON_JAR points the native binary at a specific daemon jar).
KOLT_BIN="${REPO_ROOT}/build/debug/kolt.kexe"
DAEMON_JAR="${REPO_ROOT}/kolt-jvm-compiler-daemon/build/debug/kolt-jvm-compiler-daemon.jar"
NATIVE_DAEMON_JAR="${REPO_ROOT}/kolt-native-compiler-daemon/build/debug/kolt-native-compiler-daemon.jar"
export KOLT_DAEMON_JAR="${DAEMON_JAR}"
export KOLT_NATIVE_DAEMON_JAR="${NATIVE_DAEMON_JAR}"

KOTLIN_VERSION="2.3.20"
PROJECT_ID="$(printf '%s' "${FIXTURE_DIR}" | sha256sum | cut -c1-32)"
IC_ROOT="${HOME}/.kolt/daemon/ic/${KOTLIN_VERSION}"
PROJECT_IC="${IC_ROOT}/${PROJECT_ID}"
MAIN_BTA="${PROJECT_IC}/main/bta"
TEST_BTA="${PROJECT_IC}/test/bta"
SHRUNK_NAME="shrunk-classpath-snapshot.bin"

mkdir -p "${RESULTS_DIR}"

log() { printf '[harness] %s\n' "$*" >&2; }

require_kolt() {
  [ -x "${KOLT_BIN}" ] || { log "kolt binary missing: ${KOLT_BIN} — run 'kolt build' first"; exit 1; }
  [ -f "${DAEMON_JAR}" ] || { log "daemon jar missing: ${DAEMON_JAR} — run 'cd kolt-jvm-compiler-daemon && kolt build' first"; exit 1; }
  "${KOLT_BIN}" --version >&2
  log "KOLT_DAEMON_JAR=${DAEMON_JAR}"
}

stop_daemon() {
  "${KOLT_BIN}" daemon stop --all >/dev/null 2>&1 || true
}

wipe_project_ic() {
  rm -rf "${PROJECT_IC}"
  # kolt's own up-to-date short-circuit reads `.kolt-{state,test-state}.json`
  # in the project's `build/` dir. Without removing those, a wiped IC dir
  # combined with an unchanged source tree makes kolt skip BTA entirely
  # ("up to date 0.0s") and the experiment never runs through the daemon.
  rm -f "${FIXTURE_DIR}/build/.kolt-state.json"
  rm -f "${FIXTURE_DIR}/build/.kolt-test-state.json"
}

inode_of() {
  stat -c '%i' "$1" 2>/dev/null || echo "missing"
}

sha_of() {
  if [ -f "$1" ]; then
    sha256sum "$1" | cut -d' ' -f1
  else
    echo "missing"
  fi
}

size_of() {
  stat -c '%s' "$1" 2>/dev/null || echo "missing"
}

build_main() {
  (cd "${FIXTURE_DIR}" && "${KOLT_BIN}" build) >/dev/null
}

run_test() {
  local logfile="$1"
  local start_ns end_ns
  start_ns=$(date +%s%N)
  (cd "${FIXTURE_DIR}" && "${KOLT_BIN}" test) > "${logfile}" 2>&1
  end_ns=$(date +%s%N)
  echo "$(( (end_ns - start_ns) / 1000000 ))"
}

verify_main_bta_present() {
  if [ ! -f "${MAIN_BTA}/${SHRUNK_NAME}" ]; then
    log "FATAL: expected main shrunk file at ${MAIN_BTA}/${SHRUNK_NAME} after build"
    log "(daemon may not be on the per-scope-layout branch — see README)"
    exit 1
  fi
}

require_kolt
log "fixture: ${FIXTURE_DIR}"
log "projectId: ${PROJECT_ID}"
log "IC root: ${IC_ROOT}"

# ---------------------------------------------------------------------------
# Q1: Portability across workingDirs
#
# Hypothesis: shrunk file embeds no path / workingDir state, so a file produced
# in workingDir A can be placed at workingDir B and BTA will accept it.
#
# Method: build main → take main's shrunk file → place at test's bta path →
# run kolt test → expect success and .class output.
# ---------------------------------------------------------------------------

log "=== Q1: portability ==="
{
  echo "# Q1 portability"
  date

  stop_daemon
  wipe_project_ic
  build_main
  verify_main_bta_present

  MAIN_HASH="$(sha_of "${MAIN_BTA}/${SHRUNK_NAME}")"
  MAIN_SIZE="$(size_of "${MAIN_BTA}/${SHRUNK_NAME}")"
  echo "main shrunk: size=${MAIN_SIZE} sha256=${MAIN_HASH}"

  mkdir -p "${TEST_BTA}"
  cp "${MAIN_BTA}/${SHRUNK_NAME}" "${TEST_BTA}/${SHRUNK_NAME}"
  PRE_PLACED_HASH="$(sha_of "${TEST_BTA}/${SHRUNK_NAME}")"
  echo "pre-placed at test/bta: sha256=${PRE_PLACED_HASH}"

  TEST_LOG="${RESULTS_DIR}/q1-test-run.log"
  if TEST_MS="$(run_test "${TEST_LOG}")"; then
    POST_HASH="$(sha_of "${TEST_BTA}/${SHRUNK_NAME}")"
    POST_SIZE="$(size_of "${TEST_BTA}/${SHRUNK_NAME}")"
    echo "test compile after pre-place: SUCCESS wall_ms=${TEST_MS}"
    echo "test shrunk after compile: size=${POST_SIZE} sha256=${POST_HASH}"

    # Sanity: did .class output land?
    CLASS_COUNT="$(find "${FIXTURE_DIR}/build" -name '*.class' 2>/dev/null | wc -l || echo 0)"
    echo "build/**/*.class count: ${CLASS_COUNT}"

    if [ "${CLASS_COUNT}" -gt 0 ]; then
      echo "VERDICT_Q1: PORTABLE_GREEN (BTA accepted pre-placed file, .class produced)"
    else
      echo "VERDICT_Q1: PORTABLE_AMBIGUOUS (compile reported success but no .class found)"
    fi
  else
    echo "test compile after pre-place: FAILED"
    echo "VERDICT_Q1: PORTABLE_RED (BTA rejected pre-placed file)"
  fi
} > "${RESULTS_DIR}/Q1.log"
cat "${RESULTS_DIR}/Q1.log" >&2

# ---------------------------------------------------------------------------
# Q2: Extension-activation wall-time
#
# Compare cold-path test compile wall-time:
#  arm A: clean IC, build main only, then kolt test (no pre-place)
#  arm B: clean IC, build main only, copy main shrunk to test/bta, then kolt test
#
# 5 runs each, take median. >=5% reduction in arm B vs arm A → GO threshold.
# ---------------------------------------------------------------------------

log "=== Q2: extension activation wall-time ==="
RUNS=5

run_arm() {
  local arm="$1"
  local pre_place="$2"   # "yes" or "no"
  local samples=()
  local i ms
  for i in $(seq 1 ${RUNS}); do
    stop_daemon
    wipe_project_ic
    build_main
    verify_main_bta_present
    if [ "${pre_place}" = "yes" ]; then
      mkdir -p "${TEST_BTA}"
      cp "${MAIN_BTA}/${SHRUNK_NAME}" "${TEST_BTA}/${SHRUNK_NAME}"
    fi
    ms="$(run_test "${RESULTS_DIR}/q2-${arm}-run${i}.log")"
    samples+=("${ms}")
    log "Q2 arm=${arm} run=${i} wall_ms=${ms}"
  done
  printf '%s\n' "${samples[@]}"
}

{
  echo "# Q2 extension activation"
  date
  echo "RUNS=${RUNS}"
  echo
  echo "## arm A: cold test (no pre-place)"
  ARM_A=$(run_arm "A" "no")
  echo "${ARM_A}"
  echo
  echo "## arm B: cold test (with main shrunk pre-placed at test/bta/)"
  ARM_B=$(run_arm "B" "yes")
  echo "${ARM_B}"
  echo
  MEDIAN_A="$(printf '%s\n' "${ARM_A}" | sort -n | awk -v n=${RUNS} 'NR==int(n/2)+1')"
  MEDIAN_B="$(printf '%s\n' "${ARM_B}" | sort -n | awk -v n=${RUNS} 'NR==int(n/2)+1')"
  echo "median A (no pre-place): ${MEDIAN_A} ms"
  echo "median B (pre-placed):   ${MEDIAN_B} ms"
  if [ "${MEDIAN_A}" -gt 0 ]; then
    DELTA=$(( MEDIAN_A - MEDIAN_B ))
    PCT_NUM=$(( DELTA * 1000 / MEDIAN_A ))
    PCT_INT=$(( PCT_NUM / 10 ))
    PCT_FRAC=$(( PCT_NUM % 10 ))
    if [ "${PCT_NUM}" -lt 0 ]; then
      PCT_FRAC=$(( -PCT_FRAC ))
    fi
    echo "delta: ${DELTA} ms (${PCT_INT}.${PCT_FRAC}% reduction)"
    if [ "${PCT_NUM}" -ge 50 ]; then
      echo "VERDICT_Q2: GO (>= 5% improvement)"
    elif [ "${PCT_NUM}" -gt 0 ]; then
      echo "VERDICT_Q2: GO_WARNING (0..5% improvement)"
    else
      echo "VERDICT_Q2: NO_GO (no improvement; pre-placement does not benefit BTA)"
    fi
  else
    echo "VERDICT_Q2: ANOMALOUS (median A is 0ms — measurement broken)"
  fi
} > "${RESULTS_DIR}/Q2.log"
cat "${RESULTS_DIR}/Q2.log" >&2

# ---------------------------------------------------------------------------
# Q3: BTA write semantics — in-place vs tmp+rename
#
# Method: produce a shrunk file, capture inode + sha256.
# Touch a source to force a real (non-no-op) BTA invocation.
# Run again, capture inode + sha256. If inode unchanged → in-place write.
# If inode changed → atomic rename (or unlink+create).
# ---------------------------------------------------------------------------

log "=== Q3: write semantics ==="
{
  echo "# Q3 write semantics"
  date

  stop_daemon
  wipe_project_ic
  build_main
  verify_main_bta_present

  PRE_INODE="$(inode_of "${MAIN_BTA}/${SHRUNK_NAME}")"
  PRE_HASH="$(sha_of "${MAIN_BTA}/${SHRUNK_NAME}")"
  echo "after first build: inode=${PRE_INODE} sha=${PRE_HASH}"

  # Trigger a real BTA invocation: touch any main source file
  TOUCH_TARGET="$(find "${FIXTURE_DIR}/src/main/kotlin" -name '*.kt' | head -1)"
  echo "touching: ${TOUCH_TARGET}"
  # Use synthetic epoch (per memory feedback_bench_mtime_granularity) — WSL2 9p
  # 1s mtime granularity makes plain `touch` unreliable for cache invalidation.
  touch -d "@$(date +%s)" "${TOUCH_TARGET}"
  sleep 1
  touch -d "@$(($(date +%s) + 1))" "${TOUCH_TARGET}"

  build_main

  POST_INODE="$(inode_of "${MAIN_BTA}/${SHRUNK_NAME}")"
  POST_HASH="$(sha_of "${MAIN_BTA}/${SHRUNK_NAME}")"
  echo "after second build (post-touch): inode=${POST_INODE} sha=${POST_HASH}"

  if [ "${PRE_INODE}" = "${POST_INODE}" ]; then
    echo "VERDICT_Q3: IN_PLACE_WRITE (inode preserved across compiles)"
    echo "  → hardlink optimization is UNSAFE for cache file (cache contents would be modified by per-scope BTA)"
  else
    echo "VERDICT_Q3: ATOMIC_RENAME_OR_REPLACE (inode changed across compiles)"
    echo "  → hardlink optimization is SAFE (cache file inode preserved, per-scope path gets fresh inode)"
  fi
} > "${RESULTS_DIR}/Q3.log"
cat "${RESULTS_DIR}/Q3.log" >&2

# ---------------------------------------------------------------------------
# Q4: deferred (see README — flag-invariance experiment is too expensive for
# initial gate; risk bounded by BTA source semantics)
# ---------------------------------------------------------------------------

log "=== Q4: deferred (see REPORT.md / README) ==="
{
  echo "# Q4 flag invariance"
  echo "DEFERRED in this spike — see REPORT.md."
  echo "Rationale: ClasspathEntrySnapshot embeds class metadata (classId, classAbiHash,"
  echo "supertypes), not compiler flags. Flag effects (-Xfriend-paths visibility,"
  echo "plugin classes) modify what the compiler emits and how it resolves symbols, but"
  echo "do not modify the metadata of classpath classes themselves. Risk treated as bounded;"
  echo "if the implementation finds anomalies on a per-flag basis, cache key can be"
  echo "extended to include a flag hash without changing the design's overall shape."
} > "${RESULTS_DIR}/Q4.log"

log "harness complete. results at ${RESULTS_DIR}/"
