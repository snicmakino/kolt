package kolt.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable

// Probe: does ktoml 0.7.1 preserve the TOML declaration order when decoding
// a Map<String, V>? The argv-builder for `kolt test -D...` and `kolt run -D...`
// depends on declaration order being deterministic; if ktoml does not preserve
// order, parseConfig must normalize via LinkedHashMap (per design risk note).
class ConfigOrderingTest {

  @Serializable private data class Wrapper(val sys_props: Map<String, SysPropValue> = emptyMap())

  private val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))

  @Test
  fun preservesDeclarationOrderAcrossThreeEntries() {
    val input =
      """
        [sys_props]
        "z.first" = { literal = "1" }
        "a.second" = { literal = "2" }
        "m.third" = { literal = "3" }
      """
        .trimIndent()
    val parsed = toml.decodeFromString(Wrapper.serializer(), input)
    val keys = parsed.sys_props.keys.toList()
    assertEquals(
      listOf("z.first", "a.second", "m.third"),
      keys,
      "declaration order is the contract for sysprop -D argv ordering; if this fails, " +
        "parseConfig must normalize the Map to a LinkedHashMap that mirrors source order",
    )
  }
}
