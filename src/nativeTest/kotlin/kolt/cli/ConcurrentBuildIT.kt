@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.eprintln
import kolt.infra.executeAndCapture
import kolt.infra.executeCommand
import kolt.infra.fileExists
import kolt.infra.listFiles
import kolt.infra.readFileAsString
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.PATH_MAX
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.mkdtemp

/**
 * Integration test for concurrent-build-safety (tasks.md 4.1, requirements 1.1-1.5, 1.7, 2.1-2.4).
 * Spawns the built `kolt.kexe` against tmp fixture projects to drive the project-local advisory
 * lock and the temp+rename atomic Downloader path end-to-end.
 *
 * The cases are gated behind `KOLT_CONCURRENT_IT=1` because the realistic scenarios depend on
 * `./gradlew linkDebugExecutableLinuxX64` having produced `build/bin/linuxX64/debugExecutable/
 * kolt.kexe` and on Maven Central being reachable for the Downloader scenario. Without the env
 * variable each case returns immediately so `./gradlew linuxX64Test` stays offline-safe and fast;
 * with the env variable the test exercises real `fork+execvp` of the binary and asserts on its exit
 * code, stderr, and the resulting filesystem state.
 *
 * Shell harness scripts use a Kotlin-side `D = "$"` token for shell variable references so the
 * raw-string templates do not collide with Kotlin's own `$variable` interpolation.
 */
class ConcurrentBuildIT {

  private val createdDirs = mutableListOf<String>()

  @AfterTest
  fun cleanup() {
    for (dir in createdDirs) {
      if (fileExists(dir)) {
        removeDirectoryRecursive(dir)
      }
    }
    createdDirs.clear()
  }

