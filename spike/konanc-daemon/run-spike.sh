#!/usr/bin/env bash
# Spike #166: konanc daemon feasibility — repeated K2Native invocation.
#
# Measures wall time of K2Native.exec() called N times in a single JVM,
# compared against cold subprocess konanc invocations.
#
# Prerequisites: fixtures from bench-native-ic/gen.sh must exist.
# Usage: ./run-spike.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
FIX_DIR="$REPO_ROOT/spike/bench-native-ic/fixtures"

KOTLIN_VERSION="2.3.20"
KONAN_HOME="$HOME/.konan/kotlin-native-prebuilt-linux-x86_64-${KOTLIN_VERSION}"
KONANC="$KONAN_HOME/bin/konanc"
KONANC_JAR="$KONAN_HOME/konan/lib/kotlin-native-compiler-embeddable.jar"
NATIVE_TARGET="linux_x64"

SIZES=(1 10 25 50)
N_WARM=3          # warm invocations per fixture
N_COLD_RUNS=3     # cold subprocess runs for baseline

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
OUT="$HERE/results-$(date +%Y-%m-%d).md"
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

# Collect source files into the global SOURCES array.
collect_sources() {
  local dir="$1"
  SOURCES=()
  while IFS= read -r -d '' f; do
    SOURCES+=("$f")
  done < <(find "$dir/src" -name '*.kt' -print0 | sort -z)
}

# --- Cold subprocess baseline (stage 1 only) ---

cold_subprocess() {
  local dir="$1" name="$2"
  collect_sources "$dir"
  rm -rf "$dir/build"
  mkdir -p "$dir/build"
  local start end
  start=$(date +%s%N)
  "$KONANC" -target "$NATIVE_TARGET" \
    "${SOURCES[@]}" \
    -p library -nopack \
    -o "$dir/build/${name}-klib" \
    2>/dev/null
  end=$(date +%s%N)
  echo $(( (end - start) / 1000000 ))
}

# --- Warm reflective (stage 1 only, N invocations in one JVM) ---

warm_reflective_library() {
  local dir="$1" name="$2" n_invocations="$3"
  collect_sources "$dir"
  rm -rf "$dir/build"
  mkdir -p "$dir/build"

  java \
    -ea -Xmx3G \
    -XX:TieredStopAtLevel=1 \
    -Dfile.encoding=UTF-8 \
    "-Dkonan.home=$KONAN_HOME" \
    -cp "$HERE/classes:$KONANC_JAR" \
    ReflectiveKonanc "$n_invocations" \
    -target "$NATIVE_TARGET" \
    "${SOURCES[@]}" \
    -p library -nopack \
    -o "$dir/build/${name}-klib" \
    2>&1
}

# --- Warm reflective (stage 2: link klib → kexe) ---

warm_reflective_link() {
  local dir="$1" name="$2" n_invocations="$3"

  java \
    -ea -Xmx3G \
    -XX:TieredStopAtLevel=1 \
    -Dfile.encoding=UTF-8 \
    "-Dkonan.home=$KONAN_HOME" \
    -cp "$HERE/classes:$KONANC_JAR" \
    ReflectiveKonanc "$n_invocations" \
    -target "$NATIVE_TARGET" \
    -p program \
    -e "bench.main" \
    "-Xinclude=$dir/build/${name}-klib" \
    -o "$dir/build/${name}" \
    2>&1
}

# --- Main ---

{
  echo "# Spike #166: konanc daemon feasibility — $(date +%Y-%m-%d)"
  echo
  echo "konanc: \`$KONANC\`"
  echo "kotlin: \`$KOTLIN_VERSION\`"
  echo "host: \`$(uname -srm)\`"
  echo "java: \`$(java -version 2>&1 | head -1)\`"
  echo "warm invocations: $N_WARM"
  echo "cold baseline runs: $N_COLD_RUNS"
  echo
  echo "## Stage 1 (source → klib): cold subprocess vs warm reflective"
  echo
  echo "| size | mode | run | exit | wall (ms) |"
  echo "|------|------|-----|------|-----------|"
} >"$OUT"

