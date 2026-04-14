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
JVM_TARGET="21"

write_kolt_toml() {
  local name="$1"
  cat >"kolt.toml" <<EOF
name = "$name"
version = "0.1.0"
kotlin = "$KOTLIN_VERSION"
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

# Write file index $i of $n to src/File{i}.kt (or Main.kt for i=1).
# File i defines:
#   - data class M$i
#   - fun work$i(prev: M$((i-1))): M$i
#   - three helper functions with enough cross-symbol references
# File 1 additionally defines `fun main()` which chains work2..workN (or
# runs self-contained helpers when n=1).
write_source_file() {
  local i="$1" n="$2"
  local path="src/File${i}.kt"
  [[ "$i" -eq 1 ]] && path="src/Main.kt"
  {
    echo "package bench"
    echo
    echo "data class M${i}(val a: Int, val b: String, val c: List<Int>)"
    echo
    if [[ "$i" -eq 1 ]]; then
      echo "fun seed(): M1 = M1(1, \"seed\", listOf(1, 2, 3))"
    else
      echo "fun work${i}(prev: M$((i-1))): M${i} ="
      echo "    M${i}(prev.a + ${i}, prev.b + \"-${i}\", prev.c + ${i})"
    fi
    echo
    echo "fun helper${i}A(n: Int): Int {"
    echo "    var acc = 0"
    echo "    for (k in 0..n) acc += k * ${i}"
    echo "    return acc"
    echo "}"
    echo
    echo "fun helper${i}B(s: String): String {"
    echo "    val sb = StringBuilder()"
    echo "    for (ch in s) sb.append(ch)"
    echo "    sb.append(\"-${i}\")"
    echo "    return sb.toString()"
    echo "}"
    echo
    echo "fun helper${i}C(xs: List<Int>): Int ="
    echo "    xs.fold(0) { acc, v -> acc + v * ${i} + helper${i}A(v % 4) }"
    echo
    if [[ "$i" -eq 1 ]]; then
      echo "fun main() {"
      echo "    var m: M1 = seed()"
      if [[ "$n" -gt 1 ]]; then
        echo "    val m2 = work2(m)"
        local j=3
        local prev=2
        while [[ "$j" -le "$n" ]]; do
          echo "    val m${j} = work${j}(m${prev})"
          prev="$j"
          j=$((j + 1))
        done
        echo "    val finalA = helper${prev}A(m${prev}.a)"
        echo "    val finalB = helper${prev}B(m${prev}.b)"
        echo "    val finalC = helper${prev}C(m${prev}.c)"
      else
        echo "    val finalA = helper1A(m.a)"
        echo "    val finalB = helper1B(m.b)"
        echo "    val finalC = helper1C(m.c)"
      fi
      echo "    println(\"bench \$finalA \${finalB.length} \$finalC\")"
      echo "}"
    fi
  } >"$path"
}

for n in "${SIZES[@]}"; do
  dir="$FIX_DIR/jvm-${n}"
  rm -rf "$dir"
  mkdir -p "$dir/src"
  (
    cd "$dir"
    write_kolt_toml "jvm-${n}"
    write_gradle_files "jvm-${n}"
    for ((i = 1; i <= n; i++)); do
      write_source_file "$i" "$n"
    done
  )
  echo "generated $dir ($n source files)"
done
