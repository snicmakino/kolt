package keel.cli

import keel.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginSupportTest {

    // --- resolvePluginArgs with managedKotlincBin ---

    @Test
    fun resolvePluginArgsNoPluginsReturnsEmpty() {
        val config = testConfig(plugins = emptyMap())

        val result = resolvePluginArgs(config, managedKotlincBin = "/home/user/.keel/toolchains/kotlinc/2.1.0/bin/kotlinc")

        assertTrue(result.isEmpty())
    }

    @Test
    fun resolvePluginArgsWithManagedBinUsesToolchainHome() {
        val config = testConfig(plugins = mapOf("serialization" to true))
        val managedBin = "/home/user/.keel/toolchains/kotlinc/2.1.0/bin/kotlinc"

        val result = resolvePluginArgs(config, managedKotlincBin = managedBin)

        assertEquals(
            listOf("-Xplugin=/home/user/.keel/toolchains/kotlinc/2.1.0/lib/kotlinx-serialization-compiler-plugin.jar"),
            result
        )
    }

    @Test
    fun resolvePluginArgsWithManagedBinDoesNotUseSystemKotlinc() {
        // When managedKotlincBin is provided, plugin JARs must come from the managed toolchain,
        // not from system kotlinc. This ensures compiler and plugin versions are consistent.
        val config = testConfig(plugins = mapOf("allopen" to true))
        val managedBin = "/home/user/.keel/toolchains/kotlinc/2.1.0/bin/kotlinc"

        val result = resolvePluginArgs(config, managedKotlincBin = managedBin)

        assertEquals(1, result.size)
        assertTrue(
            result[0].startsWith("-Xplugin=/home/user/.keel/toolchains/kotlinc/2.1.0/"),
            "Plugin arg should reference managed toolchain path, got: ${result[0]}"
        )
    }

    @Test
    fun resolvePluginArgsWithNullManagedBinAndNoPluginsReturnsEmpty() {
        val config = testConfig(plugins = emptyMap())

        val result = resolvePluginArgs(config, managedKotlincBin = null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun resolvePluginArgsDisabledPluginIsExcluded() {
        val config = testConfig(plugins = mapOf("serialization" to false))
        val managedBin = "/home/user/.keel/toolchains/kotlinc/2.1.0/bin/kotlinc"

        val result = resolvePluginArgs(config, managedKotlincBin = managedBin)

        assertTrue(result.isEmpty())
    }
}
