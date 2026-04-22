package kolt.build.nativedaemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.build.daemon.BootstrapJdkError
import kolt.build.daemon.ensureBootstrapJavaBin
import kolt.build.daemon.projectHashOf
import kolt.config.KoltPaths
import kolt.infra.fileExists as infraFileExists
import kolt.resolve.compareVersions

// Parallel to `kolt.build.daemon.DaemonSetup` but tailored for ADR 0024:
// no compilerJars list (one jar is passed to the daemon via `--konanc-jar`),
// no btaImplJars (BTA has no native equivalent per §6), a distinct socket
// filename (`native-compiler-daemon.sock`) co-located under the same project/version
// directory as the JVM daemon so `daemon stop` enumerates both in one pass.
internal data class NativeDaemonSetup(
    val javaBin: String,
    val daemonJarPath: String,
    val konancJar: String,
    val konanHome: String,
    val daemonDir: String,
    val socketPath: String,
    val logPath: String,
)

// Shape of spike #166: K2Native's reflective CLI surface
// (`CLICompiler.exec(PrintStream, String[])`) was validated at 100
// invocations on Kotlin 2.3.x with no state leakage. Earlier 2.2.x and
// below have different `K2Native` internals and are not exercised on
// this path. Unlike the JVM daemon's floor (ADR 0022, BTA-impl V1 shim
// classloader split), the native floor is about K2Native version drift,
// not BTA — there is no BTA for native (ADR 0024 §6).
internal const val NATIVE_KOTLIN_VERSION_FLOOR = "2.3.0"

// Linux AF_UNIX sun_path capacity. Fail fast if the projected socket path
// would overflow; the native daemon does not apply a plugin-fingerprint
// suffix (ADR 0024 §6: no daemon-side plugin plumbing), so the projected
// path is simply the raw socket path. Same rationale as the JVM daemon
// precondition minus the fingerprint over-estimate.
private const val NATIVE_SUN_PATH_CAPACITY: Int = 108

// `konan/lib/kotlin-native-compiler-embeddable.jar` is the single jar the
// daemon loads reflectively (ADR 0024 §8). It sits under the managed
// konanc toolchain root next to `bin/konanc`.
private const val KONANC_EMBEDDABLE_RELATIVE_PATH = "konan/lib/kotlin-native-compiler-embeddable.jar"

// None of these are build failures — ADR 0024 §7.
internal sealed interface NativeDaemonPreconditionError {
    data class BootstrapJdkInstallFailed(
        val jdkInstallDir: String,
        val cause: String,
    ) : NativeDaemonPreconditionError

    data object NativeDaemonJarMissing : NativeDaemonPreconditionError

    // The managed konanc install is missing its embeddable jar — usually
    // an incomplete download or a manual delete. Subprocess still works
    // because it invokes the konanc shell script, not the jar.
    data class KonancJarMissing(val path: String) : NativeDaemonPreconditionError

    data class KotlinVersionBelowFloor(
        val requested: String,
        val floor: String,
    ) : NativeDaemonPreconditionError

    data class SocketPathTooLong(
        val socketPath: String,
        val projectedBytes: Int,
        val maxBytes: Int,
    ) : NativeDaemonPreconditionError
}

internal fun resolveNativeDaemonPreconditions(
    paths: KoltPaths,
    kotlincVersion: String,
    absProjectPath: String,
    ensureJavaBin: (KoltPaths) -> Result<String, BootstrapJdkError> = ::ensureBootstrapJavaBin,
    resolveNativeDaemonJar: () -> NativeDaemonJarResolution = ::resolveNativeDaemonJar,
    fileExists: (String) -> Boolean = ::infraFileExists,
): Result<NativeDaemonSetup, NativeDaemonPreconditionError> {
    // No `bundledKotlinVersion` parameter here — unlike the JVM resolver,
    // nothing on the native path branches on bundled vs non-bundled today
    // (no BTA fetch per ADR 0024 §6). Add it back when a caller needs it.
    if (compareVersions(kotlincVersion, NATIVE_KOTLIN_VERSION_FLOOR) < 0) {
        return Err(
            NativeDaemonPreconditionError.KotlinVersionBelowFloor(
                requested = kotlincVersion,
                floor = NATIVE_KOTLIN_VERSION_FLOOR,
            ),
        )
    }

    val projectHash = projectHashOf(absProjectPath)
    val socketPath = paths.nativeDaemonSocketPath(projectHash, kotlincVersion)
    if (socketPath.length > NATIVE_SUN_PATH_CAPACITY) {
        return Err(
            NativeDaemonPreconditionError.SocketPathTooLong(
                socketPath = socketPath,
                projectedBytes = socketPath.length,
                maxBytes = NATIVE_SUN_PATH_CAPACITY,
            ),
        )
    }

    val javaBin = ensureJavaBin(paths).getOrElse { err ->
        return Err(
            NativeDaemonPreconditionError.BootstrapJdkInstallFailed(
                jdkInstallDir = err.jdkInstallDir,
                cause = err.cause.message,
            ),
        )
    }

    val daemonJar = when (val res = resolveNativeDaemonJar()) {
        is NativeDaemonJarResolution.Resolved -> res.path
        NativeDaemonJarResolution.NotFound -> return Err(NativeDaemonPreconditionError.NativeDaemonJarMissing)
    }

    val konanHome = paths.konancPath(kotlincVersion)
    val konancJar = "$konanHome/$KONANC_EMBEDDABLE_RELATIVE_PATH"
    if (!fileExists(konancJar)) {
        return Err(NativeDaemonPreconditionError.KonancJarMissing(konancJar))
    }

    return Ok(
        NativeDaemonSetup(
            javaBin = javaBin,
            daemonJarPath = daemonJar,
            konancJar = konancJar,
            konanHome = konanHome,
            daemonDir = paths.daemonDir(projectHash, kotlincVersion),
            socketPath = socketPath,
            logPath = paths.nativeDaemonLogPath(projectHash, kotlincVersion),
        ),
    )
}

internal fun formatNativeDaemonPreconditionWarning(err: NativeDaemonPreconditionError): String = when (err) {
    is NativeDaemonPreconditionError.BootstrapJdkInstallFailed ->
        "warning: could not install bootstrap JDK at ${err.jdkInstallDir} (${err.cause}) — falling back to subprocess compile"
    NativeDaemonPreconditionError.NativeDaemonJarMissing ->
        "warning: kolt-native-compiler-daemon jar not found — falling back to subprocess compile"
    is NativeDaemonPreconditionError.KonancJarMissing ->
        "warning: konanc embeddable jar not found at ${err.path} — falling back to subprocess compile"
    is NativeDaemonPreconditionError.KotlinVersionBelowFloor ->
        "warning: kolt native daemon supports Kotlin >= ${err.floor}; your kolt.toml requests ${err.requested} — " +
            "falling back to subprocess compile. Pass --no-daemon to silence this warning."
    is NativeDaemonPreconditionError.SocketPathTooLong ->
        "warning: native daemon socket path ${err.socketPath} would be ${err.projectedBytes} bytes, " +
            "exceeding the Linux AF_UNIX ${err.maxBytes}-byte limit — falling back to subprocess compile. " +
            "Move the project under a shorter \$HOME, or pass --no-daemon to silence this warning."
}
