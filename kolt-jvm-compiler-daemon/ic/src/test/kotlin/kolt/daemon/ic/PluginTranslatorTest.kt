package kolt.daemon.ic

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Unit tests for the plugin-jar map -> `-Xplugin=<jar>` freeArgs translation.
// See `PluginTranslator.kt` for why `-Xplugin=` is the chosen passthrough
// surface (BTA 2.3.x family compatibility).
class PluginTranslatorTest {

  @Test
  fun `empty map yields no plugin args`() {
    assertTrue(PluginTranslator.translate(emptyMap()).isEmpty())
  }

  @Test
  fun `single alias emits one -Xplugin= arg per resolved jar`() {
    val jar = Path.of("/fake/kotlinx-serialization-compiler-plugin.jar")
    assertEquals(
      listOf("-Xplugin=$jar"),
      PluginTranslator.translate(mapOf("serialization" to listOf(jar))),
    )
  }

  @Test
  fun `multi-jar value emits one arg per jar preserving order`() {
    val jars =
      listOf(
        Path.of("/fake/kotlinx-serialization-compiler-plugin.jar"),
        Path.of("/fake/kotlinx-serialization-compiler-plugin-embeddable.jar"),
      )
    assertEquals(
      jars.map { "-Xplugin=$it" },
      PluginTranslator.translate(mapOf("serialization" to jars)),
    )
  }

  // Production upstream (`PluginJarFetcher` in the native client) fails before
  // the daemon request is built if a plugin jar cannot be fetched, so the map
  // never carries an empty list for an alias in practice. This test pins the
  // boundary case: if it does happen (tests, future rewiring) the translator
  // emits no `-Xplugin=` entries rather than a malformed one.
  @Test
  fun `empty value list for an alias produces no args`() {
    assertTrue(PluginTranslator.translate(mapOf("serialization" to emptyList())).isEmpty())
  }

  // Iteration order matters: BTA's `applyArgumentStrings` consumes freeArgs
  // positionally, so two aliases must keep the map's declaration order.
  @Test
  fun `multi-alias map preserves declaration order across aliases`() {
    val allopen = Path.of("/fake/allopen-compiler-plugin.jar")
    val noarg = Path.of("/fake/noarg-compiler-plugin.jar")
    assertEquals(
      listOf("-Xplugin=$allopen", "-Xplugin=$noarg"),
      PluginTranslator.translate(
        linkedMapOf("allopen" to listOf(allopen), "noarg" to listOf(noarg))
      ),
    )
  }
}
