package kolt.config

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigSysPropValidationTest {

  private val jvmAppToml =
    """
        name = "x"
        version = "0.1.0"

        [kotlin]
        version = "2.3.20"

        [build]
        target = "jvm"
        main = "com.example.main"
        sources = ["src"]
    """
      .trimIndent()

  private val jvmLibToml =
    """
        name = "x"
        version = "0.1.0"
        kind = "lib"

        [kotlin]
        version = "2.3.20"

        [build]
        target = "jvm"
        sources = ["src"]
    """
      .trimIndent()

  private val nativeAppToml =
    """
        name = "x"
        version = "0.1.0"

        [kotlin]
        version = "2.3.20"

        [build]
        target = "linuxX64"
        main = "main"
        sources = ["src"]
    """
      .trimIndent()

  // ---------- empty / baseline ----------

  @Test
  fun acceptsEmptyTestAndRunSysProps() {
    val config = assertNotNull(parseConfig(jvmAppToml).get())
    assertEquals(emptyMap(), config.testSection.sysProps)
    assertEquals(emptyMap(), config.runSection.sysProps)
  }

  @Test
  fun acceptsExplicitlyEmptySysPropsTables() {
    val toml =
      jvmAppToml +
        """

        [test.sys_props]

        [run.sys_props]
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    assertEquals(emptyMap(), config.testSection.sysProps)
    assertEquals(emptyMap(), config.runSection.sysProps)
  }

  // ---------- value type lift ----------

  @Test
  fun liftsLiteralVariant() {
    val toml =
      jvmAppToml +
        """

        [test.sys_props]
        "log.level" = { literal = "INFO" }
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    val value = assertIs<SysPropValue.Literal>(config.testSection.sysProps["log.level"])
    assertEquals("INFO", value.value)
  }

  @Test
  fun liftsClasspathRefVariant() {
    val toml =
      jvmAppToml +
        """

        [classpaths.bta-impl]
        "org.jetbrains.kotlin:kotlin-build-tools-impl" = "2.3.20"

        [test.sys_props]
        "kolt.ic.btaImplClasspath" = { classpath = "bta-impl" }
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    val value =
      assertIs<SysPropValue.ClasspathRef>(config.testSection.sysProps["kolt.ic.btaImplClasspath"])
    assertEquals("bta-impl", value.bundleName)
  }

  @Test
  fun liftsProjectDirVariant() {
    val toml =
      jvmAppToml +
        """

        [test.sys_props]
        "kolt.daemon.coreMainSourceRoot" = { project_dir = "src/main/kotlin" }
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    val value =
      assertIs<SysPropValue.ProjectDir>(
        config.testSection.sysProps["kolt.daemon.coreMainSourceRoot"]
      )
    assertEquals("src/main/kotlin", value.relativePath)
  }

  // ---------- malformed-shape rejection with key context ----------

  @Test
  fun rejectsMultipleFieldsSetWithKeyContext() {
    val toml =
      jvmAppToml +
        """

        [test.sys_props]
        "kolt.bad" = { literal = "X", classpath = "Y" }
      """
          .trimIndent()
    val error = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertTrue(
      error.message.contains("kolt.bad"),
      "error must name offending key 'kolt.bad', got: ${error.message}",
    )
  }

  @Test
  fun rejectsZeroFieldsSetWithKeyContext() {
    val toml =
      jvmAppToml +
        """

        [test.sys_props]
        "kolt.empty" = {}
      """
          .trimIndent()
    val error = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertTrue(
      error.message.contains("kolt.empty"),
      "error must name offending key 'kolt.empty', got: ${error.message}",
    )
  }

  // ---------- bundle reference validation ----------

  @Test
  fun rejectsClasspathRefToUndeclaredBundle() {
    val toml =
      jvmAppToml +
        """

        [test.sys_props]
        "kolt.ic.btaImplClasspath" = { classpath = "missing-bundle" }
      """
          .trimIndent()
    val error = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertTrue(
      error.message.contains("missing-bundle"),
      "error must name missing bundle, got: ${error.message}",
    )
    assertTrue(
      error.message.contains("kolt.ic.btaImplClasspath"),
      "error must name offending sysprop key, got: ${error.message}",
    )
  }

  @Test
  fun acceptsClasspathRefFromRunSysProps() {
    val toml =
      jvmAppToml +
        """

        [classpaths.runtime-libs]
        "org.example:lib" = "1.0.0"

        [run.sys_props]
        "app.libpath" = { classpath = "runtime-libs" }
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    val value = assertIs<SysPropValue.ClasspathRef>(config.runSection.sysProps["app.libpath"])
    assertEquals("runtime-libs", value.bundleName)
  }

  // ---------- project_dir validation (corner cases per design table) ----------

  @Test
  fun rejectsAbsoluteProjectDir() {
    val toml =
      jvmAppToml +
        """

        [test.sys_props]
        "kolt.foo" = { project_dir = "/etc/somewhere" }
      """
          .trimIndent()
    val error = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertTrue(
      error.message.contains("kolt.foo"),
      "error must name offending key, got: ${error.message}",
    )
    assertTrue(
      error.message.contains("absolute") || error.message.contains("/etc"),
      "error must indicate absolute-path issue, got: ${error.message}",
    )
  }

  @Test
  fun rejectsProjectDirThatEscapesProjectRoot() {
    val toml =
      jvmAppToml +
        """

        [test.sys_props]
        "kolt.bad" = { project_dir = "../outside" }
      """
          .trimIndent()
    val error = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertTrue(
      error.message.contains("kolt.bad"),
      "error must name offending key, got: ${error.message}",
    )
  }

  @Test
  fun rejectsEmptyProjectDir() {
    val toml =
      jvmAppToml +
        """

        [test.sys_props]
        "kolt.empty" = { project_dir = "" }
      """
          .trimIndent()
    val error = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertTrue(
      error.message.contains("kolt.empty"),
      "error must name offending key, got: ${error.message}",
    )
  }

  @Test
  fun acceptsDotAsProjectDir() {
    val toml =
      jvmAppToml +
        """

        [test.sys_props]
        "kolt.root" = { project_dir = "." }
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    val value = assertIs<SysPropValue.ProjectDir>(config.testSection.sysProps["kolt.root"])
    assertEquals(".", value.relativePath)
  }

  @Test
  fun acceptsTrailingSlashInProjectDir() {
    val toml =
      jvmAppToml +
        """

        [test.sys_props]
        "kolt.foo" = { project_dir = "src/main/kotlin/" }
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    val value = assertIs<SysPropValue.ProjectDir>(config.testSection.sysProps["kolt.foo"])
    assertEquals("src/main/kotlin/", value.relativePath)
  }

  @Test
  fun acceptsNonexistentProjectDirAtParseTime() {
    // Per design corner-case table: existence is not validated at parse time.
    val toml =
      jvmAppToml +
        """

        [test.sys_props]
        "kolt.future" = { project_dir = "build/test-output-yet-to-exist" }
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    assertIs<SysPropValue.ProjectDir>(config.testSection.sysProps["kolt.future"])
  }

  // ---------- target / kind compatibility (Req 5.1-5.4) ----------

  @Test
  fun rejectsNativeTargetWithClasspaths() {
    val toml =
      nativeAppToml +
        """

        [classpaths.foo]
        "org.example:lib" = "1.0.0"
      """
          .trimIndent()
    val error = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertTrue(
      error.message.contains("classpaths"),
      "error must name [classpaths] table, got: ${error.message}",
    )
  }

  @Test
  fun rejectsNativeTargetWithTestSysProps() {
    val toml =
      nativeAppToml +
        """

        [test.sys_props]
        "log.level" = { literal = "INFO" }
      """
          .trimIndent()
    val error = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertTrue(
      error.message.contains("test.sys_props"),
      "error must name [test.sys_props] table, got: ${error.message}",
    )
  }

  @Test
  fun rejectsNativeTargetWithRunSysProps() {
    val toml =
      nativeAppToml +
        """

        [run.sys_props]
        "log.level" = { literal = "INFO" }
      """
          .trimIndent()
    val error = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertTrue(
      error.message.contains("run.sys_props"),
      "error must name [run.sys_props] table, got: ${error.message}",
    )
  }

  @Test
  fun rejectsLibKindWithRunSysProps() {
    val toml =
      jvmLibToml +
        """

        [run.sys_props]
        "log.level" = { literal = "INFO" }
      """
          .trimIndent()
    val error = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertTrue(
      error.message.contains("run.sys_props") || error.message.contains("lib"),
      "error must indicate lib + run.sys_props mismatch, got: ${error.message}",
    )
  }

  @Test
  fun acceptsLibKindWithTestSysProps() {
    val toml =
      jvmLibToml +
        """

        [test.sys_props]
        "log.level" = { literal = "INFO" }
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    assertEquals(1, config.testSection.sysProps.size)
  }

  @Test
  fun acceptsLibKindWithClasspaths() {
    val toml =
      jvmLibToml +
        """

        [classpaths.shared]
        "org.example:lib" = "1.0.0"
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    assertEquals(1, config.classpaths.size)
  }
}
