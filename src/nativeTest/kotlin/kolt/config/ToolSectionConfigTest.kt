package kolt.config

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.resolve.Coordinate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolSectionConfigTest {

  private val baseToml =
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

  @Test
  fun emptyToolsWhenSectionMissing() {
    val config = assertNotNull(parseConfig(baseToml).get())
    assertTrue(config.tools.isEmpty(), "tools should default to empty map")
  }

  @Test
  fun parsesSingleToolEntryWithoutClassifier() {
    val toml =
      baseToml +
        """

        [tools.foo]
        coords = "a:b:1.0"
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    val entry = assertNotNull(config.tools["foo"])
    assertEquals(Coordinate("a", "b", "1.0"), entry.coords)
    assertNull(entry.classifier)
  }

  @Test
  fun parsesToolEntryWithClassifier() {
    val toml =
      baseToml +
        """

        [tools.ktlint]
        coords = "com.pinterest.ktlint:ktlint-cli:1.3.1:all"
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    val entry = assertNotNull(config.tools["ktlint"])
    assertEquals(Coordinate("com.pinterest.ktlint", "ktlint-cli", "1.3.1"), entry.coords)
    assertEquals("all", entry.classifier)
  }

  @Test
  fun parsesMultipleToolEntries() {
    val toml =
      baseToml +
        """

        [tools.foo]
        coords = "a:b:1.0"

        [tools.bar]
        coords = "c:d:2.0:cls"
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    assertEquals(2, config.tools.size)
    assertEquals(Coordinate("a", "b", "1.0"), config.tools["foo"]?.coords)
    assertEquals(Coordinate("c", "d", "2.0"), config.tools["bar"]?.coords)
    assertEquals("cls", config.tools["bar"]?.classifier)
  }

  @Test
  fun rejectsUppercaseAlias() {
    val toml =
      baseToml +
        """

        [tools.Foo]
        coords = "a:b:1.0"
      """
          .trimIndent()
    val err = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertContains(err.message, "Foo")
    assertContains(err.message, "alias")
  }

  @Test
  fun rejectsDependsOnField() {
    val toml =
      baseToml +
        """

        [tools.foo]
        coords = "a:b:1.0"
        depends-on = "bar"
      """
          .trimIndent()
    val err = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertContains(err.message, "depends-on")
    assertContains(err.message, "foo")
  }

  @Test
  fun rejectsArgsField() {
    val toml =
      baseToml +
        """

        [tools.foo]
        coords = "a:b:1.0"
        args = []
      """
          .trimIndent()
    val err = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertContains(err.message, "args")
    assertContains(err.message, "foo")
  }

  @Test
  fun rejectsMainField() {
    val toml =
      baseToml +
        """

        [tools.foo]
        coords = "a:b:1.0"
        main = "com.example.Main"
      """
          .trimIndent()
    val err = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertContains(err.message, "main")
    assertContains(err.message, "foo")
  }

  @Test
  fun rejectsMissingCoords() {
    val toml =
      baseToml +
        """

        [tools.foo]
      """
          .trimIndent()
    val err = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertContains(err.message, "coords")
    assertContains(err.message, "foo")
  }

  @Test
  fun rejectsMalformedCoords() {
    val toml =
      baseToml +
        """

        [tools.foo]
        coords = "no-colons-here"
      """
          .trimIndent()
    val err = assertIs<ConfigError.ParseFailed>(parseConfig(toml).getError())
    assertContains(err.message, "foo")
    assertContains(err.message, "no-colons-here")
  }
}
