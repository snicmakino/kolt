package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltPaths
import kolt.infra.listJarFiles


internal data class DaemonSetup(
    val javaBin: String,
    val daemonJarPath: String,
    val compilerJars: List<String>,
    val btaImplJars: List<String>,
    val daemonDir: String,
    val socketPath: String,
    val logPath: String,
)

// None of these are build failures — ADR 0016 §5.
internal sealed interface DaemonPreconditionError {
    data class BootstrapJdkInstallFailed(
        val jdkInstallDir: String,
        val cause: String,
    ) : DaemonPreconditionError

    data object DaemonJarMissing : DaemonPreconditionError

    data class CompilerJarsMissing(val kotlincLibDir: String) : DaemonPreconditionError

    data class BtaImplJarsMissing(val probedDir: String) : DaemonPreconditionError
}

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
