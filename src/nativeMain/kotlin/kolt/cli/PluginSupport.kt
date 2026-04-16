package kolt.cli

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltConfig
import kolt.config.KoltPaths
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kolt.infra.WriteFailed
import kolt.infra.eprintln
import kolt.resolve.PluginFetchError
import kolt.resolve.PluginFetcherDeps
import kolt.resolve.fetchEnabledPluginJars
import kotlin.system.exitProcess

private fun defaultPluginFetcherDeps(): PluginFetcherDeps = object : PluginFetcherDeps {
    override fun fileExists(path: String): Boolean = kolt.infra.fileExists(path)
    override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> =
        kolt.infra.ensureDirectoryRecursive(path)
    override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> =
        kolt.infra.downloadFile(url, destPath)
    override fun computeSha256(filePath: String): Result<String, Sha256Error> =
        kolt.infra.computeSha256(filePath)
    override fun readFileAsString(path: String): Result<String, OpenFailed> =
        kolt.infra.readFileAsString(path)
    override fun writeFileAsString(path: String, content: String): Result<Unit, WriteFailed> =
        kolt.infra.writeFileAsString(path, content)
    override fun warn(message: String) = eprintln("warning: $message")
}

internal fun resolveEnabledPluginJarPaths(
    config: KoltConfig,
    paths: KoltPaths,
    operationalExitCode: Int,
): Map<String, String> = resolveEnabledPluginJarsOrExit(config, paths, operationalExitCode)

private fun resolveEnabledPluginJarsOrExit(
    config: KoltConfig,
    paths: KoltPaths,
    operationalExitCode: Int,
): Map<String, String> {
    return fetchEnabledPluginJars(
        plugins = config.plugins,
        kotlinVersion = config.kotlin,
        cacheBase = paths.cacheBase,
        deps = defaultPluginFetcherDeps(),
    ).getOrElse { err ->
        val exit = mapPluginFetchErrorToExit(err, operationalExitCode)
        eprintln("error: ${exit.message}")
        exitProcess(exit.exitCode)
    }
}

// 404 on a plugin jar → EXIT_CONFIG_ERROR (bad kotlin version in kolt.toml).
internal data class PluginFetchExit(val exitCode: Int, val message: String)

internal fun mapPluginFetchErrorToExit(
    err: PluginFetchError,
    operationalExitCode: Int,
): PluginFetchExit = when (err) {
    is PluginFetchError.UnknownPlugin ->
        PluginFetchExit(EXIT_CONFIG_ERROR, "unknown plugin '${err.alias}'")
    is PluginFetchError.DownloadFailed -> {
        val cause = err.cause
        if (cause is DownloadError.HttpFailed && cause.statusCode == 404) {
            PluginFetchExit(
                EXIT_CONFIG_ERROR,
                "compiler plugin '${err.alias}' not found at ${err.url} — " +
                    "check `kotlin = \"...\"` in kolt.toml",
            )
        } else {
            PluginFetchExit(
                operationalExitCode,
                "failed to download compiler plugin '${err.alias}' from ${err.url}: " +
                    formatDownloadError(cause),
            )
        }
    }
    is PluginFetchError.CacheDirCreationFailed ->
        PluginFetchExit(operationalExitCode, "could not create plugin cache directory ${err.path}")
    is PluginFetchError.HashComputationFailed ->
        PluginFetchExit(operationalExitCode, "could not compute sha256 of plugin jar ${err.path}")
    is PluginFetchError.StampWriteFailed ->
        PluginFetchExit(operationalExitCode, "could not write plugin jar stamp ${err.path}")
}

private fun formatDownloadError(err: DownloadError): String = when (err) {
    is DownloadError.HttpFailed -> "HTTP ${err.statusCode}"
    is DownloadError.NetworkError -> err.message
    is DownloadError.WriteFailed -> "write failed to ${err.path}"
}

internal fun resolvePluginArgs(
    config: KoltConfig,
    paths: KoltPaths,
    exitCode: Int,
): List<String> =
    resolveEnabledPluginJarsOrExit(config, paths, exitCode).values.map { "-Xplugin=$it" }

internal fun resolvePluginJarsMap(
    config: KoltConfig,
    paths: KoltPaths,
    exitCode: Int,
): Map<String, List<String>> =
    resolveEnabledPluginJarsOrExit(config, paths, exitCode).mapValues { (_, path) -> listOf(path) }
