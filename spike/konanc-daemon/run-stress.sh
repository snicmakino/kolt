#!/usr/bin/env bash
# Stress test: konanc daemon — 100+ invocations for state leakage + stage 2 size scaling.
#
# Prerequisites: fixtures from bench-native-ic/gen.sh must exist.
# Usage: ./run-stress.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
FIX_DIR="$REPO_ROOT/spike/bench-native-ic/fixtures"

KOTLIN_VERSION="2.3.20"
KONAN_HOME="$HOME/.konan/kotlin-native-prebuilt-linux-x86_64-${KOTLIN_VERSION}"
KONANC="$KONAN_HOME/bin/konanc"
KONANC_JAR="$KONAN_HOME/konan/lib/kotlin-native-compiler-embeddable.jar"
NATIVE_TARGET="linux_x64"

STRESS_N=100
LINK_SIZES=(10 25 50)
LINK_WARM=2  # warm invocations per link test (1 cold + LINK_WARM warm)

if [[ ! -x "$KONANC" ]]; then
  echo "konanc not found: $KONANC" >&2
  exit 1
fi
if [[ ! -d "$FIX_DIR" ]]; then
  echo "fixtures not found: $FIX_DIR" >&2
  echo "run spike/bench-native-ic/gen.sh first" >&2
  exit 1
fi

# --- Compile the prototype ---
echo "=== Compiling ReflectiveKonanc ===" >&2
javac -cp "$KONANC_JAR" "$HERE/ReflectiveKonanc.java" -d "$HERE/classes"
echo "  done" >&2

# --- Output file ---
OUT="$HERE/results-stress-$(date +%Y-%m-%d).md"
if [[ -e "$OUT" ]]; then
  for i in 2 3 4 5 6 7 8 9; do
    candidate="${OUT%.md}-${i}.md"
    if [[ ! -e "$candidate" ]]; then
      OUT="$candidate"
      break
    fi
  done
fi

# --- Helpers ---

collect_sources() {
  local dir="$1"
  SOURCES=()
  while IFS= read -r -d '' f; do
    SOURCES+=("$f")
  done < <(find "$dir/src" -name '*.kt' -print0 | sort -z)
}

# ============================================================
# Test 1: 100-invocation stage 1 (source → klib) on native-10
# ============================================================

n=10
dir="$FIX_DIR/native-${n}"
name="native-${n}"

{
  echo "# Stress test: konanc daemon — $(date +%Y-%m-%d)"
  echo
  echo "konanc: \`$KONANC\`"
  echo "kotlin: \`$KOTLIN_VERSION\`"
  echo "host: \`$(uname -srm)\`"
  echo "java: \`$(java -version 2>&1 | head -1)\`"
  echo
  echo "## Test 1: ${STRESS_N}-invocation stage 1 (source → klib) on native-${n}"
  echo
  echo "| run | exit | wall (ms) | heap (MB) |"
  echo "|-----|------|-----------|-----------|"
} >"$OUT"

echo "=== Test 1: ${STRESS_N} invocations, native-${n} stage 1 ===" >&2

collect_sources "$dir"
rm -rf "$dir/build"
mkdir -p "$dir/build"

# No -XX:TieredStopAtLevel=1 — we want C2 JIT warmup to observe steady-state.
output=$(java \
  -ea -Xmx4G \
  -Dfile.encoding=UTF-8 \
  "-Dkonan.home=$KONAN_HOME" \
  -cp "$HERE/classes:$KONANC_JAR" \
  ReflectiveKonanc "$STRESS_N" \
  -target "$NATIVE_TARGET" \
  "${SOURCES[@]}" \
  -p library -nopack \
  -o "$dir/build/${name}-klib" \
  2>&1)

# Parse and write results
fail_count=0
while IFS= read -r line; do
  if [[ "$line" =~ ^run\ ([0-9]+):\ ([A-Z_]+)\ ([0-9]+)ms\ ([0-9]+)MB$ ]]; then
    run_num="${BASH_REMATCH[1]}"
    exit_code="${BASH_REMATCH[2]}"
    wall_ms="${BASH_REMATCH[3]}"
    heap_mb="${BASH_REMATCH[4]}"
    echo "| ${run_num} | ${exit_code} | ${wall_ms} | ${heap_mb} |" >>"$OUT"
    if [[ "$exit_code" != "OK" ]]; then
      ((fail_count++))
    fi
  fi
