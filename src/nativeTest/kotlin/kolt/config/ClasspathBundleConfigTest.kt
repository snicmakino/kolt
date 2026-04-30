package kolt.config

import com.github.michaelbull.result.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ClasspathBundleConfigTest {

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
  fun acceptsSingleDeclaredBundle() {
    val toml =
      baseToml +
        """

        [classpaths.bta-impl]
        "org.jetbrains.kotlin:kotlin-build-tools-impl" = "2.3.20"
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    val bundle = assertNotNull(config.classpaths["bta-impl"])
    assertEquals("2.3.20", bundle["org.jetbrains.kotlin:kotlin-build-tools-impl"])
  }

  @Test
  fun acceptsMultipleDistinctBundles() {
    val toml =
      baseToml +
        """

        [classpaths.bta-impl]
        "org.jetbrains.kotlin:kotlin-build-tools-impl" = "2.3.20"

        [classpaths.fixture]
        "org.jetbrains.kotlin:kotlin-stdlib" = "2.3.20"
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    assertEquals(2, config.classpaths.size)
    assertNotNull(config.classpaths["bta-impl"])
    assertNotNull(config.classpaths["fixture"])
  }

  @Test
  fun acceptsEmptyClasspathBundle() {
    val toml =
      baseToml +
        """

        [classpaths.empty-on-purpose]
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    val bundle = assertNotNull(config.classpaths["empty-on-purpose"])
    assertEquals(emptyMap(), bundle)
  }

  @Test
  fun acceptsSameGavInDependenciesAndBundleWithDifferentVersions() {
    val toml =
      baseToml +
        """

        [dependencies]
        "org.jetbrains.kotlin:kotlin-stdlib" = "2.3.20"

        [classpaths.legacy-fixture]
        "org.jetbrains.kotlin:kotlin-stdlib" = "2.0.0"
      """
          .trimIndent()
    val config = assertNotNull(parseConfig(toml).get())
    assertEquals("2.3.20", config.dependencies["org.jetbrains.kotlin:kotlin-stdlib"])
    assertEquals(
      "2.0.0",
      config.classpaths["legacy-fixture"]?.get("org.jetbrains.kotlin:kotlin-stdlib"),
    )
  }

  @Test
  fun absentClasspathsKeepEmptyMap() {
    val config = assertNotNull(parseConfig(baseToml).get())
    assertEquals(emptyMap(), config.classpaths)
  }
}
