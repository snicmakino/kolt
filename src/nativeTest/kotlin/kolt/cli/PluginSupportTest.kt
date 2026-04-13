package kolt.cli

import kolt.config.KoltPaths
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginSupportTest {

    // --- resolvePluginArgs with managedKotlincBin ---

    @Test
    fun resolvePluginArgsNoPluginsReturnsEmpty() {
        val config = testConfig(plugins = emptyMap())

        val result = resolvePluginArgs(config, managedKotlincBin = "/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc")

        assertTrue(result.isEmpty())
    }

    @Test
    fun resolvePluginArgsWithManagedBinUsesToolchainHome() {
        val config = testConfig(plugins = mapOf("serialization" to true))
        val managedBin = "/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc"

        val result = resolvePluginArgs(config, managedKotlincBin = managedBin)

        assertEquals(
            listOf("-Xplugin=/home/user/.kolt/toolchains/kotlinc/2.1.0/lib/kotlinx-serialization-compiler-plugin.jar"),
            result
        )
    }

    @Test
    fun resolvePluginArgsWithManagedBinDoesNotUseSystemKotlinc() {
        // When managedKotlincBin is provided, plugin JARs must come from the managed toolchain,
        // not from system kotlinc. This ensures compiler and plugin versions are consistent.
        val config = testConfig(plugins = mapOf("allopen" to true))
        val managedBin = "/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc"

        val result = resolvePluginArgs(config, managedKotlincBin = managedBin)

        assertEquals(1, result.size)
        assertTrue(
            result[0].startsWith("-Xplugin=/home/user/.kolt/toolchains/kotlinc/2.1.0/"),
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
        val managedBin = "/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc"

        val result = resolvePluginArgs(config, managedKotlincBin = managedBin)

        assertTrue(result.isEmpty())
    }

    // --- resolveNativePluginArgs short-circuit ---
    //
    // These tests use a bogus KoltPaths home. If the short-circuit regresses
    // and resolveNativePluginArgs starts provisioning kotlinc unconditionally,
    // ensureKotlincBin will try to download under the bogus path and exit the
    // test process, failing the test.

    @Test
    fun resolveNativePluginArgsNoPluginsSkipsKotlincProvisioning() {
        val config = testConfig(plugins = emptyMap(), target = "native")
        val bogusPaths = KoltPaths("/nonexistent-home-for-test")

        val result = resolveNativePluginArgs(config, bogusPaths, exitCode = 999)

        assertTrue(result.isEmpty())
    }

    @Test
    fun resolveNativePluginArgsAllPluginsDisabledSkipsKotlincProvisioning() {
        val config = testConfig(
            plugins = mapOf("serialization" to false, "allopen" to false),
            target = "native"
        )
        val bogusPaths = KoltPaths("/nonexistent-home-for-test")

        val result = resolveNativePluginArgs(config, bogusPaths, exitCode = 999)

        assertTrue(result.isEmpty())
    }
}
