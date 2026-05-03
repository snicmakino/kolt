@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.fileExists
import kolt.infra.readFileAsString
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.PATH_MAX
import platform.posix.chdir
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.mkdtemp
import platform.posix.setenv
import platform.posix.unsetenv

// Issue #353: a `kolt add g:a:v` with an unfetchable coordinate must not
// mutate kolt.toml. The pre-fix code wrote the new entry first, then ran
// the fetch — so a typo or 404 left every subsequent dep-touching command
// erroring on the bogus entry.
//
// We force the fetch to fail deterministically by pointing the project at
// a closed local port (ECONNREFUSED is immediate on Linux); HOME is
// redirected to the tempdir so cache writes do not leak into the real
// `~/.kolt/`.
class DoAddAtomicWriteTest {
  private var originalCwd: String = ""
  private var originalHome: String? = null
  private var tmpDir: String = ""

  @BeforeTest
  fun setUp() {
    originalCwd = memScoped {
      val buf = allocArray<ByteVar>(PATH_MAX)
      getcwd(buf, PATH_MAX.toULong())?.toKString() ?: error("getcwd failed")
    }
    originalHome = getenv("HOME")?.toKString()
    tmpDir = createTempDir("kolt-add-atomic-")
    setenv("HOME", tmpDir, 1)
    check(chdir(tmpDir) == 0) { "chdir to $tmpDir failed" }
  }

  @AfterTest
  fun tearDown() {
    chdir(originalCwd)
    val home = originalHome
    if (home != null) setenv("HOME", home, 1) else unsetenv("HOME")
    if (tmpDir.isNotEmpty() && fileExists(tmpDir)) {
      removeDirectoryRecursive(tmpDir)
    }
  }

  @Test
  fun badCoordinateLeavesKoltTomlByteIdentical() {
    val originalToml =
      """
      |name = "atomic-fixture"
      |version = "0.1.0"
      |kind = "lib"
      |
      |[kotlin]
      |version = "2.1.0"
      |
      |[build]
      |target = "linuxX64"
      |sources = ["src"]
      |test_sources = []
      |
      |[repositories]
      |fake = "http://127.0.0.1:1/"
      |"""
        .trimMargin()
    writeFileAsString("kolt.toml", originalToml).getOrElse { error("setup write failed: $it") }

    val exit = doAdd(listOf("com.example:does-not-exist:1.0.0")).getError()

    assertNotNull(exit, "doAdd must fail when the coordinate is unfetchable")
    val onDisk = readFileAsString("kolt.toml").getOrElse { error("readback failed: $it") }
    assertEquals(originalToml, onDisk, "kolt.toml must be byte-identical after a failed kolt add")
  }

  private fun createTempDir(prefix: String): String {
    val template = "/tmp/${prefix}XXXXXX"
    val buf = template.encodeToByteArray().copyOf(template.length + 1)
    buf.usePinned { pinned ->
      val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
      return result.toKString()
    }
  }
}
