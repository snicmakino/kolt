package kolt.cli

import kolt.config.KoltPaths
import kolt.infra.DownloadError
import kolt.resolve.PluginFetchError
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// After #65 the plugin jar fetcher is the single source of truth for
// compiler plugin jars on every compile path. `resolvePluginArgs` /
// `resolvePluginJarsMap` are thin wrappers that delegate to
// `fetchEnabledPluginJars` and translate errors into `exitProcess`
// calls, so unit tests can only cover the "no network I/O" short-
// circuit here — anything beyond that hits Maven Central and belongs
// in `PluginJarFetcherTest`.
//
// These tests use a bogus `KoltPaths("/nonexistent-…")` as a defence:
// if the short-circuit regresses and a zero-enabled-plugin build starts
// touching the network or the filesystem, the mkdir / download attempt
// under the bogus cache base will exit the test process and fail the
// test.
class PluginSupportTest {

    private val bogusPaths = KoltPaths("/nonexistent-home-for-test")

    @Test
    fun resolvePluginArgsNoPluginsReturnsEmptyWithoutTouchingTheNetwork() {
        val config = testConfig(plugins = emptyMap())

        val result = resolvePluginArgs(config, bogusPaths, exitCode = 999)

        assertTrue(result.isEmpty())
    }

    @Test
    fun resolvePluginArgsAllDisabledReturnsEmptyWithoutTouchingTheNetwork() {
        val config = testConfig(
            plugins = mapOf("serialization" to false, "allopen" to false),
        )

        val result = resolvePluginArgs(config, bogusPaths, exitCode = 999)

        assertTrue(result.isEmpty())
    }

    @Test
    fun resolvePluginJarsMapNoPluginsReturnsEmptyMap() {
        val config = testConfig(plugins = emptyMap())

        val result = resolvePluginJarsMap(config, bogusPaths, exitCode = 999)

        assertEquals(emptyMap(), result)
    }

    @Test
    fun resolvePluginJarsMapAllDisabledReturnsEmptyMap() {
        val config = testConfig(
            plugins = mapOf("serialization" to false, "allopen" to false),
        )

        val result = resolvePluginJarsMap(config, bogusPaths, exitCode = 999)

        assertEquals(emptyMap(), result)
    }

    // The native path used to have a separate `resolveNativePluginArgs`
    // that called `ensureKotlincBin` before resolving plugin args. That
    // split is gone: the native konanc path and the JVM subprocess
    // fallback both call the same `resolvePluginArgs` now, and neither
    // needs the kotlinc sidecar. `target = "native"` is intentionally
    // exercised here to pin that the short-circuit has no target-specific
    // branches left.
    @Test
    fun resolvePluginArgsNativeTargetSharesTheSameShortCircuit() {
        val config = testConfig(plugins = emptyMap(), target = "native")

        val result = resolvePluginArgs(config, bogusPaths, exitCode = 999)

        assertTrue(result.isEmpty())
    }

    // --- mapPluginFetchErrorToExit ---
    //
    // The error-to-exit translation is a pure function so every branch
    // is exercised here without the `exitProcess` wrapper. The tests
    // pin two invariants:
    // 1. User-facing config mistakes (unknown alias, 404 on a coordinate
    //    that kolt builds from `config.kotlin`) exit with
    //    `EXIT_CONFIG_ERROR` regardless of the caller's operational
    //    exit code. A `build` or `test` command that would otherwise
    //    return EXIT_BUILD_ERROR / EXIT_TEST_ERROR still returns
    //    EXIT_CONFIG_ERROR for these cases so wrapper scripts can
    //    distinguish "network / build failure, retry" from "your
    //    kolt.toml is wrong, fix it and re-run".
    // 2. Everything else propagates to the caller-provided operational
    //    code so `build` vs `test` can keep their own exit-status
    //    conventions.

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
        // A 404 on a `kolt-*-compiler-plugin:<ver>` coordinate means
        // `kotlin = "<ver>"` in kolt.toml was set to a version that
        // JetBrains never published. The error message must point the
        // user at the right knob — "change kolt.toml", not "retry
        // later".
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
