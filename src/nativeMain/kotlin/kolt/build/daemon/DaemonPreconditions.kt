package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltPaths
import kolt.infra.listJarFiles
import kolt.resolve.compareVersions
import kolt.resolve.defaultResolverDeps


internal data class DaemonSetup(
    val javaBin: String,
    val daemonJarPath: String,
    val compilerJars: List<String>,
    val btaImplJars: List<String>,
    val daemonDir: String,
    val socketPath: String,
    val logPath: String,
)

// Soft floor for daemon-supported Kotlin versions per ADR 0022. The 2.3.x
// family shares one binary-compat surface (verified by spike #138); 2.2.x
// and earlier crash at `KotlinToolchains.loadImplementation` because their
// V1 compat shim lives in `internal.compat.*`, outside what the daemon's
// SharedApiClassesClassLoader exposes.
internal const val KOTLIN_VERSION_FLOOR = "2.3.0"

// BTA's structured `COMPILER_PLUGINS` argument key was introduced in 2.3.20.
// Before that, the impl rejects the key even with an empty list, so a
// project that actually has plugins configured cannot run on the daemon
// against a pre-2.3.20 BTA-impl. The gap is recoverable in principle via
// `freeArgs` passthrough (`-Xplugin=...`) but that path is unimplemented;
// for now, plugin-using projects on 2.3.0–2.3.10 take the subprocess
// fallback rail with a dedicated warning. Tracked as a #138 follow-up.
internal const val KOTLIN_VERSION_FOR_PLUGINS = "2.3.20"

// None of these are build failures — ADR 0016 §5.
internal sealed interface DaemonPreconditionError {
    data class BootstrapJdkInstallFailed(
        val jdkInstallDir: String,
        val cause: String,
    ) : DaemonPreconditionError

    data object DaemonJarMissing : DaemonPreconditionError

    data class CompilerJarsMissing(val kotlincLibDir: String) : DaemonPreconditionError

    data class BtaImplJarsMissing(val probedDir: String) : DaemonPreconditionError

    // ADR 0022: the daemon adapter is compiled against BTA-API 2.3.x.
    // Below the floor, no fetch can rescue us — the impl jar's V1 compat
    // shim lives in `internal.compat.*`, which the daemon classloader
    // hierarchy does not share.
    data class KotlinVersionBelowFloor(
        val requested: String,
        val floor: String,
    ) : DaemonPreconditionError

    data class BtaImplFetchFailed(
        val version: String,
        val cause: BtaImplFetchError,
    ) : DaemonPreconditionError

    // Plugins requested but the requested Kotlin version pre-dates
    // BTA's structured COMPILER_PLUGINS argument (2.3.20). See the
    // KOTLIN_VERSION_FOR_PLUGINS const for context.
    data class PluginsRequireMinKotlinVersion(
        val requested: String,
        val minVersion: String,
    ) : DaemonPreconditionError
}

