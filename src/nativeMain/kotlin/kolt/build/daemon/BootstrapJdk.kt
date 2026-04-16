package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.config.KoltPaths
import kolt.infra.eprintln
import kolt.infra.fileExists
import kolt.tool.ToolchainError
import kolt.tool.installJdkToolchain

// Independent of the project's JDK — see ADR 0017.
const val BOOTSTRAP_JDK_VERSION: String = "21"

internal data class BootstrapJdkError(
    val jdkInstallDir: String,
    val cause: ToolchainError,
)

internal fun resolveBootstrapJavaBin(paths: KoltPaths): String? {
    val javaBin = paths.javaBin(BOOTSTRAP_JDK_VERSION)
    return if (fileExists(javaBin)) javaBin else null
}

// Progress goes to stderr so it does not interleave with build stdout.
internal fun ensureBootstrapJavaBin(
    paths: KoltPaths,
    resolve: (KoltPaths) -> String? = ::resolveBootstrapJavaBin,
    install: (String, KoltPaths) -> Result<Unit, ToolchainError> =
        { v, p -> installJdkToolchain(v, p, progressSink = ::eprintln) },
): Result<String, BootstrapJdkError> {
    resolve(paths)?.let { return Ok(it) }

    val installDir = paths.jdkPath(BOOTSTRAP_JDK_VERSION)
    install(BOOTSTRAP_JDK_VERSION, paths).getOrElse { err ->
        return Err(BootstrapJdkError(installDir, err))
    }

    val javaBin = resolve(paths)
        ?: return Err(
            BootstrapJdkError(
                installDir,
                ToolchainError("java binary not found at ${paths.javaBin(BOOTSTRAP_JDK_VERSION)} after installation"),
            ),
        )
    return Ok(javaBin)
}
