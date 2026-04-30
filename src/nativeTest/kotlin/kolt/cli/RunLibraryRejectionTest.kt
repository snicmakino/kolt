package kolt.cli

import com.github.michaelbull.result.getError
import kolt.infra.fileExists
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kolt.testConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.PATH_MAX
import platform.posix.chdir
import platform.posix.getcwd
import platform.posix.mkdtemp

@OptIn(ExperimentalForeignApi::class)
private fun createTempDir(prefix: String): String {
  val template = "/tmp/${prefix}XXXXXX"
  val buf = template.encodeToByteArray().copyOf(template.length + 1)
  buf.usePinned { pinned ->
    val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
    return result.toKString()
  }
}

/**
 * R4 matrix: `kolt run` must reject library projects before any artifact resolution or build
 * invocation (ADR 0023 §1 kind schema). Coverage:
 * - R4.1 canonical stderr substring + `EXIT_CONFIG_ERROR` exit code.
 * - R4.2 rejection pre-empts the build pipeline / artifact path lookup.
 * - R4.3 rejection is target-agnostic (JVM + native).
 * - R4.2 watch-variant: the guard fires once at entry, not per poll tick.
 */
@OptIn(ExperimentalForeignApi::class)
class RunLibraryRejectionTest {

  // R4.1 + R4.3: the run-guard rejects both JVM and native library
  // configs with `EXIT_CONFIG_ERROR` and emits the canonical stderr
  // substring once per invocation.
  @Test
  fun rejectIfLibraryReturnsConfigErrorWithCanonicalMessageForJvmAndNative() {
    for (target in listOf("jvm", "linuxX64")) {
      val base = testConfig(name = "mylib", target = target)
      val libConfig = base.copy(kind = "lib", build = base.build.copy(main = null))
      val stderr = mutableListOf<String>()

      val exit = rejectIfLibrary(libConfig, eprint = { stderr.add(it) }).getError()

      assertEquals(EXIT_CONFIG_ERROR, exit, "target=$target must exit with EXIT_CONFIG_ERROR")
      assertEquals(1, stderr.size, "target=$target must emit exactly one stderr line")
      assertTrue(
        stderr[0].contains("library projects cannot be run"),
        "target=$target stderr must contain canonical substring, got: ${stderr[0]}",
      )
    }
  }

  // Non-regression guardrail: app configs flow through the guard
  // untouched (R4.4 path), no stderr emitted.
  @Test
  fun rejectIfLibraryPassesThroughAppConfigsWithoutStderr() {
    for (target in listOf("jvm", "linuxX64")) {
      val config = testConfig(name = "myapp", target = target).copy(kind = "app")
      val stderr = mutableListOf<String>()

      val outcome = rejectIfLibrary(config, eprint = { stderr.add(it) })

      assertTrue(outcome.isOk, "target=$target app config must pass the guard")
      assertTrue(stderr.isEmpty(), "target=$target app config must emit no stderr")
    }
  }

  // R4.2: the guard fires before `doRun` looks at any artifact. Pre-
  // guard, a library config would hit the JVM `?: return Err(EXIT_BUILD_ERROR)`
  // or the native `!fileExists(kexePath)` branch — both yield
  // `EXIT_BUILD_ERROR`. The guard upgrades that to `EXIT_CONFIG_ERROR`
  // and short-circuits before any filesystem check.
  @Test
  fun doRunRejectsLibraryWithConfigErrorBeforeArtifactResolution() {
    for (target in listOf("jvm", "linuxX64")) {
      val base = testConfig(name = "mylib", target = target)
      val libConfig = base.copy(kind = "lib", build = base.build.copy(main = null))

      val exit = doRun(libConfig, classpath = null).getError()

      assertEquals(
        EXIT_CONFIG_ERROR,
        exit,
        "target=$target doRun on library must return EXIT_CONFIG_ERROR, not EXIT_BUILD_ERROR",
      )
    }
  }
}

/**
 * R4.2 watch-variant: `watchRunLoop` must surface the rejection once and return cleanly without
 * entering the rebuild-poll loop. Drives the loop via a real `kolt.toml` fixture in a temp
 * directory; a non-entry guard would attempt `setupWatches` and block on `pollEvents`.
 */
@OptIn(ExperimentalForeignApi::class)
class WatchRunLoopLibraryRejectionTest {

  private var originalCwd: String = ""
  private var tmpDir: String = ""

  @BeforeTest
  fun setUp() {
    originalCwd = memScoped {
      val buf = allocArray<ByteVar>(PATH_MAX)
      getcwd(buf, PATH_MAX.toULong())?.toKString() ?: error("getcwd failed")
    }
    tmpDir = createTempDir("kolt-run-lib-reject-")
    check(chdir(tmpDir) == 0) { "chdir to $tmpDir failed" }
  }

  @AfterTest
  fun tearDown() {
    chdir(originalCwd)
    if (tmpDir.isNotEmpty() && fileExists(tmpDir)) {
      removeDirectoryRecursive(tmpDir)
    }
  }

  // R4.2 (watch): rejection fires at loop entry, exactly once, without
  // blocking on inotify setup or the poll loop.
  @Test
  fun watchRunLoopRejectsLibraryOnceAtEntryAndReturns() {
    writeFileAsString(KOLT_TOML, LIB_TOML).getError()?.let { error("seed failed: $it") }

    val stderr = mutableListOf<String>()

    // If the entry guard is present, watchRunLoop must return
    // immediately without installing inotify or spawning a child.
    watchRunLoop(useDaemon = false, appArgs = emptyList(), eprint = { stderr.add(it) })

    val rejections = stderr.count { it.contains("library projects cannot be run") }
    assertEquals(
      1,
      rejections,
      "watch-run must emit the library rejection exactly once; stderr=$stderr",
    )
    assertFalse(
      stderr.any { it.contains("watching for changes") },
      "watch-run must not enter the poll loop for a library; stderr=$stderr",
    )
  }

  companion object {
    private val LIB_TOML =
      """
            name = "mylib"
            version = "0.1.0"
            kind = "lib"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            sources = ["src"]
        """
        .trimIndent()
  }
}
