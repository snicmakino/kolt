@file:OptIn(org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi::class)

package kolt.daemon.ic

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Unit tests for the kolt.toml [plugins] → CompilerPlugin translation path
// defined by ADR 0019 §9. PluginTranslator is a pure function: given a
// projectRoot and a jar resolver, it parses kolt.toml and emits the list of
// CompilerPlugin instances that BtaIncrementalCompiler will attach to the
// `COMPILER_PLUGINS` compiler argument on the JvmCompilationOperation.
//
// The resolver is injected so these tests can run without any real plugin
// jars on disk — B-2a only needs to prove the translation path exists and is
// exercised from the compile path. Actual plugin jar delivery (e.g. real
// kotlinx-serialization-compiler-plugin.jar) is a B-2b / daemon-core concern.
class PluginTranslatorTest {

    @Test
    fun `missing kolt_toml yields an empty plugin list`() {
        val projectRoot = Files.createTempDirectory("plugin-translator-empty-")
        val plugins = PluginTranslator.translate(
            projectRoot = projectRoot,
            jarResolver = { _ -> error("resolver must not be called when no plugins section") },
        )
        assertTrue(plugins.isEmpty(), "no kolt.toml → no plugins, got $plugins")
    }

    @Test
    fun `kolt_toml with no plugins section yields an empty list`() {
        val projectRoot = Files.createTempDirectory("plugin-translator-no-section-")
        projectRoot.resolve("kolt.toml").writeText(
            """
            name = "demo"
            version = "0.1.0"
            kotlin = "2.3.20"
            target = "jvm"
            main = "demo.Main"
            sources = ["src/main/kotlin"]
            """.trimIndent(),
        )
        val plugins = PluginTranslator.translate(
            projectRoot = projectRoot,
            jarResolver = { _ -> error("resolver must not be called when plugins map is empty") },
        )
        assertTrue(plugins.isEmpty())
    }

    @Test
    fun `serialization plugin entry is translated to a CompilerPlugin`() {
        val projectRoot = Files.createTempDirectory("plugin-translator-serialization-")
        projectRoot.resolve("kolt.toml").writeText(
            """
            name = "demo"
            version = "0.1.0"
            kotlin = "2.3.20"
            target = "jvm"
            main = "demo.Main"
            sources = ["src/main/kotlin"]

            [plugins]
            serialization = true
            """.trimIndent(),
        )
        val fakeJar = Path.of("/fake/kotlinx-serialization-compiler-plugin.jar")
        val resolved = mutableListOf<String>()

        val plugins = PluginTranslator.translate(
            projectRoot = projectRoot,
            jarResolver = { name ->
                resolved += name
                if (name == "serialization") listOf(fakeJar) else emptyList()
            },
        )

        assertEquals(listOf("serialization"), resolved, "resolver should be asked once for the serialization plugin")
        assertEquals(1, plugins.size, "expected exactly one translated plugin, got $plugins")
        val plugin = plugins.single()
        assertEquals(PluginTranslator.SERIALIZATION_PLUGIN_ID, plugin.pluginId)
        assertEquals(listOf(fakeJar), plugin.classpath)
    }

    @Test
    fun `disabled plugin entry is skipped entirely`() {
        val projectRoot = Files.createTempDirectory("plugin-translator-disabled-")
        projectRoot.resolve("kolt.toml").writeText(
            """
            name = "demo"
            version = "0.1.0"
            kotlin = "2.3.20"
            target = "jvm"
            main = "demo.Main"
            sources = ["src/main/kotlin"]

            [plugins]
            serialization = false
            """.trimIndent(),
        )
        val plugins = PluginTranslator.translate(
            projectRoot = projectRoot,
            jarResolver = { _ -> error("resolver must not be called for disabled plugin") },
        )
        assertTrue(plugins.isEmpty())
    }

    // #65 native client wiring: allopen / noarg join serialization in the
    // alias map so the JVM build path can enable them via kolt.toml. The
    // plugin IDs are the stock Kotlin compiler ones (`org.jetbrains.kotlin.
    // allopen`, `org.jetbrains.kotlin.noarg`) — not vendor-renamed — so a
    // future bump of the kolt-compiler-daemon kotlin version does not need
    // to revisit this map.
    @Test
    fun `allopen plugin entry is translated to the stock allopen CompilerPlugin id`() {
        val projectRoot = Files.createTempDirectory("plugin-translator-allopen-")
        projectRoot.resolve("kolt.toml").writeText(
            """
            name = "demo"
            version = "0.1.0"
            kotlin = "2.3.20"
            target = "jvm"
            main = "demo.Main"
            sources = ["src/main/kotlin"]

            [plugins]
            allopen = true
            """.trimIndent(),
        )
        val fakeJar = Path.of("/fake/allopen-compiler-plugin.jar")
        val plugins = PluginTranslator.translate(
            projectRoot = projectRoot,
            jarResolver = { name -> if (name == "allopen") listOf(fakeJar) else emptyList() },
        )
        assertEquals(1, plugins.size)
        assertEquals(PluginTranslator.ALLOPEN_PLUGIN_ID, plugins.single().pluginId)
        assertEquals(listOf(fakeJar), plugins.single().classpath)
    }

    @Test
    fun `noarg plugin entry is translated to the stock noarg CompilerPlugin id`() {
        val projectRoot = Files.createTempDirectory("plugin-translator-noarg-")
        projectRoot.resolve("kolt.toml").writeText(
            """
            name = "demo"
            version = "0.1.0"
            kotlin = "2.3.20"
            target = "jvm"
            main = "demo.Main"
            sources = ["src/main/kotlin"]

            [plugins]
            noarg = true
            """.trimIndent(),
        )
        val fakeJar = Path.of("/fake/noarg-compiler-plugin.jar")
        val plugins = PluginTranslator.translate(
            projectRoot = projectRoot,
            jarResolver = { name -> if (name == "noarg") listOf(fakeJar) else emptyList() },
        )
        assertEquals(1, plugins.size)
        assertEquals(PluginTranslator.NOARG_PLUGIN_ID, plugins.single().pluginId)
        assertEquals(listOf(fakeJar), plugins.single().classpath)
    }

    @Test
    fun `unresolved plugin jar produces an empty classpath but still emits a CompilerPlugin`() {
        // An empty classpath from the resolver does not mean "skip this plugin":
        // B-2a's plugin-jar delivery path is not wired yet, so the resolver
        // legitimately returns emptyList() for every known id. The translator
        // still emits a CompilerPlugin so the attached COMPILER_PLUGINS list is
        // non-empty and the BTA layer sees the plugin request. This matches the
        // #112 acceptance criterion 4 wording: "a compile failure that reaches
        // the BTA layer with the plugin classpath attached is sufficient".
        val projectRoot = Files.createTempDirectory("plugin-translator-unresolved-")
        projectRoot.resolve("kolt.toml").writeText(
            """
            name = "demo"
            version = "0.1.0"
            kotlin = "2.3.20"
            target = "jvm"
            main = "demo.Main"
            sources = ["src/main/kotlin"]

            [plugins]
            serialization = true
            """.trimIndent(),
        )
        val plugins = PluginTranslator.translate(
            projectRoot = projectRoot,
            jarResolver = { _ -> emptyList() },
        )
        assertEquals(1, plugins.size)
        assertTrue(plugins.single().classpath.isEmpty())
    }
}