for n in "${SIZES[@]}"; do
  dir="$FIX_DIR/native-${n}"
  if [[ ! -d "$dir" ]]; then
    echo "SKIP native-${n}: fixture not found" >&2
    continue
  fi
  name="native-${n}"
  echo "=== native-${n} ===" >&2

  # --- Cold subprocess baseline ---
  echo "--- cold subprocess ---" >&2
  for ((r = 1; r <= N_COLD_RUNS; r++)); do
    ms=$(cold_subprocess "$dir" "$name")
    echo "  cold run $r: ${ms}ms" >&2
    echo "| ${n} | cold-subprocess | ${r} | OK | ${ms} |" >>"$OUT"
  done

  # --- Warm reflective (1 cold + N_WARM warm in one JVM) ---
  echo "--- warm reflective (1 cold + ${N_WARM} warm in one JVM) ---" >&2
  total_invocations=$((1 + N_WARM))

  output=$(warm_reflective_library "$dir" "$name" "$total_invocations")
  echo "$output" >&2

  # Parse output: lines like "run 1: OK 12345ms"
  while IFS= read -r line; do
    if [[ "$line" =~ ^run\ ([0-9]+):\ ([A-Z_]+)\ ([0-9]+)ms(\ [0-9]+MB)?$ ]]; then
      run_num="${BASH_REMATCH[1]}"
      exit_code="${BASH_REMATCH[2]}"
      wall_ms="${BASH_REMATCH[3]}"
      if [[ "$run_num" -eq 1 ]]; then
        mode="warm-cold"
      else
        mode="warm-hot"
      fi
      echo "| ${n} | ${mode} | ${run_num} | ${exit_code} | ${wall_ms} |" >>"$OUT"
    fi
  done <<<"$output"

  echo "" >&2
done

# --- Two-stage build test for native-10 ---
{
  echo
  echo "## Two-stage reflective build (source → klib → kexe): native-10"
  echo
} >>"$OUT"

n=10
dir="$FIX_DIR/native-${n}"
if [[ -d "$dir" ]]; then
  name="native-${n}"
  echo "=== two-stage reflective: native-${n} ===" >&2

  # Stage 1 reflective (2 invocations: 1 cold + 1 warm)
  echo "--- stage 1 (library) ---" >&2
  output_s1=$(warm_reflective_library "$dir" "$name" 2)
  echo "$output_s1" >&2

  # Stage 2 reflective (2 invocations: 1 cold + 1 warm)
  echo "--- stage 2 (link) reflective ---" >&2
  output_s2=$(warm_reflective_link "$dir" "$name" 2)
  echo "$output_s2" >&2

  # Stage 2 subprocess baseline
  echo "--- stage 2 (link) subprocess ---" >&2
  # Rebuild stage 1 cleanly first (subprocess)
  collect_sources "$dir"
  rm -rf "$dir/build"
  mkdir -p "$dir/build"
  "$KONANC" -target "$NATIVE_TARGET" "${SOURCES[@]}" -p library -nopack -o "$dir/build/${name}-klib" 2>/dev/null
  start=$(date +%s%N)
  "$KONANC" -target "$NATIVE_TARGET" -p program -e "bench.main" "-Xinclude=$dir/build/${name}-klib" -o "$dir/build/${name}" 2>/dev/null
  end=$(date +%s%N)
  link_sub_ms=$(( (end - start) / 1000000 ))
  echo "  link subprocess: ${link_sub_ms}ms" >&2

  {
    echo "### Stage 1 (library) reflective"
    echo '```'
    echo "$output_s1"
    echo '```'
    echo
    echo "### Stage 2 (link) reflective"
    echo '```'
    echo "$output_s2"
    echo '```'
    echo
    echo "### Stage 2 (link) subprocess baseline: ${link_sub_ms}ms"
    echo
    if [[ -f "$dir/build/${name}.kexe" ]]; then
      result=$("$dir/build/${name}.kexe" 2>/dev/null || true)
      echo "Binary output: \`${result}\`"
    else
      echo "Binary not produced."
    fi
  } >>"$OUT"
fi

{
  echo
  echo "## Observations"
  echo
  echo "_(fill in after reviewing results)_"
} >>"$OUT"

echo "" >&2
echo "Results written to $OUT" >&2
