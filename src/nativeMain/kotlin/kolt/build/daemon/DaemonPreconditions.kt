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
    // The bootstrap JDK is not yet installed at the expected path
    // under ~/.kolt/toolchains/jdk/<BOOTSTRAP_JDK_VERSION>/bin/java.
    // Carries the probed directory so the warning can point at the
    // exact path the user needs to populate. Auto-install is future
    // work tracked in ADR 0017 Alternatives §5.
    data class BootstrapJdkMissing(val jdkInstallDir: String) : DaemonPreconditionError

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
}

/**
 * Collects the inputs [DaemonCompilerBackend] needs and hands back
 * either a complete [DaemonSetup] or the first missing piece. This is
 * the single point where the daemon path is either "available" or
 * "not available for this build" — doBuild() branches on the result
 * and picks [kolt.build.FallbackCompilerBackend] or a plain
 * [kolt.build.SubprocessCompilerBackend] accordingly.
 *
 * Seams ([resolveJavaBin], [resolveDaemonJar], [listCompilerJars]) are
 * parameterised so unit tests can drive every variant of
 * [DaemonPreconditionError] without touching the real filesystem.
 * Defaults wire up the production helpers; production callers pass
 * only [paths], [kotlincVersion], and [absProjectPath].
 */
internal fun resolveDaemonPreconditions(
    paths: KoltPaths,
    kotlincVersion: String,
    absProjectPath: String,
    resolveJavaBin: (KoltPaths) -> String? = ::resolveBootstrapJavaBin,
    resolveDaemonJar: () -> DaemonJarResolution = ::resolveDaemonJar,
    listCompilerJars: (String) -> List<String>? = { dir -> listJarFiles(dir).getOrElse { null } },
): Result<DaemonSetup, DaemonPreconditionError> {
    val javaBin = resolveJavaBin(paths)
        ?: return Err(DaemonPreconditionError.BootstrapJdkMissing(paths.jdkPath(BOOTSTRAP_JDK_VERSION)))

    val daemonJar = when (val res = resolveDaemonJar()) {
        is DaemonJarResolution.Resolved -> res.path
        DaemonJarResolution.NotFound -> return Err(DaemonPreconditionError.DaemonJarMissing)
    }

    val kotlincLibDir = "${paths.kotlincPath(kotlincVersion)}/lib"
    val compilerJars = listCompilerJars(kotlincLibDir)
    if (compilerJars.isNullOrEmpty()) {
        return Err(DaemonPreconditionError.CompilerJarsMissing(kotlincLibDir))
    }

    val projectHash = projectHashOf(absProjectPath)
    return Ok(
        DaemonSetup(
            javaBin = javaBin,
            daemonJarPath = daemonJar,
            compilerJars = compilerJars,
            daemonDir = paths.daemonDir(projectHash),
            socketPath = paths.daemonSocketPath(projectHash),
            logPath = paths.daemonLogPath(projectHash),
        ),
    )
}

/**
 * One-line stderr warning for a precondition failure. Kept next to
 * the error enum so every new variant is forced to supply wording at
 * the same time. The wording names the on-disk location kolt probed
 * rather than an imperative install command — auto-install of the
 * bootstrap JDK is deferred (ADR 0017 Alternatives §5) and there is
 * no dedicated one-liner a user can run yet. Acceptable regression
 * from pre-daemon kolt because the subprocess fallback preserves the
 * old ~8 s clean build behaviour exactly.
 */
internal fun formatDaemonPreconditionWarning(err: DaemonPreconditionError): String = when (err) {
    is DaemonPreconditionError.BootstrapJdkMissing ->
        "warning: bootstrap JDK not installed at ${err.jdkInstallDir} — falling back to subprocess compile"
    DaemonPreconditionError.DaemonJarMissing ->
        "warning: kolt-compiler-daemon jar not found — falling back to subprocess compile"
    is DaemonPreconditionError.CompilerJarsMissing ->
        "warning: no compiler jars found in ${err.kotlincLibDir} — falling back to subprocess compile"
}
