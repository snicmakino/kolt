package kolt.build

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.config.KoltPaths
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UserJdkTest {

  private val paths = KoltPaths(home = "/fake/home")

  @Test
  fun managedJdkReturnsPathsJdkHomeWhenInstalled() {
    val config = testConfig(jdk = "21")

    val result =
      resolveUserJdkHome(
        config,
        paths,
        exists = { path -> path == paths.javaBin("21") },
        probe = { error("must not probe system java when managed JDK resolves") },
      )

    val home = assertNotNull(result.get())
    assertEquals("21", home.version)
    assertEquals(paths.jdkPath("21"), home.home)
    assertNull(result.getError())
  }

  // An interrupted toolchain install or a manual prune can leave the JDK
  // directory behind without `bin/java`. Treat that the same as missing — a
  // home with no class roots would mislead kotlin-lsp.
  @Test
  fun managedJdkReturnsManagedMissingWhenBinJavaMissingEvenIfDirExists() {
    val config = testConfig(jdk = "21")

    val result =
      resolveUserJdkHome(
        config,
        paths,
        exists = { path -> path == paths.jdkPath("21") },
        probe = { error("must not probe system java when managed JDK is pinned") },
      )

    assertNull(result.get())
    assertEquals(
      UserJdkError.ManagedMissing(version = "21", expectedPath = paths.jdkPath("21")),
      result.getError(),
    )
  }

  // `jdk = ""` in kolt.toml parses as a non-null blank string. Treat it like
  // an unset pin so the warning doesn't surface an empty version label.
  @Test
  fun blankManagedJdkFallsBackToSystemProbe() {
    val config = testConfig(jdk = "", jvmTarget = "17")

    val result =
      resolveUserJdkHome(
        config,
        paths,
        exists = { error("must not check filesystem for a blank pin") },
        probe = { "/usr/lib/jvm/java-17-openjdk" },
      )

    val home = assertNotNull(result.get())
    assertEquals("17", home.version)
    assertEquals("/usr/lib/jvm/java-17-openjdk", home.home)
  }

  // When the user pinned a managed JDK that hasn't been installed yet,
  // silently falling back to a system `java` would contradict the pin and
  // mislead the LSP. Surface the error so callers can emit a targeted warning.
  @Test
  fun managedJdkReturnsManagedMissingWhenNotInstalled() {
    val config = testConfig(jdk = "21")

    val result =
      resolveUserJdkHome(
        config,
        paths,
        exists = { false },
        probe = { error("must not probe system java when managed JDK is pinned") },
      )

    assertNull(result.get())
    val err = assertNotNull(result.getError())
    assertEquals(
      UserJdkError.ManagedMissing(version = "21", expectedPath = paths.jdkPath("21")),
      err,
    )
  }

  @Test
  fun unmanagedJdkFallsBackToSystemProbe() {
    val config = testConfig(jvmTarget = "17")

    val result =
      resolveUserJdkHome(
        config,
        paths,
        exists = { error("unused for unmanaged path") },
        probe = { "/usr/lib/jvm/java-17-openjdk" },
      )

    val home = assertNotNull(result.get())
    assertEquals("17", home.version)
    assertEquals("/usr/lib/jvm/java-17-openjdk", home.home)
  }

  @Test
  fun unmanagedJdkReturnsSystemProbeFailedWhenProbeReturnsNull() {
    val config = testConfig()

    val result = resolveUserJdkHome(config, paths, exists = { false }, probe = { null })

    assertNull(result.get())
    assertEquals(UserJdkError.SystemProbeFailed, result.getError())
  }

  @Test
  fun parseJavaHomeFromPropertiesExtractsIndentedAssignment() {
    val output =
      """
      OpenJDK 64-Bit Server VM warning: ignoring option X
      Property settings:
          java.class.path =
          java.home = /usr/lib/jvm/java-21-openjdk
          java.io.tmpdir = /tmp
      """
        .trimIndent()

    assertEquals("/usr/lib/jvm/java-21-openjdk", parseJavaHomeFromProperties(output))
  }

  @Test
  fun parseJavaHomeFromPropertiesIgnoresSimilarKeys() {
    // `java.home.foo` is not a real JDK property today, but harden the parser
    // so a future namespace collision does not return the wrong value.
    val output =
      """
          java.home.foo = /bogus
          java.home = /real/home
      """
        .trimIndent()

    assertEquals("/real/home", parseJavaHomeFromProperties(output))
  }

  @Test
  fun parseJavaHomeFromPropertiesReturnsNullWhenAbsent() {
    val output =
      """
      Property settings:
          file.encoding = UTF-8
      """
        .trimIndent()

    assertNull(parseJavaHomeFromProperties(output))
  }
}
