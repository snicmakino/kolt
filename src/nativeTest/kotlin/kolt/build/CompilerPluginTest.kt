package kolt.build

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CompilerPluginTest {

    private val kotlinHome = "/usr/local/kotlinc"

    // --- pluginArgs ---

    @Test
    fun pluginArgsSingleEnabledPlugin() {
        val plugins = mapOf("serialization" to true)

        val result = pluginArgs(plugins, kotlinHome)

        val args = assertNotNull(result.get())
        assertEquals(
            listOf("-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"),
            args
        )
    }

    @Test
    fun pluginArgsMultipleEnabledPlugins() {
        val plugins = mapOf("serialization" to true, "allopen" to true)

        val result = pluginArgs(plugins, kotlinHome)

        val args = assertNotNull(result.get())
        assertEquals(2, args.size)
        assertEquals(
            "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
            args[0]
        )
        assertEquals(
            "-Xplugin=/usr/local/kotlinc/lib/allopen-compiler-plugin.jar",
            args[1]
        )
    }

    @Test
    fun pluginArgsDisabledPluginIsExcluded() {
        val plugins = mapOf("serialization" to false)

        val result = pluginArgs(plugins, kotlinHome)

        val args = assertNotNull(result.get())
        assertEquals(emptyList(), args)
    }

    @Test
    fun pluginArgsMixedEnabledAndDisabled() {
        val plugins = mapOf("serialization" to true, "allopen" to false, "noarg" to true)

        val result = pluginArgs(plugins, kotlinHome)

        val args = assertNotNull(result.get())
        assertEquals(2, args.size)
        assertEquals(
            "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
            args[0]
        )
        assertEquals(
            "-Xplugin=/usr/local/kotlinc/lib/noarg-compiler-plugin.jar",
            args[1]
        )
    }

    @Test
    fun pluginArgsEmptyMapReturnsEmptyList() {
        val result = pluginArgs(emptyMap(), kotlinHome)

        val args = assertNotNull(result.get())
        assertEquals(emptyList(), args)
    }

    @Test
    fun pluginArgsUnknownPluginReturnsError() {
        val plugins = mapOf("unknown-plugin" to true)

        val result = pluginArgs(plugins, kotlinHome)

        assertNull(result.get())
        val error = assertNotNull(result.getError())
        assertIs<UnknownPlugin>(error)
        assertEquals("unknown-plugin", error.name)
    }

    @Test
    fun pluginArgsUnknownDisabledPluginIsIgnored() {
        val plugins = mapOf("unknown-plugin" to false)

        val result = pluginArgs(plugins, kotlinHome)

        val args = assertNotNull(result.get())
        assertEquals(emptyList(), args)
    }

    @Test
    fun pluginArgsUsesKotlinHomePath() {
        val plugins = mapOf("serialization" to true)
        val customHome = "/home/user/.sdkman/candidates/kotlin/current"

        val result = pluginArgs(plugins, customHome)

        val args = assertNotNull(result.get())
        assertEquals(
            listOf("-Xplugin=/home/user/.sdkman/candidates/kotlin/current/lib/kotlinx-serialization-compiler-plugin.jar"),
            args
        )
    }

    // --- pluginJarPaths ---

    // #65 daemon path: produces an alias → list-of-jar-paths map for
    // `--plugin-jars`. Mirrors the existing pluginArgs lookup but emits
    // structured paths instead of `-Xplugin=...` strings so the daemon's
    // PluginTranslator can drive BTA's CompilerPlugin model.
    @Test
    fun pluginJarPathsSingleEnabledPluginReturnsAliasToJarList() {
        val result = pluginJarPaths(mapOf("serialization" to true), kotlinHome)

        val map = assertNotNull(result.get())
        assertEquals(
            mapOf("serialization" to listOf("/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar")),
            map,
        )
    }

    @Test
    fun pluginJarPathsDisabledPluginsAreExcluded() {
        val result = pluginJarPaths(
            mapOf("serialization" to true, "allopen" to false, "noarg" to true),
            kotlinHome,
        )

        val map = assertNotNull(result.get())
        assertEquals(setOf("serialization", "noarg"), map.keys)
    }

    @Test
    fun pluginJarPathsEmptyMapReturnsEmptyMap() {
        val result = pluginJarPaths(emptyMap(), kotlinHome)
        assertEquals(emptyMap(), assertNotNull(result.get()))
    }

    @Test
    fun pluginJarPathsUnknownEnabledPluginReturnsError() {
        val result = pluginJarPaths(mapOf("unknown-plugin" to true), kotlinHome)

        assertNull(result.get())
        val error = assertNotNull(result.getError())
        assertEquals("unknown-plugin", error.name)
    }

    // --- parseKotlinHome ---

    @Test
    fun parseKotlinHomeFromStandardPath() {
        assertEquals("/usr/local/kotlinc", parseKotlinHome("/usr/local/kotlinc/bin/kotlinc"))
    }

    @Test
    fun parseKotlinHomeFromSdkmanPath() {
        assertEquals(
            "/home/user/.sdkman/candidates/kotlin/current",
            parseKotlinHome("/home/user/.sdkman/candidates/kotlin/current/bin/kotlinc")
        )
    }

    @Test
    fun parseKotlinHomeFromMinimalPath() {
        assertEquals("/opt/kotlin", parseKotlinHome("/opt/kotlin/bin/kotlinc"))
    }
}
