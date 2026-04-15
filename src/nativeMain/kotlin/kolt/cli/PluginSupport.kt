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

// Production wiring for the plugin jar fetcher's I/O seam. Every method
// is a passthrough to the matching `kolt.infra` helper — isolated in one
// place so unit tests can substitute a fake, and so the fetcher has a
// single "production" construction site.
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

// Resolve every enabled `kolt.toml [plugins]` entry to its cached jar
// path as a flat `alias → path` map. Callers that need the derived
// shapes (`-Xplugin=…` strings or the daemon-shaped
// `alias → List<path>`) should call this once and map the result so a
// rebuild does not pay the file-exists + re-hash cost per enabled
// plugin twice. Errors: an unknown alias is a user config bug
// (`EXIT_CONFIG_ERROR`); any other failure (download, mkdir, hash,
// stamp write) is an operational error pinned to the caller-provided
// [operationalExitCode] so `build` and `test` paths can distinguish
// their own exit statuses.
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

// #65 review findings #3 + #8: the error-to-exit translation is a pure
// function so every branch (including the HTTP 404 → EXIT_CONFIG_ERROR
// special case) is unit-testable without process exit. The wrapper
// above handles the eprintln + exitProcess side effects; every test
// that wants to pin the mapping calls this function directly.
//
// Mapping rules:
// - UnknownPlugin → config bug (EXIT_CONFIG_ERROR). User mistyped an
//   alias in `kolt.toml [plugins]`.
// - DownloadFailed / HttpFailed 404 → config bug. A 404 on a stable
//   `org.jetbrains.kotlin:kotlin-*-compiler-plugin:<ver>` coordinate
//   effectively always means the `kotlin = "<ver>"` pin in `kolt.toml`
//   is wrong (or a rare JetBrains un-publish, which is indistinguishable
//   from the user's perspective and still requires a config edit).
// - DownloadFailed / other → operational. Network / write / TLS errors
//   are not the user's fault and should propagate as the caller's
//   operational exit code (build vs test).
// - Cache dir / hash / stamp failures → operational. Local filesystem
//   failures bubble up the same way the resolver treats them.
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

// #65: derive `-Xplugin=<jar>` arguments for kotlinc / konanc subprocess
// calls. Used by the JVM subprocess fallback path and the native konanc
// build / test paths — none of which talk to the warm daemon.
internal fun resolvePluginArgs(
    config: KoltConfig,
    paths: KoltPaths,
    exitCode: Int,
): List<String> =
    resolveEnabledPluginJarsOrExit(config, paths, exitCode).values.map { "-Xplugin=$it" }

// #65 daemon path: alias → jar classpath (single-element list per alias
// today, since plugin jars are shaded singletons). Consumed by
// `resolveCompilerBackend(pluginJars=…)` → `DaemonCompilerBackend`'s
// `--plugin-jars` argv builder. The list shape is forward-compatible
// with a future plugin that ships multiple jars.
internal fun resolvePluginJarsMap(
    config: KoltConfig,
    paths: KoltPaths,
    exitCode: Int,
): Map<String, List<String>> =
    resolveEnabledPluginJarsOrExit(config, paths, exitCode).mapValues { (_, path) -> listOf(path) }
