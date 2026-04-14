package kolt.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.mapBoth
import kolt.daemon.ic.BtaIncrementalCompiler
import kolt.daemon.server.DaemonConfig
import kolt.daemon.server.DaemonServer
import java.io.File
import java.nio.file.Path
import kotlin.system.exitProcess

internal data class CliArgs(
    val socketPath: Path,
    // Retained per post-B-2a review decision: kolt-compiler-embeddable still
    // flows through this flag even though Phase B routes compile traffic through
    // BtaIncrementalCompiler. Downstream B-2c work may need to reuse it for
    // plugin-jars alongside BTA, and dropping it now would force a client-side
    // change the moment that requirement materialises. Unused paths are ignored
    // by the daemon rather than failing startup. `MainCliArgsTest` pins the
    // flag as required so a future contributor cannot silently remove it
    // from the parser without updating the native-client spawn argv in lock-
    // step.
    val compilerJars: List<File>,
    val btaImplJars: List<File>,
)

internal sealed interface CliError {
    data class UnknownFlag(val flag: String) : CliError
    data object MissingSocket : CliError
    data object MissingCompilerJars : CliError
    data object EmptyCompilerJars : CliError
    data object MissingBtaImplJars : CliError
    data object EmptyBtaImplJars : CliError
}

fun main(args: Array<String>) {
    val cli = parseArgs(args).mapBoth(
        success = { it },
        failure = { err ->
            System.err.println("kolt-compiler-daemon: ${formatCliError(err)}")
            System.err.println(
                "usage: kolt-compiler-daemon --socket <path> --compiler-jars <classpath> --bta-impl-jars <classpath>",
            )
            exitProcess(64)
        },
    )

    val compiler = BtaIncrementalCompiler.create(
        btaImplJars = cli.btaImplJars.map { it.toPath() },
    ).mapBoth(
        success = { it },
        failure = { err ->
            System.err.println(
                "kolt-compiler-daemon: failed to initialise incremental compiler: " +
                    (err.cause.message ?: err.cause.javaClass.name),
            )
            exitProcess(70)
        },
    )

    val server = DaemonServer(cli.socketPath, compiler, DaemonConfig())
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

internal fun parseArgs(args: Array<String>): Result<CliArgs, CliError> {
    var socketPath: String? = null
    var compilerJars: String? = null
    var btaImplJars: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--socket" -> { socketPath = args.getOrNull(i + 1); i += 2 }
            "--compiler-jars" -> { compilerJars = args.getOrNull(i + 1); i += 2 }
            "--bta-impl-jars" -> { btaImplJars = args.getOrNull(i + 1); i += 2 }
            else -> return Err(CliError.UnknownFlag(args[i]))
        }
    }
    if (socketPath == null) return Err(CliError.MissingSocket)
    if (compilerJars == null) return Err(CliError.MissingCompilerJars)
    if (btaImplJars == null) return Err(CliError.MissingBtaImplJars)
    val cjars = compilerJars.split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it) }
    if (cjars.isEmpty()) return Err(CliError.EmptyCompilerJars)
    val bjars = btaImplJars.split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it) }
    if (bjars.isEmpty()) return Err(CliError.EmptyBtaImplJars)
    return Ok(CliArgs(Path.of(socketPath), cjars, bjars))
}

private fun formatCliError(err: CliError): String = when (err) {
    is CliError.UnknownFlag -> "unknown flag: ${err.flag}"
    CliError.MissingSocket -> "--socket is required"
    CliError.MissingCompilerJars -> "--compiler-jars is required"
    CliError.EmptyCompilerJars -> "--compiler-jars resolved to zero paths"
    CliError.MissingBtaImplJars -> "--bta-impl-jars is required"
    CliError.EmptyBtaImplJars -> "--bta-impl-jars resolved to zero paths"
}
