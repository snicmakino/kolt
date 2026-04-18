package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.build.daemon.projectHashOf
import kolt.config.resolveKoltPaths
import kolt.daemon.wire.FrameCodec
import kolt.daemon.wire.Message
import kolt.infra.currentWorkingDirectory
import kolt.infra.eprintln
import kolt.infra.fileExists
import kolt.infra.listSubdirectories
import kolt.infra.net.UnixSocket

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

private fun stopProjectDaemons(projectDir: String): Int {
    if (!fileExists(projectDir)) return 0
    var stopped = 0
    // Pre-#138 layout: socket sat directly under <projectHash>/. Probe first
    // so a still-running pre-upgrade daemon can be shut down cleanly.
    val legacySocket = "$projectDir/daemon.sock"
    if (fileExists(legacySocket) && sendShutdown(legacySocket)) {
        stopped++
    }
    val versionDirs = listSubdirectories(projectDir).getOrElse { return stopped }
    for (versionDir in versionDirs) {
        val socketPath = "$projectDir/$versionDir/daemon.sock"
        if (fileExists(socketPath) && sendShutdown(socketPath)) {
            stopped++
        }
    }
    return stopped
}

private fun stopAllDaemons(daemonBaseDir: String) {
    if (!fileExists(daemonBaseDir)) {
        println("no daemons running")
        return
    }
    val projectDirs = listSubdirectories(daemonBaseDir).getOrElse {
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
    val sent = FrameCodec.writeFrame(socket, Message.Shutdown).isOk
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
    eprintln("  stop       Stop the compiler daemon (--all for all daemons)")
    eprintln("  reap       Remove stale daemon directories and orphaned sockets")
}
