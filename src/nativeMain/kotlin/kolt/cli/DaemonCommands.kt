package kolt.cli

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
import kotlin.system.exitProcess

private val DAEMON_SUBCOMMANDS = setOf("stop", "reap")

internal fun validateDaemonSubcommand(args: List<String>): Boolean =
    args.isNotEmpty() && args[0] in DAEMON_SUBCOMMANDS

internal fun doDaemon(args: List<String>) {
    if (args.isEmpty()) {
        printDaemonUsage()
        exitProcess(EXIT_CONFIG_ERROR)
    }
    when (args[0]) {
        "stop" -> doDaemonStop(args.drop(1))
        "reap" -> doDaemonReap()
        else -> {
            eprintln("error: unknown daemon command '${args[0]}'")
            printDaemonUsage()
            exitProcess(EXIT_CONFIG_ERROR)
        }
    }
}

private fun doDaemonStop(args: List<String>) {
    val all = args.contains("--all")
    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); exitProcess(EXIT_CONFIG_ERROR) }

    if (all) {
        stopAllDaemons(paths.daemonBaseDir)
    } else {
        val cwd = currentWorkingDirectory() ?: run {
            eprintln("error: could not determine project directory")
            exitProcess(EXIT_CONFIG_ERROR)
        }
        val hash = projectHashOf(cwd)
        val socketPath = paths.daemonSocketPath(hash)
        if (!fileExists(socketPath)) {
            println("no daemon running for this project")
            return
        }
        val stopped = sendShutdown(socketPath)
        if (stopped) println("daemon stopped") else println("no daemon running for this project")
    }
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
    for (dir in projectDirs) {
        val socketPath = "$daemonBaseDir/$dir/daemon.sock"
        if (fileExists(socketPath) && sendShutdown(socketPath)) {
            stopped++
        }
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

private fun doDaemonReap() {
    val paths = resolveKoltPaths().getOrElse { eprintln("error: $it"); exitProcess(EXIT_CONFIG_ERROR) }
    val result = reapStaleDaemons(paths.daemonBaseDir)
    if (result.reaped == 0) {
        println("no stale daemons found")
    } else {
        println("reaped ${result.reaped} stale daemon dir${if (result.reaped > 1) "s" else ""}")
    }
}

private fun printDaemonUsage() {
    eprintln("usage: kolt daemon <command>")
    eprintln("")
    eprintln("commands:")
    eprintln("  stop       Stop the compiler daemon (--all for all daemons)")
    eprintln("  reap       Remove stale daemon directories and orphaned sockets")
}
