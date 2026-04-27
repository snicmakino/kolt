#!/usr/bin/env bash
# Generate deterministic JVM fixtures for scaling benchmark (#96).
# Usage: ./gen.sh            # regenerates fixtures/jvm-{1,10,25,50}
#        ./gen.sh 1 10 25 50 # same, explicit sizes
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
FIX_DIR="$HERE/fixtures"

SIZES=("${@:-1 10 25 50}")
# shellcheck disable=SC2206
SIZES=(${SIZES[@]})

KOTLIN_VERSION="2.1.0"
JVM_TARGET="25"

write_kolt_toml() {
  local name="$1"
  cat >"kolt.toml" <<EOF
name = "$name"
version = "0.1.0"

[kotlin]
version = "$KOTLIN_VERSION"

[build]
target = "jvm"
jvm_target = "$JVM_TARGET"
main = "bench.main"
sources = ["src"]
EOF
}

write_gradle_files() {
  local name="$1"
  cat >"settings.gradle.kts" <<EOF
rootProject.name = "$name"
EOF
  cat >"build.gradle.kts" <<EOF
plugins {
    kotlin("jvm") version "$KOTLIN_VERSION"
    application
}

repositories { mavenCentral() }

kotlin {
    jvmToolchain($JVM_TARGET)
}

application {
    mainClass.set("bench.MainKt")
}

sourceSets["main"].kotlin.srcDirs("src")
EOF
  mkdir -p "gradle/wrapper"
  cp "$REPO_ROOT/gradle/wrapper/gradle-wrapper.jar" "gradle/wrapper/"
  cp "$REPO_ROOT/gradle/wrapper/gradle-wrapper.properties" "gradle/wrapper/"
  cp "$REPO_ROOT/gradlew" "./"
  chmod +x "./gradlew"
}

write_util_file() {
  cat >"src/util/Util.kt" <<'EOF'
package bench.util

data class Container<T>(val value: Int, val label: T)

sealed class Op {
    data class Add(val amount: Int) : Op()
    data class Mul(val factor: Int) : Op()
    data class Shift(val bits: Int) : Op()
    data object Noop : Op()
}

inline fun <reified T> describe(container: Container<T>): String =
    "${T::class.simpleName}:${container.value}/${container.label}"

inline fun <reified T> boxed(value: T): Container<T> =
    Container(value.hashCode(), value)

fun applyOp(value: Int, op: Op): Int = when (op) {
    is Op.Add -> value + op.amount
    is Op.Mul -> value * op.factor
    is Op.Shift -> value shl (op.bits and 31)
    Op.Noop -> value
}

fun <T : Comparable<T>> pickLarger(a: T, b: T): T = if (a >= b) a else b
EOF
}

