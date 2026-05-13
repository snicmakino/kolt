package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.config.KOLT_LOCAL_TOML
import kolt.config.SysPropValue
import kolt.infra.deleteFile
import kolt.infra.fileExists
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
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
class LoadProjectConfigOverlayTest {
  private var originalCwd: String = ""
  private var tmpDir: String = ""

  @BeforeTest
  fun setUp() {
    originalCwd = memScoped {
      val buf = allocArray<ByteVar>(PATH_MAX)
      getcwd(buf, PATH_MAX.toULong())?.toKString() ?: error("getcwd failed")
    }
    tmpDir = createTempDir("kolt-overlay-load-")
    check(chdir(tmpDir) == 0) { "chdir to $tmpDir failed" }
  }

  @AfterTest
  fun tearDown() {
    chdir(originalCwd)
    if (tmpDir.isNotEmpty() && fileExists(tmpDir)) {
      removeDirectoryRecursive(tmpDir)
    }
  }

  @Test
  fun overlay_present_reflects_in_loaded_config() {
    writeFileAsString(KOLT_TOML, BASE_TOML).getOrElse { error("seed base failed: $it") }
    writeFileAsString(KOLT_LOCAL_TOML, OVERLAY_TOML).getOrElse { error("seed overlay failed: $it") }

    val config = loadProjectConfig().getOrElse { fail("expected Ok, got exit=$it") }

    val entry = config.testSection.sysProps["greeting"]
    val literal = assertIs<SysPropValue.Literal>(entry, "overlay must replace the base entry")
    assertEquals("overlay-value", literal.value)
  }

  @Test
  fun overlay_absent_loads_base_only() {
    writeFileAsString(KOLT_TOML, BASE_TOML).getOrElse { error("seed base failed: $it") }

    val config = loadProjectConfig().getOrElse { fail("expected Ok, got exit=$it") }

    val entry = config.testSection.sysProps["greeting"]
    val literal = assertIs<SysPropValue.Literal>(entry, "base entry must survive without overlay")
    assertEquals("base-value", literal.value)
  }

  @Test
  fun overlay_deleted_reverts_to_base_only() {
    writeFileAsString(KOLT_TOML, BASE_TOML).getOrElse { error("seed base failed: $it") }
    writeFileAsString(KOLT_LOCAL_TOML, OVERLAY_TOML).getOrElse { error("seed overlay failed: $it") }

    val merged = loadProjectConfig().getOrElse { fail("expected Ok, got exit=$it") }
    val mergedLiteral = assertIs<SysPropValue.Literal>(merged.testSection.sysProps["greeting"])
    assertEquals("overlay-value", mergedLiteral.value)

    deleteFile(KOLT_LOCAL_TOML)

    val reverted = loadProjectConfig().getOrElse { fail("expected Ok, got exit=$it") }
    val revertedLiteral = assertIs<SysPropValue.Literal>(reverted.testSection.sysProps["greeting"])
    assertEquals("base-value", revertedLiteral.value)
  }

  private fun createTempDir(prefix: String): String {
    val template = "/tmp/${prefix}XXXXXX"
    val buf = template.encodeToByteArray().copyOf(template.length + 1)
    buf.usePinned { pinned ->
      val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
      return result.toKString()
    }
  }

  companion object {
    private val BASE_TOML =
      """
            name = "my-app"
            version = "0.1.0"

            [kotlin]
            version = "2.1.0"

            [build]
            target = "jvm"
            main = "com.example.main"
            sources = ["src"]

            [test.sys_props]
            greeting = { literal = "base-value" }
        """
        .trimIndent()

    private val OVERLAY_TOML =
      """
            [test.sys_props]
            greeting = { literal = "overlay-value" }
        """
        .trimIndent()
  }
}
