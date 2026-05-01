package kolt.daemon.ic

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageVersionTranslatorTest {

  private val baseToml =
    """
        name = "demo"
        version = "0.1.0"

        [build]
        target = "jvm"
        main = "demo.Main"
        sources = ["src/main/kotlin"]
    """
      .trimIndent()

  @Test
  fun `missing kolt_toml yields no args`() {
    val projectRoot = Files.createTempDirectory("lang-translator-empty-")
    assertTrue(LanguageVersionTranslator.translate(projectRoot).isEmpty())
  }

  @Test
  fun `compiler unset yields no args`() {
    val projectRoot = Files.createTempDirectory("lang-translator-unset-")
    projectRoot
      .resolve("kolt.toml")
      .writeText(
        """
            $baseToml

            [kotlin]
            version = "2.1.0"
            """
          .trimIndent()
      )
    assertTrue(LanguageVersionTranslator.translate(projectRoot).isEmpty())
  }

  @Test
  fun `compiler equal to version yields no args`() {
    val projectRoot = Files.createTempDirectory("lang-translator-equal-")
    projectRoot
      .resolve("kolt.toml")
      .writeText(
        """
            $baseToml

            [kotlin]
            version = "2.3.20"
            compiler = "2.3.20"
            """
          .trimIndent()
      )
    assertTrue(LanguageVersionTranslator.translate(projectRoot).isEmpty())
  }

  @Test
  fun `compiler higher than version emits language and api flags pinned to version`() {
    val projectRoot = Files.createTempDirectory("lang-translator-higher-")
    projectRoot
      .resolve("kolt.toml")
      .writeText(
        """
            $baseToml

            [kotlin]
            version = "2.1.0"
            compiler = "2.3.20"
            """
          .trimIndent()
      )
    assertEquals(
      listOf("-language-version", "2.1", "-api-version", "2.1"),
      LanguageVersionTranslator.translate(projectRoot),
    )
  }
}
