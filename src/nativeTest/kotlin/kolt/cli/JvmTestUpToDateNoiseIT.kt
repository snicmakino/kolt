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
 * Regression guard for #359 — the contradictory "is up to date" + "compiling tests..." pair on warm
 * `kolt test` runs. Spawns the bootstrap `kolt.kexe` against a minimal JVM fixture, runs `kolt
 * test` twice (cold + warm), and asserts that the second-run stdout contains `compiling tests...`
 * but does NOT contain `is up to date` — that line was the user-visible contradiction (#359,
 * dogfood 2026-05-03).
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
  fun warmKoltTestDoesNotPrintUpToDateBeforeCompilingTests() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createFixtureProject("kolt-it-uptodate-")
    // First run seeds caches; second run hits the JVM main-artifact
    // up-to-date branch in doBuildInner (#359 reproducer). Capture both
    // exit codes so a cold-run failure surfaces clearly instead of being
    // masked by the warm-run assertion.
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
    // Positive signal: the warm run actually reached the test compile
    // phase (the `compiling tests...` banner in doTestInner). Without
    // this, an early exit before the banner could pass the negative
    // assertion for the wrong reason.
    assertTrue(
      stdout2.contains("compiling tests..."),
      "warm run must reach the test-compile phase; stdout=$stdout2 stderr=$stderr2",
    )
    // Regression guard: the main-artifact "is up to date" line is the
    // contradiction reported in #359. After the fix, the test-driven
    // call site of doBuildInner suppresses it via quietUpToDate=true.
    assertFalse(
      stdout2.contains("is up to date"),
      "warm `kolt test` stdout must not print 'is up to date' before 'compiling tests...' (#359); stdout=$stdout2",
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
