package kolt.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.mapBoth
import kolt.daemon.host.SharedCompilerHost
import kolt.daemon.server.DaemonConfig
import kolt.daemon.server.DaemonServer
import kolt.daemon.server.ExitReason
import java.io.File
import java.nio.file.Path
import kotlin.system.exitProcess

private data class CliArgs(
    val socketPath: Path,
    val compilerJars: List<File>,
)

private sealed interface CliError {
    data class UnknownFlag(val flag: String) : CliError
    data object MissingSocket : CliError
    data object MissingCompilerJars : CliError
    data object EmptyCompilerJars : CliError
}

fun main(args: Array<String>) {
    val cli = parseArgs(args).mapBoth(
        success = { it },
        failure = { err ->
            System.err.println("kolt-compiler-daemon: ${formatCliError(err)}")
            System.err.println("usage: kolt-compiler-daemon --socket <path> --compiler-jars <classpath>")
            exitProcess(64)
        },
    )

    val host = SharedCompilerHost.create(cli.compilerJars).mapBoth(
        success = { it },
        failure = { err ->
            System.err.println("kolt-compiler-daemon: failed to initialise compiler host: ${err.reason}")
            exitProcess(70)
        },
    )

    val server = DaemonServer(cli.socketPath, host, DaemonConfig())
    val reason = server.serve().mapBoth(
        success = { it },
        failure = { err ->
            System.err.println("kolt-compiler-daemon: serve failed: $err")
            exitProcess(71)
        },
    )
    System.err.println("kolt-compiler-daemon: exiting (${reason::class.simpleName})")
    exitProcess(0)
}

private fun parseArgs(args: Array<String>): Result<CliArgs, CliError> {
    var socketPath: String? = null
    var compilerJars: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--socket" -> { socketPath = args.getOrNull(i + 1); i += 2 }
            "--compiler-jars" -> { compilerJars = args.getOrNull(i + 1); i += 2 }
            else -> return Err(CliError.UnknownFlag(args[i]))
        }
    }
    if (socketPath == null) return Err(CliError.MissingSocket)
    if (compilerJars == null) return Err(CliError.MissingCompilerJars)
    val jars = compilerJars.split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it) }
    if (jars.isEmpty()) return Err(CliError.EmptyCompilerJars)
    return Ok(CliArgs(Path.of(socketPath), jars))
}

private fun formatCliError(err: CliError): String = when (err) {
    is CliError.UnknownFlag -> "unknown flag: ${err.flag}"
    CliError.MissingSocket -> "--socket is required"
    CliError.MissingCompilerJars -> "--compiler-jars is required"
    CliError.EmptyCompilerJars -> "--compiler-jars resolved to zero paths"
}
