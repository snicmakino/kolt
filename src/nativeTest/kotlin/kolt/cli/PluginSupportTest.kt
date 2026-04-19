package kolt.cli

import com.github.michaelbull.result.get
import kolt.config.KoltPaths
import kolt.infra.DownloadError
import kolt.resolve.PluginFetchError
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Bogus KoltPaths: if the zero-plugin short-circuit regresses, any I/O
// attempt under this path will fail the test.
class PluginSupportTest {

    private val bogusPaths = KoltPaths("/nonexistent-home-for-test")

    @Test
    fun resolvePluginArgsNoPluginsReturnsEmptyWithoutTouchingTheNetwork() {
        val config = testConfig(plugins = emptyMap())

        val result = assertNotNull(resolvePluginArgs(config, bogusPaths, operationalExitCode = 999).get())

        assertTrue(result.isEmpty())
    }

    @Test
    fun resolvePluginArgsAllDisabledReturnsEmptyWithoutTouchingTheNetwork() {
        val config = testConfig(
            plugins = mapOf("serialization" to false, "allopen" to false),
        )

        val result = assertNotNull(resolvePluginArgs(config, bogusPaths, operationalExitCode = 999).get())

        assertTrue(result.isEmpty())
    }

    @Test
    fun resolvePluginJarsMapNoPluginsReturnsEmptyMap() {
        val config = testConfig(plugins = emptyMap())

        val result = assertNotNull(resolvePluginJarsMap(config, bogusPaths, operationalExitCode = 999).get())

        assertEquals(emptyMap(), result)
    }

    @Test
    fun resolvePluginJarsMapAllDisabledReturnsEmptyMap() {
        val config = testConfig(
            plugins = mapOf("serialization" to false, "allopen" to false),
        )

        val result = assertNotNull(resolvePluginJarsMap(config, bogusPaths, operationalExitCode = 999).get())

        assertEquals(emptyMap(), result)
    }

    @Test
    fun resolvePluginArgsNativeTargetSharesTheSameShortCircuit() {
        val config = testConfig(plugins = emptyMap(), target = "linuxX64")

        val result = assertNotNull(resolvePluginArgs(config, bogusPaths, operationalExitCode = 999).get())

        assertTrue(result.isEmpty())
    }

    // Config mistakes (unknown alias, 404) always EXIT_CONFIG_ERROR;
    // transient failures propagate the caller's operational exit code.

    @Test
    fun mapUnknownPluginIsConfigError() {
        val exit = mapPluginFetchErrorToExit(
            PluginFetchError.UnknownPlugin("fictitious"),
            operationalExitCode = 999,
        )
        assertEquals(EXIT_CONFIG_ERROR, exit.exitCode)
        assertTrue(exit.message.contains("fictitious"))
    }

    @Test
    fun mapHttp404DownloadFailureIsConfigErrorAndNamesTheKotlinPin() {
        val exit = mapPluginFetchErrorToExit(
            PluginFetchError.DownloadFailed(
                alias = "serialization",
                url = "https://repo1.maven.org/maven2/.../kotlin-serialization-compiler-plugin-99.0.0.jar",
                cause = DownloadError.HttpFailed(
                    url = "https://repo1.maven.org/maven2/.../kotlin-serialization-compiler-plugin-99.0.0.jar",
                    statusCode = 404,
                ),
            ),
            operationalExitCode = 999,
        )
        assertEquals(EXIT_CONFIG_ERROR, exit.exitCode)
        assertTrue(exit.message.contains("not found"))
        assertTrue(
            exit.message.contains("kolt.toml"),
            "404 message should point at kolt.toml, got: ${exit.message}",
        )
    }

    @Test
    fun mapHttpNon404DownloadFailureIsOperational() {
        val exit = mapPluginFetchErrorToExit(
            PluginFetchError.DownloadFailed(
                alias = "serialization",
                url = "https://repo1.maven.org/.../serialization.jar",
                cause = DownloadError.HttpFailed(
                    url = "https://repo1.maven.org/.../serialization.jar",
                    statusCode = 503,
                ),
            ),
            operationalExitCode = 77,
        )
        assertEquals(77, exit.exitCode)
        assertTrue(exit.message.contains("HTTP 503"))
    }

    @Test
    fun mapNetworkDownloadFailureIsOperational() {
        val exit = mapPluginFetchErrorToExit(
            PluginFetchError.DownloadFailed(
                alias = "serialization",
                url = "https://repo1.maven.org/.../serialization.jar",
                cause = DownloadError.NetworkError(
                    url = "https://repo1.maven.org/.../serialization.jar",
                    message = "connection refused",
                ),
            ),
            operationalExitCode = 77,
        )
        assertEquals(77, exit.exitCode)
        assertTrue(exit.message.contains("connection refused"))
    }

    @Test
    fun mapCacheDirCreationFailureIsOperational() {
        val exit = mapPluginFetchErrorToExit(
            PluginFetchError.CacheDirCreationFailed("/home/u/.kolt/cache/org/jetbrains/kotlin/foo"),
            operationalExitCode = 42,
        )
        assertEquals(42, exit.exitCode)
        assertTrue(exit.message.contains("/home/u/.kolt/cache/org/jetbrains/kotlin/foo"))
    }

    @Test
    fun mapHashComputationFailureIsOperational() {
        val exit = mapPluginFetchErrorToExit(
            PluginFetchError.HashComputationFailed("/home/u/.kolt/cache/.../foo.jar"),
            operationalExitCode = 42,
        )
        assertEquals(42, exit.exitCode)
        assertTrue(exit.message.contains("sha256"))
    }

    @Test
    fun mapStampWriteFailureIsOperational() {
        val exit = mapPluginFetchErrorToExit(
            PluginFetchError.StampWriteFailed("/home/u/.kolt/cache/.../foo.jar.sha256"),
            operationalExitCode = 42,
        )
        assertEquals(42, exit.exitCode)
        assertTrue(exit.message.contains("stamp"))
    }
}