done <<<"$output"

{
  echo
  echo "**Failures: ${fail_count} / ${STRESS_N}**"
  echo
} >>"$OUT"

echo "  Test 1 done. Failures: ${fail_count}" >&2

# ============================================================
# Test 2: Stage 2 size scaling (klib → kexe)
# ============================================================

{
  echo "## Test 2: Stage 2 size scaling (klib → kexe)"
  echo
  echo "| size | mode | run | exit | wall (ms) | heap (MB) |"
  echo "|------|------|-----|------|-----------|-----------|"
} >>"$OUT"

echo "=== Test 2: stage 2 size scaling ===" >&2

for n in "${LINK_SIZES[@]}"; do
  dir="$FIX_DIR/native-${n}"
  if [[ ! -d "$dir" ]]; then
    echo "SKIP native-${n}: fixture not found" >&2
    continue
  fi
  name="native-${n}"
  echo "--- native-${n} ---" >&2

  # Build klib via subprocess first
  collect_sources "$dir"
  rm -rf "$dir/build"
  mkdir -p "$dir/build"
  "$KONANC" -target "$NATIVE_TARGET" "${SOURCES[@]}" -p library -nopack -o "$dir/build/${name}-klib" 2>/dev/null
  echo "  klib built (subprocess)" >&2

  # Cold subprocess link baseline
  start=$(date +%s%N)
  "$KONANC" -target "$NATIVE_TARGET" -p program -e "bench.main" \
    "-Xinclude=$dir/build/${name}-klib" -o "$dir/build/${name}" 2>/dev/null
  end=$(date +%s%N)
  link_sub_ms=$(( (end - start) / 1000000 ))
  echo "  cold subprocess link: ${link_sub_ms}ms" >&2
  echo "| ${n} | cold-subprocess | 1 | OK | ${link_sub_ms} | - |" >>"$OUT"

  # Warm reflective link (1 cold + LINK_WARM warm)
  total=$((1 + LINK_WARM))
  # Rebuild klib cleanly for reflective test
  rm -rf "$dir/build"
  mkdir -p "$dir/build"
  "$KONANC" -target "$NATIVE_TARGET" "${SOURCES[@]}" -p library -nopack -o "$dir/build/${name}-klib" 2>/dev/null

  link_output=$(java \
    -ea -Xmx4G \
    -Dfile.encoding=UTF-8 \
    "-Dkonan.home=$KONAN_HOME" \
    -cp "$HERE/classes:$KONANC_JAR" \
    ReflectiveKonanc "$total" \
    -target "$NATIVE_TARGET" \
    -p program -e "bench.main" \
    "-Xinclude=$dir/build/${name}-klib" \
    -o "$dir/build/${name}" \
    2>&1)

  echo "$link_output" >&2

  while IFS= read -r line; do
    if [[ "$line" =~ ^run\ ([0-9]+):\ ([A-Z_]+)\ ([0-9]+)ms\ ([0-9]+)MB$ ]]; then
      run_num="${BASH_REMATCH[1]}"
      exit_code="${BASH_REMATCH[2]}"
      wall_ms="${BASH_REMATCH[3]}"
      heap_mb="${BASH_REMATCH[4]}"
      if [[ "$run_num" -eq 1 ]]; then
        mode="warm-cold"
      else
        mode="warm-hot"
      fi
      echo "| ${n} | ${mode} | ${run_num} | ${exit_code} | ${wall_ms} | ${heap_mb} |" >>"$OUT"
    fi
  done <<<"$link_output"

  echo "" >&2
done

# Correctness check: run the last built binary
last_dir="$FIX_DIR/native-50"
last_name="native-50"
if [[ -f "$last_dir/build/${last_name}.kexe" ]]; then
  result=$("$last_dir/build/${last_name}.kexe" 2>/dev/null || true)
  {
    echo
    echo "## Correctness"
    echo
    echo "Binary output (native-50): \`${result}\`"
  } >>"$OUT"
fi

echo "" >&2
echo "Results written to $OUT" >&2