internal fun resolveDaemonPreconditions(
    paths: KoltPaths,
    kotlincVersion: String,
    absProjectPath: String,
    bundledKotlinVersion: String,
    pluginsRequested: Boolean = false,
    ensureJavaBin: (KoltPaths) -> Result<String, BootstrapJdkError> = ::ensureBootstrapJavaBin,
    resolveDaemonJar: () -> DaemonJarResolution = ::resolveDaemonJar,
    listCompilerJars: (String) -> List<String>? = { dir -> listJarFiles(dir).getOrElse { null } },
    resolveBundledBtaImplJars: () -> BtaImplJarsResolution = ::resolveBtaImplJars,
    fetchBtaImplJars: (String, String) -> Result<List<String>, BtaImplFetchError> =
        { v, base -> ensureBtaImplJars(v, base, defaultResolverDeps()) },
): Result<DaemonSetup, DaemonPreconditionError> {
    // Floor check first: cheaper than any disk probe and short-circuits
    // every variant the daemon cannot serve. Equal to or above 2.3.0
    // counts as in-family per ADR 0022 §3.
    if (compareVersions(kotlincVersion, KOTLIN_VERSION_FLOOR) < 0) {
        return Err(
            DaemonPreconditionError.KotlinVersionBelowFloor(
                requested = kotlincVersion,
                floor = KOTLIN_VERSION_FLOOR,
            ),
        )
    }

    if (pluginsRequested && compareVersions(kotlincVersion, KOTLIN_VERSION_FOR_PLUGINS) < 0) {
        return Err(
            DaemonPreconditionError.PluginsRequireMinKotlinVersion(
                requested = kotlincVersion,
                minVersion = KOTLIN_VERSION_FOR_PLUGINS,
            ),
        )
    }

    val javaBin = ensureJavaBin(paths).getOrElse { err ->
        return Err(
            DaemonPreconditionError.BootstrapJdkInstallFailed(
                jdkInstallDir = err.jdkInstallDir,
                cause = err.cause.message,
            ),
        )
    }

    val daemonJar = when (val res = resolveDaemonJar()) {
        is DaemonJarResolution.Resolved -> res.path
        DaemonJarResolution.NotFound -> return Err(DaemonPreconditionError.DaemonJarMissing)
    }

    val kotlincLibDir = "${paths.kotlincPath(kotlincVersion)}/lib"
    val compilerJars = listCompilerJars(kotlincLibDir)
    if (compilerJars.isNullOrEmpty()) {
        return Err(DaemonPreconditionError.CompilerJarsMissing(kotlincLibDir))
    }

    // Bundled-version fast path: a fresh kolt install ships the matching
    // BTA-impl classpath under <prefix>/libexec/kolt-bta-impl/, so a
    // first build at the bundled version pays no Maven Central round
    // trip. Other 2.3.x patches resolve through the fetcher.
    val btaImplJars = if (kotlincVersion == bundledKotlinVersion) {
        when (val res = resolveBundledBtaImplJars()) {
            is BtaImplJarsResolution.Resolved -> res.jars
            is BtaImplJarsResolution.NotFound ->
                return Err(DaemonPreconditionError.BtaImplJarsMissing(res.probedDir))
        }
    } else {
        fetchBtaImplJars(kotlincVersion, paths.cacheBase).getOrElse {
            return Err(DaemonPreconditionError.BtaImplFetchFailed(kotlincVersion, it))
        }
    }

    val projectHash = projectHashOf(absProjectPath)
    return Ok(
        DaemonSetup(
            javaBin = javaBin,
            daemonJarPath = daemonJar,
            compilerJars = compilerJars,
            btaImplJars = btaImplJars,
            daemonDir = paths.daemonDir(projectHash, kotlincVersion),
            socketPath = paths.daemonSocketPath(projectHash, kotlincVersion),
            logPath = paths.daemonLogPath(projectHash, kotlincVersion),
        ),
    )
}

internal fun formatDaemonPreconditionWarning(err: DaemonPreconditionError): String = when (err) {
    is DaemonPreconditionError.BootstrapJdkInstallFailed ->
        "warning: could not install bootstrap JDK at ${err.jdkInstallDir} (${err.cause}) — falling back to subprocess compile"
    DaemonPreconditionError.DaemonJarMissing ->
        "warning: kolt-compiler-daemon jar not found — falling back to subprocess compile"
    is DaemonPreconditionError.CompilerJarsMissing ->
        "warning: no compiler jars found in ${err.kotlincLibDir} — falling back to subprocess compile"
    is DaemonPreconditionError.BtaImplJarsMissing ->
        "warning: kotlin-build-tools-impl jars not found in ${err.probedDir} — falling back to subprocess compile"
    is DaemonPreconditionError.KotlinVersionBelowFloor ->
        "warning: kolt daemon supports Kotlin >= ${err.floor}; your kolt.toml requests ${err.requested} — " +
            "falling back to subprocess compile. Pass --no-daemon to silence this warning."
    is DaemonPreconditionError.BtaImplFetchFailed ->
        "warning: could not fetch kotlin-build-tools-impl ${err.version} from Maven Central " +
            "(${formatBtaImplFetchError(err.cause)}) — falling back to subprocess compile"
    is DaemonPreconditionError.PluginsRequireMinKotlinVersion ->
        "warning: kolt.toml uses compiler plugins which require Kotlin >= ${err.minVersion} on the daemon path; " +
            "your kolt.toml requests ${err.requested} — falling back to subprocess compile. " +
            "Pass --no-daemon to silence this warning."
}

private fun formatBtaImplFetchError(err: BtaImplFetchError): String = when (err) {
    is BtaImplFetchError.ResolveFailed -> err.cause.toString()
    is BtaImplFetchError.ResolvedEmpty -> "resolver returned no jars"
}
