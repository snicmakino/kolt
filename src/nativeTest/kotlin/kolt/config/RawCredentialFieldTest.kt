package kolt.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable

internal class RawCredentialFieldTest {

  @Serializable private data class Wrapper(val token: RawCredentialField? = null)

  private val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))

  @Test
  fun decodesLiteralVariant() {
    val input = """token = { literal = "abc" }""".trimIndent()
    val parsed = toml.decodeFromString(Wrapper.serializer(), input)
    val value = parsed.token
    assertIs<RawCredentialField.Literal>(value)
    assertEquals("abc", value.value)
  }

  @Test
  fun decodesEnvVariant() {
    val input = """token = { env = "X" }""".trimIndent()
    val parsed = toml.decodeFromString(Wrapper.serializer(), input)
    val value = parsed.token
    assertIs<RawCredentialField.Env>(value)
    assertEquals("X", value.varName)
  }

  @Test
  fun rejectsBothFieldsSet() {
    val input = """token = { literal = "a", env = "X" }""".trimIndent()
    val failure = assertFails { toml.decodeFromString(Wrapper.serializer(), input) }
    assertTrue(
      failure.message?.contains("exactly one") == true,
      "expected exactly-one-of error, got: ${failure.message}",
    )
  }

  @Test
  fun rejectsZeroFieldsSet() {
    val input = """token = {}""".trimIndent()
    val failure = assertFails { toml.decodeFromString(Wrapper.serializer(), input) }
    assertTrue(
      failure.message?.contains("exactly one") == true,
      "expected exactly-one-of error, got: ${failure.message}",
    )
  }

  @Test
  fun literalToStringRedactsValue() {
    val rendered = RawCredentialField.Literal("abc").toString()
    assertFalse(rendered.contains("abc"), "Literal.toString must not expose value, got: $rendered")
  }

  @Test
  fun envToStringContainsVarName() {
    val rendered = RawCredentialField.Env("X").toString()
    assertTrue(rendered.contains("X"), "Env.toString must include varName, got: $rendered")
  }
}