  // Req 1.1, 1.2, 1.5, 1.7: two concurrent `kolt build` against the same
  // project must both reach a clean exit; the lock serialises them and
  // neither sees a corrupted `build/`. We do not assert a tight wall-
  // clock budget — flake-prone on a busy CI machine — only that both
  // finish within a generous upper bound and report exit 0.
  @Test
  fun twoConcurrentBuildsBothExitZero() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createFixtureProject("kolt-it-serial-")
    val script =
      """
            set -u
            cd "$fixture"
            (
              "$kolt" build > b1.stdout 2> b1.stderr
              echo $D? > b1.exit
            ) &
            P1=$D!
            sleep 0.05
            (
              "$kolt" build > b2.stdout 2> b2.stderr
              echo $D? > b2.exit
            ) &
            P2=$D!
            wait ${D}P1
            wait ${D}P2
            """
        .trimIndent()
    runHarness(script)
    val exit1 = readExit(fixture, "b1.exit")
    val exit2 = readExit(fixture, "b2.exit")
    val stderr1 = readOptional(fixture, "b1.stderr")
    val stderr2 = readOptional(fixture, "b2.stderr")
    assertEquals(0, exit1, "first kolt build must exit 0; stderr=$stderr1")
    assertEquals(0, exit2, "second kolt build must exit 0; stderr=$stderr2")
  }

  // Req 1.4 + 1.7: when a peer holds the lock past the configured
  // timeout budget the second `kolt build` exits with EXIT_LOCK_TIMEOUT
  // (6) and emits the canonical stderr message. We use shell `flock(1)`
  // as the peer so the test has no dependency on a long-running first
  // kolt process — the lock file path is the same advisory file that
  // `ProjectLock.acquire` opens.
  @Test
  fun lockTimeoutExitCodeAndMessageWhenPeerHoldsLockTooLong() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createFixtureProject("kolt-it-timeout-")
    // `flock(1)` opens the lock file with O_CREAT too, so we just need
    // the parent build/ directory to exist before either party touches
    // the lock path. Doing the mkdir ourselves keeps the pre-spawn state
    // of the file (= absent) deterministic.
    val buildDir = "$fixture/build"
    executeCommand(listOf("mkdir", "-p", buildDir)).getOrElse { error("mkdir build/ failed: $it") }
    val lockPath = "$buildDir/.kolt-build.lock"
    val script =
      """
            set -u
            cd "$fixture"
            # Hold the project lock for ~1.0s in a peer shell, then run
            # `kolt build` with a 200ms acquire budget — the build must
            # observe TimedOut well before the peer releases.
            ( flock -x "$lockPath" sh -c 'sleep 1' ) &
            FPID=$D!
            # Give flock(1) a beat to actually take the lock before we
            # let kolt try. 100ms is long enough for kernel flock(2) to
            # land while still leaving ~800ms of held-state.
            sleep 0.1
            KOLT_LOCK_TIMEOUT_MS=200 "$kolt" build > t.stdout 2> t.stderr
            echo $D? > t.exit
            wait ${D}FPID || true
            """
        .trimIndent()
    runHarness(script)
    val exit = readExit(fixture, "t.exit")
    val stderr = readOptional(fixture, "t.stderr") ?: ""
    assertEquals(EXIT_LOCK_TIMEOUT, exit, "expected EXIT_LOCK_TIMEOUT (6); stderr=$stderr")
    assertTrue(
      stderr.contains("lock acquisition timed out"),
      "stderr must mention timeout; got: $stderr",
    )
  }

  // Req 1.1, 1.2, 1.5: deps install and build against the same project
  // both acquire the same project-local lock and serialise. Both must
  // reach exit 0 — no half-written `kolt.lock` or torn `build/` artefact.
  @Test
  fun depsInstallAndBuildBothExitZeroWhenRunConcurrently() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createFixtureProject("kolt-it-deps-")
    val script =
      """
            set -u
            cd "$fixture"
            (
              "$kolt" deps install > d.stdout 2> d.stderr
              echo $D? > d.exit
            ) &
            P1=$D!
            sleep 0.05
            (
              "$kolt" build > b.stdout 2> b.stderr
              echo $D? > b.exit
            ) &
            P2=$D!
            wait ${D}P1
            wait ${D}P2
            """
        .trimIndent()
    runHarness(script)
    val depsExit = readExit(fixture, "d.exit")
    val buildExit = readExit(fixture, "b.exit")
    val depsStderr = readOptional(fixture, "d.stderr")
    val buildStderr = readOptional(fixture, "b.stderr")
    assertEquals(0, depsExit, "kolt deps install must exit 0; stderr=$depsStderr")
    assertEquals(0, buildExit, "kolt build must exit 0; stderr=$buildStderr")
  }

  // Req 2.1-2.4: two procs racing on the same coordinate end with one
  // valid jar at the final path and zero `*.tmp.<pid>` siblings. A tmp
  // HOME keeps the user's `~/.kolt/cache/` untouched. Both procs hit a
  // fully cold cache — exercising the per-segment `mkdir`-with-EEXIST
  // path (#263) and the Downloader's atomic rename together.
  @Test
  fun parallelDownloadsEndWithSingleValidJarAndNoTempLeftover() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val tmpHome = createTempDir("kolt-it-home-")
    val fixture1 = createFixtureProject("kolt-it-dl-a-")
    val fixture2 = createFixtureProject("kolt-it-dl-b-")
    // Maven Central layout for the KMP -jvm artefact. Note the doubled
    // `kotlin-result/kotlin-result-jvm/` is the KMP module-info parent
    // dir followed by the JVM-target artefact dir; not a typo.
    val coordinateDir =
      "$tmpHome/.kolt/cache/com/michael-bull/kotlin-result/kotlin-result-jvm/$KOTLIN_RESULT_JVM_VERSION"
    val expectedJar = "$coordinateDir/kotlin-result-jvm-$KOTLIN_RESULT_JVM_VERSION.jar"

    val raceScript =
      """
            set -u
            export HOME="$tmpHome"
            (
              cd "$fixture1"
              "$kolt" deps install > a.stdout 2> a.stderr
              echo $D? > a.exit
            ) &
            P1=$D!
            (
              cd "$fixture2"
              "$kolt" deps install > b.stdout 2> b.stderr
              echo $D? > b.exit
            ) &
            P2=$D!
            wait ${D}P1
            wait ${D}P2
            """
        .trimIndent()
    runHarness(raceScript)
    val exit1 = readExit(fixture1, "a.exit")
    val exit2 = readExit(fixture2, "b.exit")
    val stderr1 = readOptional(fixture1, "a.stderr")
    val stderr2 = readOptional(fixture2, "b.stderr")
    assertEquals(0, exit1, "deps install A must exit 0; stderr=$stderr1")
    assertEquals(0, exit2, "deps install B must exit 0; stderr=$stderr2")
    val lsDiagnostic = executeAndCapture("ls -la $coordinateDir 2>&1").getOrElse { "<ls failed>" }
    assertTrue(
      fileExists(expectedJar),
      "expected jar at $expectedJar — directory contents:\n$lsDiagnostic",
    )
    val entries =
      listFiles(coordinateDir).getOrElse { error("listFiles($coordinateDir) failed: $it") }
    val tempLeftovers = entries.filter { it.contains(".tmp.") }
    assertTrue(
      tempLeftovers.isEmpty(),
      "downloader must not leave .tmp.* siblings; saw $tempLeftovers in $entries",
    )
  }

  // The IT cases need the Gradle-produced `kolt.kexe`. The Gradle test
  // task does not depend on `linkDebugExecutableLinuxX64` by default, so
  // we cannot assume the binary is present. When it is missing we
  // surface a clear error rather than silently passing — the env-gate
  // already guarded the "do not run" path; if the user opted in, the
  // binary really should be there.
  private fun locateKoltKexe(): String? {
    val cwd = currentWorkingDir() ?: return null
    val candidates =
      listOf(
        "$cwd/build/bin/linuxX64/debugExecutable/kolt.kexe",
        "$cwd/build/bin/linuxX64/releaseExecutable/kolt.kexe",
      )
    val found = candidates.firstOrNull { fileExists(it) }
    if (found == null) {
      error(
        "KOLT_CONCURRENT_IT=1 but kolt.kexe is not built. Run " +
          "`./gradlew linkDebugExecutableLinuxX64` first. Looked under: $candidates"
      )
    }
    return found
  }

  private fun createFixtureProject(prefix: String): String {
    val dir = createTempDir(prefix)
    writeFileAsString("$dir/kolt.toml", FIXTURE_TOML).getOrElse { error("write kolt.toml: $it") }
    val srcDir = "$dir/src"
    executeCommand(listOf("mkdir", "-p", srcDir)).getOrElse { error("mkdir src: $it") }
    writeFileAsString("$srcDir/Main.kt", FIXTURE_MAIN).getOrElse { error("write Main.kt: $it") }
    return dir
  }

  private fun createTempDir(prefix: String): String {
    val template = "/tmp/${prefix}XXXXXX"
    val buf = template.encodeToByteArray().copyOf(template.length + 1)
    buf.usePinned { pinned ->
      val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
      val path = result.toKString()
      createdDirs.add(path)
      return path
    }
  }

  private fun currentWorkingDir(): String? = memScoped {
    val buf = allocArray<ByteVar>(PATH_MAX)
    getcwd(buf, PATH_MAX.toULong())?.toKString()
  }

  private fun runHarness(script: String) {
    // bash itself returning non-zero would mean the harness crashed —
    // not the kolt build under test. We surface that distinctly so a
    // broken script does not look like a genuine test failure.
    executeCommand(listOf("bash", "-c", script)).getOrElse { err ->
      error("harness bash failed: $err — script was:\n$script")
    }
  }

  private fun readExit(dir: String, name: String): Int {
    val raw =
      readFileAsString("$dir/$name").getOrElse {
        error("missing $dir/$name — harness did not record an exit code")
      }
    return raw.trim().toIntOrNull() ?: error("could not parse exit code from $dir/$name: '$raw'")
  }

  private fun readOptional(dir: String, name: String): String? {
    val path = "$dir/$name"
    if (!fileExists(path)) return null
    return readFileAsString(path).getOrElse {
      return null
    }
  }

  // Without an explicit env opt-in the four scenarios early-return rather
  // than running, so the suite ships clean to default `linuxX64Test`. Print
  // the skip notice once so a developer running tests without the env
  // variable does not assume "4 passed" means concurrent safety was
  // exercised.
  private fun enabled(): Boolean {
    val on = getenv("KOLT_CONCURRENT_IT")?.toKString() == "1"
    if (!on && !skipNoticePrinted) {
      skipNoticePrinted = true
      eprintln(
        "ConcurrentBuildIT: skipped (set KOLT_CONCURRENT_IT=1 and run linkDebugExecutableLinuxX64 to enable)"
      )
    }
    return on
  }

  companion object {
    // Single literal "$" token for use inside the bash heredoc raw
    // strings. Kotlin would otherwise interpret `$P1` / `$!` / `$?` as
    // template lookups against missing properties.
    private const val D = "$"

    private var skipNoticePrinted = false

    // kotlin-result is small (a few KB), already published, and a
    // frequent dependency on Maven Central — a low-risk synthetic
    // coordinate for the Downloader race. The version is pinned so the
    // tmp-cache path stays deterministic.
    private const val KOTLIN_RESULT_JVM_VERSION = "2.3.1"

    private val FIXTURE_TOML =
      """
            name = "concurrent-it"
            version = "0.0.1"
            kind = "app"

            [kotlin]
            version = "2.3.20"

            [build]
            target = "jvm"
            jvm_target = "21"
            main = "main"
            sources = ["src"]
            test_sources = []

            [dependencies]
            "com.michael-bull.kotlin-result:kotlin-result" = "$KOTLIN_RESULT_JVM_VERSION"
            """
        .trimIndent()

    private val FIXTURE_MAIN =
      """
            fun main() {
                println("hello from concurrent IT")
            }
            """
        .trimIndent()
  }
}
