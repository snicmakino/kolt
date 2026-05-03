@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.eprintln
import kolt.infra.executeCommand
import kolt.infra.fileExists
import kolt.infra.readFileAsString
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * IT for the JVM `kolt test` up-to-date cache. Two regression guards:
 * - Cache hit short-circuits the whole pipeline (no kotlinc, no JUnit launcher) and prints "<name>
 *   tests are up to date". The earlier #359 contradiction (main-artifact "is up to date" before
 *   "compiling tests...") stays suppressed under `quietUpToDate=true`.
 * - Cache invalidates when `kolt.lock` mtime changes, mirroring the main `BuildState` signal so
 *   `kolt deps` updates do not leave a stale test artifact in place.
 *
 * Gated behind `KOLT_INTEGRATION=1`: the fixture pulls JUnit + kotlin-stdlib from Maven Central.
 * The default `kolt test` run skips this case so it stays offline-safe.
 */
class JvmTestUpToDateNoiseIT {

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

  @Test
  fun warmKoltTestHitsTestBuildCacheAndShortCircuits() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createFixtureProject("kolt-it-uptodate-")
    // First run seeds caches; second run is the warm cache-hit check.
    // Capture both exit codes so a cold-run failure surfaces clearly
    // instead of being masked by the warm-run assertion.
    val script =
      """
            set -u
            cd "$fixture"
            "$kolt" test > t1.stdout 2> t1.stderr
            echo $D? > t1.exit
            "$kolt" test > t2.stdout 2> t2.stderr
            echo $D? > t2.exit
            """
        .trimIndent()
    runHarness(script)
    val exit1 = readExit(fixture, "t1.exit")
    val exit2 = readExit(fixture, "t2.exit")
    val stdout1 = readOptional(fixture, "t1.stdout") ?: ""
    val stderr1 = readOptional(fixture, "t1.stderr") ?: ""
    val stdout2 = readOptional(fixture, "t2.stdout") ?: ""
    val stderr2 = readOptional(fixture, "t2.stderr") ?: ""
    assertEquals(0, exit1, "cold kolt test must exit 0; stdout=$stdout1 stderr=$stderr1")
    assertEquals(0, exit2, "warm kolt test must exit 0; stdout=$stdout2 stderr=$stderr2")
    // Positive signal: the warm run reached the cache-hit short-circuit.
    // Without this, an early exit before the banner could pass the negative
    // assertions for the wrong reason.
    assertTrue(
      stdout2.contains("uptodate-it tests are up to date"),
      "warm run must hit the test-build cache; stdout=$stdout2 stderr=$stderr2",
    )
    // Cache hit must skip kotlinc — full short-circuit.
    assertFalse(
      stdout2.contains("compiling tests..."),
      "warm `kolt test` must skip kotlinc on cache hit; stdout=$stdout2",
    )
    // Cache hit must skip JUnit launcher — full short-circuit.
    assertFalse(
      stdout2.contains("running tests..."),
      "warm `kolt test` must skip JUnit launcher on cache hit; stdout=$stdout2",
    )
    // Regression guard: the main-artifact "<name> is up to date" line is
    // the contradiction reported in #359. The substring `is up to date`
    // would also match the test-cache hit line ("uptodate-it tests are
    // up to date"), so anchor on the exact main-artifact format.
    assertFalse(
      stdout2.contains("uptodate-it is up to date"),
      "warm `kolt test` stdout must not print main-artifact 'is up to date' line (#359); stdout=$stdout2",
    )
    // The cache file must materialise after the cold run — otherwise the
    // warm short-circuit could not have happened.
    assertTrue(
      fileExists("$fixture/build/.kolt-test-state.json"),
      "test-build cache file must exist after cold run; fixture=$fixture",
    )
  }

  @Test
  fun warmKoltTestMissesCacheWhenLockfileChanges() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createFixtureProject("kolt-it-lockfile-")
    // The fixture has no explicit `[dependencies]` but the JVM target
    // auto-injects `kotlin-stdlib` and `kotlin-test-junit5`, so the
    // resolver writes `kolt.lock` on the cold run. Touching it bumps
    // its mtime past the cached state's `lockfile_mtime`, the equality
    // check trips, and the test pipeline must rebuild. A no-deps
    // fixture would take the resolver's "delete kolt.lock" branch
    // mid-warm-run, breaking the assumption — keep the auto-inject
    // shape if this fixture ever gets re-shaped.
    //
    // `sleep 1` defends against WSL2 / 9p 1s mtime granularity: if the
    // cold run happened to finish in the same second as the touch,
    // mtime would not advance and the IT would flake on a fast cache.
    val script =
      """
            set -u
            cd "$fixture"
            "$kolt" test > t1.stdout 2> t1.stderr
            echo $D? > t1.exit
            sleep 1
            touch kolt.lock
            "$kolt" test > t2.stdout 2> t2.stderr
            echo $D? > t2.exit
            """
        .trimIndent()
    runHarness(script)
    val exit1 = readExit(fixture, "t1.exit")
    val exit2 = readExit(fixture, "t2.exit")
    val stdout1 = readOptional(fixture, "t1.stdout") ?: ""
    val stderr1 = readOptional(fixture, "t1.stderr") ?: ""
    val stdout2 = readOptional(fixture, "t2.stdout") ?: ""
    val stderr2 = readOptional(fixture, "t2.stderr") ?: ""
    assertEquals(0, exit1, "cold kolt test must exit 0; stdout=$stdout1 stderr=$stderr1")
    assertEquals(0, exit2, "warm kolt test must exit 0; stdout=$stdout2 stderr=$stderr2")
    // Cache must miss on the warm run — the lockfile mtime differs.
    assertFalse(
      stdout2.contains("uptodate-it tests are up to date"),
      "warm `kolt test` must miss cache after kolt.lock touch; stdout=$stdout2",
    )
    assertTrue(
      stdout2.contains("compiling tests..."),
      "warm `kolt test` must rebuild after kolt.lock touch; stdout=$stdout2 stderr=$stderr2",
    )
  }

  // Mirrors JvmTestSysPropIT/ConcurrentBuildIT/BuildProfileIT: the IT
  // case relies on the bootstrap-built `kolt.kexe` under
  // `build/<profile>/kolt.kexe`. When the env gate is on we expect the
  // binary to be present; surface a loud error otherwise so a
  // misconfigured run does not silently pass.
  private fun locateKoltKexe(): String? {
    val cwd = currentWorkingDir() ?: return null
    val candidates = listOf("$cwd/build/debug/kolt.kexe", "$cwd/build/release/kolt.kexe")
    val found = candidates.firstOrNull { fileExists(it) }
    if (found == null) {
      error(
        "KOLT_INTEGRATION=1 but kolt.kexe is not built. Run `kolt build` first. " +
          "Looked under: $candidates"
      )
    }
    return found
  }

  private fun createFixtureProject(prefix: String): String {
    val dir = createTempDir(prefix)
    writeFileAsString("$dir/kolt.toml", FIXTURE_TOML).getOrElse { error("write kolt.toml: $it") }
    val srcDir = "$dir/src"
    val testDir = "$dir/test"
    executeCommand(listOf("mkdir", "-p", srcDir)).getOrElse { error("mkdir src: $it") }
    executeCommand(listOf("mkdir", "-p", testDir)).getOrElse { error("mkdir test: $it") }
    writeFileAsString("$srcDir/Main.kt", FIXTURE_MAIN).getOrElse { error("write Main.kt: $it") }
    writeFileAsString("$testDir/TrivialTest.kt", FIXTURE_TEST).getOrElse {
      error("write TrivialTest.kt: $it")
    }
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

  private fun enabled(): Boolean {
    val on = getenv("KOLT_INTEGRATION")?.toKString() == "1"
    if (!on && !skipNoticePrinted) {
      skipNoticePrinted = true
      eprintln(
        "JvmTestUpToDateNoiseIT: skipped (set KOLT_INTEGRATION=1 and bootstrap kolt.kexe to enable)"
      )
    }
    return on
  }

  companion object {
    // Single literal "$" token for shell variable references inside the
    // bash heredoc raw strings; mirrors JvmTestSysPropIT.
    private const val D = "$"

    private var skipNoticePrinted = false

    private val FIXTURE_TOML =
      """
            name = "uptodate-it"
            version = "0.0.1"
            kind = "app"

            [kotlin]
            version = "2.3.20"

            [build]
            target = "jvm"
            jvm_target = "21"
            main = "main"
            sources = ["src"]
            test_sources = ["test"]
            """
        .trimIndent()

    private val FIXTURE_MAIN =
      """
            fun main() {
                println("hello from uptodate IT")
            }
            """
        .trimIndent()

    private val FIXTURE_TEST =
      """
            import kotlin.test.Test
            import kotlin.test.assertEquals

            class TrivialTest {
                @Test
                fun trivial() {
                    assertEquals(2, 1 + 1)
                }
            }
            """
        .trimIndent()
  }
}
