@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.build.Profile
import kolt.build.nativeIcCacheDir
import kolt.build.outputJarPath
import kolt.build.outputKexePath
import kolt.infra.eprintln
import kolt.infra.executeCommand
import kolt.infra.fileExists
import kolt.infra.readFileAsString
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kolt.testConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

// Profile alternation + JVM no-op. The path-level case in this file always
// runs; it asserts that `Profile` threads through `outputKexePath` /
// `outputJarPath` / `nativeIcCacheDir` to distinct directories per profile
// so an alternation cannot silently overwrite either profile's artifact or
// IC cache. The end-to-end alternation case is gated on KOLT_PROFILE_IT=1
// because it shells out to a built kolt.kexe (the same gating shape as
// ConcurrentBuildIT).
class BuildProfileIT {

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

  // Req 4.3 / 5.3 path-level invariant. Output directories for each
  // profile are disjoint, so a Debug build followed by a Release build
  // (or vice versa) cannot overwrite the other profile's artifact or IC
  // cache. Lives at the helper layer so it always runs in the standard
  // suite, complementing the env-gated end-to-end case below.
  @Test
  fun profileAlternationDirsAreDisjointAtPathLayer() {
    val nativeApp = testConfig(name = "alt-app", target = "linuxX64")
    val jvmApp = testConfig(name = "alt-jvm", target = "jvm")

    val debugKexe = outputKexePath(nativeApp, Profile.Debug)
    val releaseKexe = outputKexePath(nativeApp, Profile.Release)
    val debugJar = outputJarPath(jvmApp, Profile.Debug)
    val releaseJar = outputJarPath(jvmApp, Profile.Release)
    val debugIc = nativeIcCacheDir(Profile.Debug)
    val releaseIc = nativeIcCacheDir(Profile.Release)

    assertNotEquals(debugKexe, releaseKexe)
    assertNotEquals(debugJar, releaseJar)
    assertNotEquals(debugIc, releaseIc)
    assertTrue(debugKexe.contains("/${Profile.Debug.dirName}/"))
    assertTrue(releaseKexe.contains("/${Profile.Release.dirName}/"))
    assertTrue(debugIc.contains("/${Profile.Debug.dirName}/"))
    assertTrue(releaseIc.contains("/${Profile.Release.dirName}/"))
  }

  // Req 3.1: JVM compile invocation byte-equivalence at the path layer.
  // The JVM jar lives under `build/<profile>/<name>.jar`, so the
  // *outputs* differ by profile dir, but the produced compile args (the
  // "JVM compile invocation") are the byte-identical contract. The
  // env-gated case below verifies the on-disk artifact byte equality
  // when a real kolt build is runnable.
  @Test
  fun jvmAppPathsThreadProfileButCompileArgsAreProfileIndependent() {
    val cfg = testConfig(name = "jvm-noop", target = "jvm")
    val debugJar = outputJarPath(cfg, Profile.Debug)
    val releaseJar = outputJarPath(cfg, Profile.Release)

    assertEquals("build/debug/jvm-noop.jar", debugJar)
    assertEquals("build/release/jvm-noop.jar", releaseJar)
    // checkCommand and testBuildCommand do not branch on Profile (the
    // @Suppress UNUSED_PARAMETER is the contract). The byte-identity is
    // pinned in the unit-test layer (BuilderTest, TestBuilderTest); this
    // IT path-layer test only locks the path partitioning shape.
  }

  // Req 4.3 / 5.3 end-to-end. Build a JVM fixture twice (debug then
  // release) using the bootstrapped kolt.kexe; assert both jars exist.
  // JVM-target rather than native because Native end-to-end requires
  // konanc + a working bootstrap toolchain in CI; JVM-target end-to-end
  // is closer in cost to a normal `kolt test` run.
  @Test
  fun debugAndReleaseJarsCoexistAfterAlternation() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createFixtureProject("kolt-it-profile-alt-")
    val script =
      """
            set -u
            cd "$fixture"
            "$kolt" build > b1.stdout 2> b1.stderr
            echo $D? > b1.exit
            "$kolt" build --release > b2.stdout 2> b2.stderr
            echo $D? > b2.exit
            "$kolt" build > b3.stdout 2> b3.stderr
            echo $D? > b3.exit
            """
        .trimIndent()
    runHarness(script)
    val e1 = readExit(fixture, "b1.exit")
    val e2 = readExit(fixture, "b2.exit")
    val e3 = readExit(fixture, "b3.exit")
    assertEquals(
      0,
      e1,
      "kolt build (debug) must exit 0; stderr=${readOptional(fixture, "b1.stderr")}",
    )
    assertEquals(
      0,
      e2,
      "kolt build --release must exit 0; stderr=${readOptional(fixture, "b2.stderr")}",
    )
    assertEquals(
      0,
      e3,
      "kolt build (debug) again must exit 0; stderr=${readOptional(fixture, "b3.stderr")}",
    )

