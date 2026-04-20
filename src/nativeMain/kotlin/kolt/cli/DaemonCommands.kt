package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.build.daemon.projectHashOf
import kolt.config.resolveKoltPaths
import kolt.infra.ListFilesFailed
import kolt.infra.currentWorkingDirectory
import kolt.infra.eprintln
import kolt.infra.fileExists as infraFileExists
import kolt.infra.listSubdirectories as infraListSubdirectories
import kolt.infra.net.UnixSocket
import kolt.daemon.wire.FrameCodec as JvmFrameCodec
import kolt.daemon.wire.Message as JvmMessage
import kolt.nativedaemon.wire.FrameCodec as NativeFrameCodec
import kolt.nativedaemon.wire.Message as NativeMessage

private val DAEMON_SUBCOMMANDS = setOf("stop", "reap")

internal fun validateDaemonSubcommand(args: List<String>): Boolean =
    args.isNotEmpty() && args[0] in DAEMON_SUBCOMMANDS

internal fun doDaemon(args: List<String>): Result<Unit, Int> {
    if (args.isEmpty()) {
        printDaemonUsage()
        return Err(EXIT_CONFIG_ERROR)
    }
    return when (args[0]) {
        "stop" -> doDaemonStop(args.drop(1))
        "reap" -> doDaemonReap()
        else -> {
            eprintln("error: unknown daemon command '${args[0]}'")
            printDaemonUsage()
            Err(EXIT_CONFIG_ERROR)
        }
    }
}

private fun doDaemonStop(args: List<String>): Result<Unit, Int> {
    val all = args.contains("--all")
    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_CONFIG_ERROR) }

    if (all) {
        stopAllDaemons(paths.daemonBaseDir)
    } else {
        val cwd = currentWorkingDirectory() ?: run {
            eprintln("error: could not determine project directory")
            return Err(EXIT_CONFIG_ERROR)
        }
        val hash = projectHashOf(cwd)
        val projectDir = "${paths.daemonBaseDir}/$hash"
        val stopped = stopProjectDaemons(projectDir)
        when (stopped) {
            0 -> println("no daemon running for this project")
            1 -> println("daemon stopped")
            else -> println("stopped $stopped daemons")
        }
    }
    return Ok(Unit)
}

internal fun stopProjectDaemons(
    projectDir: String,
    fileExists: (String) -> Boolean = ::infraFileExists,
    listSubdirectories: (String) -> Result<List<String>, ListFilesFailed> = ::infraListSubdirectories,
    sendJvmShutdown: (String) -> Boolean = ::sendShutdown,
    sendNativeShutdown: (String) -> Boolean = ::sendNativeShutdown,
): Int {
    if (!fileExists(projectDir)) return 0
    var stopped = 0
    // Pre-#138 layout: JVM daemon socket sat directly under <projectHash>/.
    // The native daemon (ADR 0024, #170) post-dates the versioned layout,
    // so it has no legacy variant — only the JVM shutdown is attempted here.
    val legacySocket = "$projectDir/daemon.sock"
    if (fileExists(legacySocket) && sendJvmShutdown(legacySocket)) {
        stopped++
    }
    val versionDirs = listSubdirectories(projectDir).getOrElse { return stopped }
    for (versionDir in versionDirs) {
        // ADR 0024 §3: both daemons share <projectHash>/<version>/, keyed
        // by filename. Probe each and send the protocol-appropriate
        // Shutdown — the two wire formats are structurally similar but
        // bound to separate Message types per ADR 0024 §1.
        val jvmSocket = "$projectDir/$versionDir/daemon.sock"
        if (fileExists(jvmSocket) && sendJvmShutdown(jvmSocket)) {
            stopped++
        }
        val nativeSocket = "$projectDir/$versionDir/native-daemon.sock"
        if (fileExists(nativeSocket) && sendNativeShutdown(nativeSocket)) {
            stopped++
        }
    }
    return stopped
}

private fun stopAllDaemons(daemonBaseDir: String) {
    if (!infraFileExists(daemonBaseDir)) {
        println("no daemons running")
        return
    }
    val projectDirs = infraListSubdirectories(daemonBaseDir).getOrElse {
        println("no daemons running")
        return
    }
    var stopped = 0
    for (projectDir in projectDirs) {
        stopped += stopProjectDaemons("$daemonBaseDir/$projectDir")
    }
    if (stopped == 0) println("no daemons running")
    else println("stopped $stopped daemon${if (stopped > 1) "s" else ""}")
}

private fun sendShutdown(socketPath: String): Boolean {
    val socket = UnixSocket.connect(socketPath).getOrElse { return false }
    val sent = JvmFrameCodec.writeFrame(socket, JvmMessage.Shutdown).isOk
    socket.close()
    return sent
}

// ADR 0024 §1: native daemon speaks its own Message sealed interface. The
// wire-level JSON matches the JVM daemon's today, but keeping the send
// path typed catches a future divergence at the compiler rather than in
// production.
private fun sendNativeShutdown(socketPath: String): Boolean {
    val socket = UnixSocket.connect(socketPath).getOrElse { return false }
    val sent = NativeFrameCodec.writeFrame(socket, NativeMessage.Shutdown).isOk
    socket.close()
    return sent
}

private fun doDaemonReap(): Result<Unit, Int> {
    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); return Err(EXIT_CONFIG_ERROR) }
    val result = reapStaleDaemons(paths.daemonBaseDir)
    if (result.reaped == 0) {
        println("no stale daemons found")
    } else {
        println("reaped ${result.reaped} stale daemon dir${if (result.reaped > 1) "s" else ""}")
    }
    return Ok(Unit)
}

private fun printDaemonUsage() {
    eprintln("usage: kolt daemon <command>")
    eprintln("")
    eprintln("commands:")
    eprintln("  stop       Stop this project's compiler daemons (JVM and native; --all for all projects)")
    eprintln("  reap       Remove stale daemon directories and orphaned sockets")
}