# Write file index $i of $n to src/File{i}.kt (or Main.kt for i=1).
# Each generated file exercises: generics, inline reified, sealed class
# when-exhaustive, lambdas with capture, a private helper, and a
# cross-package import from `bench.util`. This is heavier per file than
# the original bench design (cf. spike/bench-scaling critique #7) so the
# per-file slope reflects real compiler work, not boilerplate short-
# circuits.
write_source_file() {
  local i="$1" n="$2"
  local prev=$((i - 1))
  local path="src/File${i}.kt"
  [[ "$i" -eq 1 ]] && path="src/Main.kt"
  {
    echo "package bench"
    echo
    echo "import bench.util.Container"
    echo "import bench.util.Op"
    echo "import bench.util.applyOp"
    echo "import bench.util.boxed"
    echo "import bench.util.describe"
    echo "import bench.util.pickLarger"
    echo
    echo "data class M${i}(val a: Int, val b: String, val c: List<Int>)"
    echo
    echo "inline fun <reified T> tag${i}(value: T): String ="
    echo "    \"\${T::class.simpleName}:\${value}\""
    echo
    echo "private fun helper${i}(n: Int): Int {"
    echo "    var acc = 0"
    echo "    for (k in 0..n) acc += k * ${i} + (k shl 1)"
    echo "    return acc"
    echo "}"
    echo
    echo "fun fold${i}(xs: List<Int>): Int {"
    echo "    val scale = ${i}"
    echo "    return xs.fold(0) { acc, v -> acc + v * scale + helper${i}(v and 3) }"
    echo "}"
    echo
    echo "fun map${i}(xs: List<Int>): List<String> ="
    echo "    xs.map { it * ${i} }.filter { it >= 0 }.map { tag${i}(it) }"
    echo
    if [[ "$i" -eq 1 ]]; then
      echo "fun seed(): M1 = M1(1, \"seed\", listOf(1, 2, 3, 4, 5))"
      echo
      echo "fun process1(prev: M1): M1 {"
      echo "    val container: Container<String> = boxed(prev.b)"
      echo "    val op: Op = when (prev.a and 3) {"
      echo "        0 -> Op.Add(prev.a + 1)"
      echo "        1 -> Op.Mul(pickLarger(1, prev.a))"
      echo "        2 -> Op.Shift(prev.a and 7)"
      echo "        else -> Op.Noop"
      echo "    }"
      echo "    val applied = applyOp(container.value, op)"
      echo "    val folded = fold1(prev.c)"
      echo "    val mapped = map1(prev.c)"
      echo "    val label = describe(container)"
      echo "    return M1(applied + folded, label + \"|\" + mapped.size, prev.c + folded)"
      echo "}"
    else
      echo "fun work${i}(prev: M${prev}): M${i} {"
      echo "    val container: Container<String> = boxed(prev.b)"
      echo "    val op: Op = when (prev.a and 3) {"
      echo "        0 -> Op.Add(${i})"
      echo "        1 -> Op.Mul(pickLarger(1, ${i}))"
      echo "        2 -> Op.Shift((${i} and 7))"
      echo "        else -> Op.Noop"
      echo "    }"
      echo "    val applied = applyOp(container.value, op)"
      echo "    val folded = fold${i}(prev.c)"
      echo "    val mapped = map${i}(prev.c)"
      echo "    val label = describe(container) + \"-${i}/\" + mapped.size"
      echo "    return M${i}(applied + folded, label, prev.c + folded + ${i})"
      echo "}"
    fi
    echo
    if [[ "$i" -eq 1 ]]; then
      echo "fun main() {"
      echo "    var m: M1 = process1(seed())"
      if [[ "$n" -gt 1 ]]; then
        # Variable ladder is `m, m2, m3, ...`. The M1 result from
        # process1(seed()) lives in `m`; work2 reads from it directly,
        # subsequent calls chain via the previous m${k} variable.
        local j=2
        local prev2=""
        while [[ "$j" -le "$n" ]]; do
          if [[ -z "$prev2" ]]; then
            echo "    val m${j} = work${j}(m)"
          else
            echo "    val m${j} = work${j}(m${prev2})"
          fi
          prev2="$j"
          j=$((j + 1))
        done
        echo "    val finalFold = fold${prev2}(m${prev2}.c)"
        echo "    val finalMap  = map${prev2}(m${prev2}.c).size"
        echo "    println(\"bench \${m${prev2}.a} \${m${prev2}.b.length} \$finalFold \$finalMap\")"
      else
        echo "    val finalFold = fold1(m.c)"
        echo "    val finalMap = map1(m.c).size"
        echo "    println(\"bench \${m.a} \${m.b.length} \$finalFold \$finalMap\")"
      fi
      echo "}"
    fi
  } >"$path"
}

for n in "${SIZES[@]}"; do
  dir="$FIX_DIR/jvm-${n}"
  rm -rf "$dir"
  mkdir -p "$dir/src/util"
  (
    cd "$dir"
    write_kolt_toml "jvm-${n}"
    write_gradle_files "jvm-${n}"
    write_util_file
    for ((i = 1; i <= n; i++)); do
      write_source_file "$i" "$n"
    done
  )
  # Report file count including the shared util file.
  echo "generated $dir ($n source files + bench/util/Util.kt)"
done
