package kolt.config

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RepositorySchemaMigrationTest {

  private val baseToml =
    """
        name = "my-app"
        version = "0.1.0"

        [kotlin]
        version = "2.1.0"

        [build]
        target = "jvm"
        main = "com.example.main"
        sources = ["src"]

    """
      .trimIndent()

  @Test
  fun acceptSubTableForm() {
    val toml =
      baseToml +
        """
            [repositories.central]
            url = "https://repo1.maven.org/maven2"
        """
          .trimIndent()

    val config = assertNotNull(parseConfig(toml).get())
    assertEquals(1, config.repositories.size)
    assertEquals(Repository("https://repo1.maven.org/maven2"), config.repositories["central"])
  }

  @Test
  fun preserveDeclarationOrderAcrossMultipleSubTables() {
    val toml =
      baseToml +
        """
            [repositories.central]
            url = "https://repo1.maven.org/maven2"

            [repositories.internal]
            url = "https://nexus.example.com/repository/internal"
        """
          .trimIndent()

    val config = assertNotNull(parseConfig(toml).get())
    val keys = config.repositories.keys.toList()
    assertEquals(listOf("central", "internal"), keys)
  }

  @Test
  fun rejectLegacyFlatFormWithMigrationHint() {
    val toml =
      baseToml +
        """
            [repositories]
            central = "https://repo1.maven.org/maven2"
        """
          .trimIndent()

    val err = parseConfig(toml, path = "kolt.toml").getError()
    val parseFailed = assertNotNull(err) as ConfigError.ParseFailed
    val rendered = renderConfigErrorAsLine(parseFailed)
    assertTrue(
      "repositories schema migrated" in rendered,
      "expected migration hint in error message; actual: $rendered",
    )
    assertTrue(
      "[repositories.<name>]" in rendered || "repositories.<name>" in rendered,
      "expected sub-table form mentioned in error message; actual: $rendered",
    )
    assertTrue(
      "kolt.toml" in rendered,
      "expected source file `kolt.toml` named in error message; actual: $rendered",
    )
  }

  @Test
  fun rejectEmptySubTableWithRepositoryName() {
    val toml =
      baseToml +
        """
            [repositories.x]
        """
          .trimIndent()

    val err = parseConfig(toml).getError()
    val parseFailed = assertNotNull(err) as ConfigError.ParseFailed
    assertTrue(
      "x" in parseFailed.message && "url" in parseFailed.message,
      "expected error naming repository 'x' and 'url'; actual: ${parseFailed.message}",
    )
  }
}