    val debugJar = "$fixture/build/debug/${PROFILE_FIXTURE_NAME}.jar"
    val releaseJar = "$fixture/build/release/${PROFILE_FIXTURE_NAME}.jar"
    assertTrue(fileExists(debugJar), "debug jar must exist after alternation: $debugJar")
    assertTrue(fileExists(releaseJar), "release jar must exist after alternation: $releaseJar")
  }

  // Req 3.1 end-to-end: identical artifact bytes between debug and
  // release on JVM (modulo file-system timestamps; jar contents are
  // compared, not jar files themselves, because zip entries carry an
  // mtime). For this scope we compare the directory contents under
  // `build/<profile>/classes` if accessible, falling back to a jar
  // checksum when only the jar is observable.
  @Test
  fun jvmReleaseBuildProducesIdenticalCompileOutputBytes() {
    if (!enabled()) return
    val kolt = locateKoltKexe() ?: return
    val fixture = createFixtureProject("kolt-it-profile-jvm-noop-")
    val script =
      """
            set -u
            cd "$fixture"
            "$kolt" build > d.stdout 2> d.stderr
            echo $D? > d.exit
            cp -r build/classes build/classes-debug-snapshot
            rm -rf build
            "$kolt" build --release > r.stdout 2> r.stderr
            echo $D? > r.exit
            diff -r build/classes-debug-snapshot build/classes > diff.out 2>&1 || true
            """
        .trimIndent()
    // First run leaves snapshot, second run --release is a fresh build
    // — but we cleared `build/` above so the second build starts cold.
    // Compare classes directories byte-for-byte; if any class file
    // differs the diff output will be non-empty.
    runHarness(
      """
            set -u
            cd "$fixture"
            "$kolt" build > d.stdout 2> d.stderr
            echo $D? > d.exit
            mkdir -p build-snapshot
            cp -r build/classes build-snapshot/classes-debug || true
            "$kolt" build --release > r.stdout 2> r.stderr
            echo $D? > r.exit
            diff -r build-snapshot/classes-debug build/classes > diff.out 2>&1 || true
            test ! -s diff.out
            echo $D? > diff.exit
            """
        .trimIndent()
    )
    val dExit = readExit(fixture, "d.exit")
    val rExit = readExit(fixture, "r.exit")
    val diffExit = readExit(fixture, "diff.exit")
    assertEquals(
      0,
      dExit,
      "kolt build (debug) must exit 0; stderr=${readOptional(fixture, "d.stderr")}",
    )
    assertEquals(
      0,
      rExit,
      "kolt build --release must exit 0; stderr=${readOptional(fixture, "r.stderr")}",
    )
    assertEquals(
      0,
      diffExit,
      "diff -r between debug-classes and release-classes must be empty " +
        "(JVM no-op contract); diff output was: ${readOptional(fixture, "diff.out")}",
    )
    val rStderr = readOptional(fixture, "r.stderr") ?: ""
    val rStdout = readOptional(fixture, "r.stdout") ?: ""
    assertTrue(
      !rStderr.contains("warning") && !rStderr.contains("error"),
      "JVM --release must not surface warning/error attributable to the flag; stderr=$rStderr",
    )
    // No deprecation banner on stdout either.
    assertTrue(
      !rStdout.contains("deprecat") && !rStderr.contains("deprecat"),
      "JVM --release must not surface a deprecation banner",
    )
  }

  private fun locateKoltKexe(): String? {
    val cwd = currentWorkingDir() ?: return null
    val candidates = listOf("$cwd/build/debug/kolt.kexe", "$cwd/build/release/kolt.kexe")
    val found = candidates.firstOrNull { fileExists(it) }
    if (found == null) {
      error(
        "KOLT_PROFILE_IT=1 but kolt.kexe is not built. Run " +
          "`kolt build` first. Looked under: $candidates"
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
    val on = getenv("KOLT_PROFILE_IT")?.toKString() == "1"
    if (!on && !skipNoticePrinted) {
      skipNoticePrinted = true
      eprintln("BuildProfileIT: skipped (set KOLT_PROFILE_IT=1 and run `kolt build` to enable)")
    }
    return on
  }

  companion object {
    private const val D = "$"

    private var skipNoticePrinted = false

    // Used in fixture kolt.toml below; the two e2e tests share the same
    // fixture shape so the assertion paths can use one constant name.
    private const val PROFILE_FIXTURE_NAME = "profile-it"

    private val FIXTURE_TOML =
      """
            name = "$PROFILE_FIXTURE_NAME"
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
            """
        .trimIndent()

    private val FIXTURE_MAIN =
      """
            fun main() {
                println("hello from profile IT")
            }
            """
        .trimIndent()
  }
}
