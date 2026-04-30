package kolt.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

class SysPropValueDecodeTest {

  @Serializable private data class Wrapper(val sys_props: Map<String, SysPropValue> = emptyMap())

  private val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))

  @Test
  fun decodesLiteralVariant() {
    val input =
      """
        [sys_props]
        "kolt.foo" = { literal = "verbatim-value" }
      """
        .trimIndent()
    val parsed = toml.decodeFromString(Wrapper.serializer(), input)
    val value = parsed.sys_props.getValue("kolt.foo")
    assertIs<SysPropValue.Literal>(value)
    assertEquals("verbatim-value", value.value)
  }

  @Test
  fun decodesClasspathRefVariant() {
    val input =
      """
        [sys_props]
        "kolt.bar" = { classpath = "bta-impl" }
      """
        .trimIndent()
    val parsed = toml.decodeFromString(Wrapper.serializer(), input)
    val value = parsed.sys_props.getValue("kolt.bar")
    assertIs<SysPropValue.ClasspathRef>(value)
    assertEquals("bta-impl", value.bundleName)
  }

  @Test
  fun decodesProjectDirVariant() {
    val input =
      """
        [sys_props]
        "kolt.baz" = { project_dir = "src/main/kotlin" }
      """
        .trimIndent()
    val parsed = toml.decodeFromString(Wrapper.serializer(), input)
    val value = parsed.sys_props.getValue("kolt.baz")
    assertIs<SysPropValue.ProjectDir>(value)
    assertEquals("src/main/kotlin", value.relativePath)
  }

  // Key-context (e.g. "kolt.bad: ...") is the responsibility of parseConfig
  // (task 1.3), not of SysPropValueSerializer. These cases verify that the
  // serializer rejects invalid shapes; the parseConfig-level wrapper will
  // attach the offending key name in ConfigSysPropValidationTest.
  @Test
  fun rejectsMultipleFieldsSet() {
    val input =
      """
        [sys_props]
        "kolt.bad" = { classpath = "X", project_dir = "Y" }
      """
        .trimIndent()
    val failure = assertFails { toml.decodeFromString(Wrapper.serializer(), input) }
    assertTrue(
      failure.message?.contains("exactly one") == true,
      "expected exactly-one-of error, got: ${failure.message}",
    )
  }

  @Test
  fun rejectsZeroFieldsSet() {
    val input =
      """
        [sys_props]
        "kolt.empty" = {}
      """
        .trimIndent()
    val failure = assertFails { toml.decodeFromString(Wrapper.serializer(), input) }
    assertTrue(
      failure.message?.contains("exactly one") == true,
      "expected exactly-one-of error, got: ${failure.message}",
    )
  }
}
