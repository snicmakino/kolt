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
 * End-to-end integration test for jvm-sys-props (tasks.md 4.1, requirements 3.1, 3.3, 3.4). Spawns
 * the built `kolt.kexe` against a fixture project that declares one `[classpaths.<name>]` bundle
 * and `[test.sys_props]` covering all 3 value shapes (literal / classpath / project_dir), then
 * asserts that the spawned JVM sees the correct resolved values via `System.getProperty`.
 *
 * Gated behind `KOLT_INTEGRATION=1`: the fixture pulls JUnit (auto-injected via test-deps) and
 * `kotlin-stdlib` (the bundle dep) from Maven Central, so the case is offline-unsafe and slow.
 * Without the env var the test returns immediately so the default `kolt test` run stays clean.
 *
 * Shell harness scripts use a Kotlin-side `D = "$"` token for shell variable references so the
 * raw-string templates do not collide with Kotlin's own `$variable` interpolation.
 */
class JvmTestSysPropIT {

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

  // Req 3.1, 3.3, 3.4: kolt test spawns a JVM with `-D<k>=<v>` for every
  // entry in `[test.sys_props]`, where the resolved value follows the
  // 3-form rules — verbatim literal, colon-joined absolute paths for
  // `{ classpath = "<bundle>" }`, project-root-joined absolute path for
  // `{ project_dir = "<rel>" }`. The fixture's JUnit test calls
  // `System.getProperty(...)` for each declared key and asserts the
  // structural shape; we rely on a green fixture-test exit code as the
  // signal that the resolved values reached the JVM correctly.
  @Test
  fun koltTestPropagatesAll3SysPropFormsToTheTestJvm() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createFixtureProject("kolt-it-sysprop-")
    val script =
      """
            set -u
            cd "$fixture"
            "$kolt" test > t.stdout 2> t.stderr
            echo $D? > t.exit
            """
        .trimIndent()
    runHarness(script)
    val exit = readExit(fixture, "t.exit")
    val stdout = readOptional(fixture, "t.stdout") ?: ""
    val stderr = readOptional(fixture, "t.stderr") ?: ""
    assertEquals(0, exit, "kolt test on sysprop fixture must exit 0; stdout=$stdout stderr=$stderr")
    // The JUnit Console Launcher prints a summary that includes a
    // "tests successful" / "Tests run:" style marker; pin a positive
    // success signal so a silent zero-exit (tests skipped) does not
    // pass for the wrong reason.
    val combined = stdout + "\n" + stderr
    assertTrue(
      combined.contains("successful") || combined.contains("Tests run:"),
      "expected JUnit success marker in test output; stdout=$stdout stderr=$stderr",
    )
  }

  // Mirrors ConcurrentBuildIT/BuildProfileIT: the IT case relies on the
  // bootstrap-built `kolt.kexe` under `build/<profile>/kolt.kexe`. When
  // the env gate is on we expect the binary to be present; surface a
  // loud error otherwise so a misconfigured run does not silently pass.
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
    writeFileAsString("$dir/kolt.toml", fixtureToml()).getOrElse { error("write kolt.toml: $it") }
    val srcDir = "$dir/src"
    val testDir = "$dir/test"
    val resourcesDir = "$dir/$RESOURCES_REL_PATH"
    executeCommand(listOf("mkdir", "-p", srcDir)).getOrElse { error("mkdir src: $it") }
    executeCommand(listOf("mkdir", "-p", testDir)).getOrElse { error("mkdir test: $it") }
    // ProjectDir resolves to <projectRoot>/<rel>; declarative — the
    // directory does not need to exist for the resolver to accept it,
    // but creating it makes the assertion clearer (the JVM-side test
    // can sanity-check that the path points at a real directory).
    executeCommand(listOf("mkdir", "-p", resourcesDir)).getOrElse { error("mkdir resources: $it") }
    writeFileAsString("$srcDir/Main.kt", FIXTURE_MAIN).getOrElse { error("write Main.kt: $it") }
    writeFileAsString("$testDir/SysPropTest.kt", fixtureTest()).getOrElse {
      error("write SysPropTest.kt: $it")
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
        "JvmTestSysPropIT: skipped (set KOLT_INTEGRATION=1 and bootstrap kolt.kexe to enable)"
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

    // kotlin-stdlib pinned so the test does not pick up a moving
    // version. The bundle classpath assertion uses the file-name
    // suffix only, so the exact version is not load-bearing past
    // "must be on Maven Central".
    private const val BUNDLE_DEP_VERSION = "2.3.20"

    private const val LITERAL_VALUE = "fixture-literal-value"

    // Project-relative path used by `[test.sys_props].project_dir_prop`.
    // The JVM test asserts the value is absolute and ends with this
    // exact string (so the resolver's "<root>/<rel>" formula is pinned).
    private const val RESOURCES_REL_PATH = "fixtures/resources"

    private fun fixtureToml(): String =
      """
            name = "sysprop-it"
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

            [classpaths.fixture-cp]
            "org.jetbrains.kotlin:kotlin-stdlib" = "$BUNDLE_DEP_VERSION"

            [test.sys_props]
            literal_prop = { literal = "$LITERAL_VALUE" }
            classpath_prop = { classpath = "fixture-cp" }
            project_dir_prop = { project_dir = "$RESOURCES_REL_PATH" }
            """
        .trimIndent()

    private val FIXTURE_MAIN =
      """
            fun main() {
                println("hello from sysprop IT")
            }
            """
        .trimIndent()

    private fun fixtureTest(): String =
      """
            import kotlin.test.Test
            import kotlin.test.assertEquals
            import kotlin.test.assertNotNull
            import kotlin.test.assertTrue

            class SysPropTest {
                @Test
                fun literal_prop_is_passed_through_verbatim() {
                    val v = assertNotNull(System.getProperty("literal_prop"),
                        "literal_prop must be set as a -D sysprop")
                    assertEquals("$LITERAL_VALUE", v,
                        "literal value must be byte-verbatim from kolt.toml")
                }

                @Test
                fun classpath_prop_is_colon_joined_absolute_paths() {
                    val v = assertNotNull(System.getProperty("classpath_prop"),
                        "classpath_prop must be set as a -D sysprop")
                    // colon-joined classpath: at least one absolute path
                    // entry pointing at a kotlin-stdlib jar in the kolt
                    // cache. With a single bundle entry there may be no
                    // ':' (only one jar), so the structural assertion is
                    // "every segment is absolute and at least one segment
                    // points at kotlin-stdlib".
                    val parts = v.split(":")
                    assertTrue(parts.isNotEmpty(), "classpath must have at least one entry: '${'$'}v'")
                    for (p in parts) {
                        assertTrue(p.startsWith("/"),
                            "classpath segment must be absolute: '${'$'}p' in '${'$'}v'")
                        assertTrue(p.endsWith(".jar"),
                            "classpath segment must be a jar: '${'$'}p' in '${'$'}v'")
                    }
                    assertTrue(parts.any { it.contains("kotlin-stdlib") },
                        "classpath must contain kotlin-stdlib jar: '${'$'}v'")
                }

                @Test
                fun project_dir_prop_is_absolute_and_ends_with_relative_path() {
                    val v = assertNotNull(System.getProperty("project_dir_prop"),
                        "project_dir_prop must be set as a -D sysprop")
                    assertTrue(v.startsWith("/"),
                        "project_dir value must be absolute: '${'$'}v'")
                    assertTrue(v.endsWith("$RESOURCES_REL_PATH"),
                        "project_dir value must end with the declared rel path: '${'$'}v'")
                }
            }
            """
        .trimIndent()
  }
}
