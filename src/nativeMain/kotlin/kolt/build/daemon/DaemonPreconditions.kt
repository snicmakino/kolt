package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltPaths
import kolt.infra.listJarFiles


// Everything `DaemonCompilerBackend` needs to try to talk to a warm
// daemon. Collected into one value so doBuild() can decide in a single
// branch whether the daemon path is reachable for this build.
//
// [daemonDir] is the per-project state directory that holds the socket
// file and the daemon log. It is carried alongside [socketPath] so
// wiring code does not have to re-derive the directory by string
// slicing the socket path — a fragile inverse of `KoltPaths.daemonDir`
// / `daemonSocketPath`.
internal data class DaemonSetup(
    val javaBin: String,
    val daemonJarPath: String,
    val compilerJars: List<String>,
    // kotlin-build-tools-impl jars shipped alongside the daemon. Required by the
    // Phase B incremental compile path (ADR 0019 §3): the daemon spawns with
    // these on the `--bta-impl-jars` CLI flag and loads them reflectively
    // through a child URLClassLoader parented by `SharedApiClassesClassLoader`,
    // keeping daemon-core classes free of @ExperimentalBuildToolsApi types.
    // Existence is checked at precondition time, but jar *content* is not —
    // a corrupted or version-mismatched -impl jar is a daemon-side failure
    // surfaced through `IcError.InternalError` and retried via
    // `FallbackCompilerBackend` per ADR 0016 §5.
    val btaImplJars: List<String>,
    val daemonDir: String,
    val socketPath: String,
    val logPath: String,
)

// Reasons the daemon path is not reachable for this build. Each
// variant maps to a one-line warning the user sees once, after which
// the build falls back to the subprocess compile path. None of these
// are build failures — ADR 0016 §5 guarantees the daemon is never
// load-bearing for correctness.
internal sealed interface DaemonPreconditionError {
    // Auto-install of the bootstrap JDK failed (download, checksum,
    // extract, or a leftover partial install under the probed
    // directory). Carries the probed directory so the warning can
    // name the exact path kolt tried to populate, plus the underlying
    // cause message so the warning tells the user *why* it failed
    // (HTTP status, offline, disk full, …) rather than just "missing".
    // ADR 0017 §Decision (revised) describes this auto-install path.
    data class BootstrapJdkInstallFailed(
        val jdkInstallDir: String,
        val cause: String,
    ) : DaemonPreconditionError

    // The `kolt-compiler-daemon-all.jar` was not found in any of the
    // locations [resolveDaemonJar] probes (env override, libexec
    // co-location, dev fallback). Not a user misconfig: it means
    // kolt itself was not installed with a daemon jar beside it.
    data object DaemonJarMissing : DaemonPreconditionError

    // The kotlinc lib directory either does not exist or contains no
    // .jar files, so there is nothing to load into the daemon's
    // compiler classloader. Includes the directory so the warning can
    // point at the exact path the user needs to repair.
    data class CompilerJarsMissing(val kotlincLibDir: String) : DaemonPreconditionError

    // kotlin-build-tools-impl jars could not be located. The Phase B
    // incremental compile path needs them on disk so the daemon can
    // load them through a URLClassLoader (ADR 0019 §3). Ships via
    // `libexec/kolt-bta-impl/` at install time, or the dev-fallback
    // directory `<repo>/kolt-compiler-daemon/build/bta-impl-jars/`
    // populated by the `stageBtaImplJars` Gradle task. Includes the
    // probed directory so the warning points at the exact path to
    // repair — identical shape to CompilerJarsMissing.
    data class BtaImplJarsMissing(val probedDir: String) : DaemonPreconditionError
}

/**
 * Collects the inputs [DaemonCompilerBackend] needs and hands back
 * either a complete [DaemonSetup] or the first missing piece. This is
 * the single point where the daemon path is either "available" or
 * "not available for this build" — doBuild() branches on the result
 * and picks [kolt.build.FallbackCompilerBackend] or a plain
 * [kolt.build.SubprocessCompilerBackend] accordingly.
 *
 * Seams ([ensureJavaBin], [resolveDaemonJar], [listCompilerJars]) are
 * parameterised so unit tests can drive every variant of
 * [DaemonPreconditionError] without touching the real filesystem.
 * Defaults wire up the production helpers; production callers pass
 * only [paths], [kotlincVersion], and [absProjectPath]. [ensureJavaBin]
 * is the auto-installing [ensureBootstrapJavaBin] — on a clean first
 * run it downloads the pinned JDK synchronously before returning; on
 * failure the daemon path degrades to the subprocess backend per
 * ADR 0016 §5, never killing the build.
 */
internal fun resolveDaemonPreconditions(
    paths: KoltPaths,
    kotlincVersion: String,
    absProjectPath: String,
    ensureJavaBin: (KoltPaths) -> Result<String, BootstrapJdkError> = ::ensureBootstrapJavaBin,
    resolveDaemonJar: () -> DaemonJarResolution = ::resolveDaemonJar,
    listCompilerJars: (String) -> List<String>? = { dir -> listJarFiles(dir).getOrElse { null } },
    resolveBtaImplJars: () -> BtaImplJarsResolution = ::resolveBtaImplJars,
): Result<DaemonSetup, DaemonPreconditionError> {
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

    val btaImplJars = when (val res = resolveBtaImplJars()) {
        is BtaImplJarsResolution.Resolved -> res.jars
        is BtaImplJarsResolution.NotFound ->
            return Err(DaemonPreconditionError.BtaImplJarsMissing(res.probedDir))
    }

    val projectHash = projectHashOf(absProjectPath)
    return Ok(
        DaemonSetup(
            javaBin = javaBin,
            daemonJarPath = daemonJar,
            compilerJars = compilerJars,
            btaImplJars = btaImplJars,
            daemonDir = paths.daemonDir(projectHash),
            socketPath = paths.daemonSocketPath(projectHash),
            logPath = paths.daemonLogPath(projectHash),
        ),
    )
}

/**
 * One-line stderr warning for a precondition failure. Kept next to
 * the error enum so every new variant is forced to supply wording at
 * the same time. For [DaemonPreconditionError.BootstrapJdkInstallFailed]
 * the wording now includes the underlying cause (HTTP status, offline,
 * disk full, …) so a user staring at "slow builds" knows whether to
 * retry online, free up disk, or investigate a proxy. ADR 0017
 * §Decision (revised) made the auto-install path default-on.
 */
internal fun formatDaemonPreconditionWarning(err: DaemonPreconditionError): String = when (err) {
    is DaemonPreconditionError.BootstrapJdkInstallFailed ->
        "warning: could not install bootstrap JDK at ${err.jdkInstallDir} (${err.cause}) — falling back to subprocess compile"
    DaemonPreconditionError.DaemonJarMissing ->
        "warning: kolt-compiler-daemon jar not found — falling back to subprocess compile"
    is DaemonPreconditionError.CompilerJarsMissing ->
        "warning: no compiler jars found in ${err.kotlincLibDir} — falling back to subprocess compile"
    is DaemonPreconditionError.BtaImplJarsMissing ->
        "warning: kotlin-build-tools-impl jars not found in ${err.probedDir} — falling back to subprocess compile"
}
