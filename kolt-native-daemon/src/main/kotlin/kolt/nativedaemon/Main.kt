package kolt.nativedaemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.mapBoth
import kolt.nativedaemon.compiler.ReflectiveK2NativeCompiler
import kolt.nativedaemon.compiler.SetupError
import kolt.nativedaemon.server.DaemonConfig
import kolt.nativedaemon.server.DaemonServer
import java.nio.file.Path
import kotlin.system.exitProcess

// Kotlin version pin for the native daemon. Must move in lockstep with the
// root `daemonKotlinVersion` in the top-level build.gradle.kts; the root
// `verifyDaemonKotlinVersion` task guards against drift. Mirrors
// `KOLT_DAEMON_KOTLIN_VERSION` in kolt-compiler-daemon/.../Main.kt.
internal const val KOLT_NATIVE_DAEMON_KOTLIN_VERSION: String = "2.3.20"

// ADR 0024 §8: --konanc-jar and --konan-home are the two paths the daemon
// needs to locate and drive `kotlin-native-compiler-embeddable.jar`. They
// are supplied by the native client spawn argv; the daemon itself has no
// other way to discover them (the client knows `~/.konan/<version>/` from
// the project's toolchain pin).
internal data class CliArgs(
    val socketPath: Path,
    val konancJar: Path,
    val konanHome: Path,
)

internal sealed interface CliError {
    data class UnknownFlag(val flag: String) : CliError
    data object MissingSocket : CliError
    data object MissingKonancJar : CliError
    data object MissingKonanHome : CliError
    data object EmptySocket : CliError
    data object EmptyKonancJar : CliError
    data object EmptyKonanHome : CliError
}

fun main(args: Array<String>) {
    val cli = parseArgs(args).mapBoth(
        success = { it },
        failure = { err ->
            System.err.println("kolt-native-daemon: ${formatCliError(err)}")
            System.err.println(
                "usage: kolt-native-daemon --socket <path> --konanc-jar <path> --konan-home <path>",
            )
            exitProcess(64)
        },
    )

    // ADR 0024 §8: `konan.home` is set as a JVM-global system property before
    // any K2Native reflection runs. konanc reads it via `System.getProperty`
    // to locate its own runtime libraries (distribution libs, stdlib klib,
    // platform klibs). ADR 0020 §2 / §1 of ADR 0024 make the native daemon a
    // separate JVM precisely so this JVM-global is safe to set — the JVM
    // compiler daemon does not share the process.
    System.setProperty("konan.home", cli.konanHome.toString())

    val compiler = ReflectiveK2NativeCompiler.create(cli.konancJar).mapBoth(
        success = { it },
        failure = { err ->
            System.err.println("kolt-native-daemon: failed to initialise compiler: ${formatSetupError(err)}")
            exitProcess(70)
        },
    )

    val server = DaemonServer(
        socketPath = cli.socketPath,
        compiler = compiler,
        config = DaemonConfig(),
    )
    val reason = server.serve().mapBoth(
        success = { it },
        failure = { err ->
            System.err.println("kolt-native-daemon: serve failed: $err")
            exitProcess(71)
        },
    )
    System.err.println("kolt-native-daemon: exiting (${reason::class.simpleName})")
    exitProcess(0)
}

internal fun parseArgs(args: Array<String>): Result<CliArgs, CliError> {
    var socketPath: String? = null
    var konancJar: String? = null
    var konanHome: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--socket" -> { socketPath = args.getOrNull(i + 1); i += 2 }
            "--konanc-jar" -> { konancJar = args.getOrNull(i + 1); i += 2 }
            "--konan-home" -> { konanHome = args.getOrNull(i + 1); i += 2 }
            else -> return Err(CliError.UnknownFlag(args[i]))
        }
    }
    if (socketPath == null) return Err(CliError.MissingSocket)
    if (konancJar == null) return Err(CliError.MissingKonancJar)
    if (konanHome == null) return Err(CliError.MissingKonanHome)
    if (socketPath.isBlank()) return Err(CliError.EmptySocket)
    if (konancJar.isBlank()) return Err(CliError.EmptyKonancJar)
    if (konanHome.isBlank()) return Err(CliError.EmptyKonanHome)
    return Ok(
        CliArgs(
            socketPath = Path.of(socketPath),
            konancJar = Path.of(konancJar),
            konanHome = Path.of(konanHome),
        ),
    )
}

private fun formatCliError(err: CliError): String = when (err) {
    is CliError.UnknownFlag -> "unknown flag: ${err.flag}"
    CliError.MissingSocket -> "--socket is required"
    CliError.MissingKonancJar -> "--konanc-jar is required"
    CliError.MissingKonanHome -> "--konan-home is required"
    CliError.EmptySocket -> "--socket value is empty"
    CliError.EmptyKonancJar -> "--konanc-jar value is empty"
    CliError.EmptyKonanHome -> "--konan-home value is empty"
}

private fun formatSetupError(err: SetupError): String = when (err) {
    is SetupError.KonancJarNotFound -> "konanc jar not found at ${err.path}"
    is SetupError.KonancJarUnreadable -> "konanc jar at ${err.path} is unreadable: ${err.cause.message ?: err.cause.javaClass.name}"
    is SetupError.K2NativeClassNotFound ->
        "K2Native class not found in konanc jar: ${err.cause.message ?: err.cause.javaClass.name}"
    is SetupError.K2NativeInstantiationFailed ->
        "failed to instantiate K2Native: ${err.cause.message ?: err.cause.javaClass.name}"
    SetupError.ExecMethodNotFound -> "exec(PrintStream, String[]) method not found on K2Native"
}
