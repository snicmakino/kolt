package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.fileExists
import kolt.infra.listSubdirectories
import kolt.infra.net.UnixSocket
import kolt.infra.removeDirectoryRecursive

data class ReapResult(val reaped: Int, val alive: Int, val failed: Int)

// Probe-based reaper for orphaned daemon sockets under `daemonBaseDir`.
// A daemon directory is reaped when: (a) no `daemon.sock` exists, or
// (b) `daemon.sock` exists but connect fails (daemon already exited).
// Invariant: a live daemon is never touched.
internal fun reapStaleDaemons(daemonBaseDir: String): ReapResult {
    if (!fileExists(daemonBaseDir)) return ReapResult(0, 0, 0)
    val dirs = listSubdirectories(daemonBaseDir).getOrElse { return ReapResult(0, 0, 0) }

    var reaped = 0
    var alive = 0
    var failed = 0
    for (dir in dirs) {
        val fullDir = "$daemonBaseDir/$dir"
        val socketPath = "$fullDir/daemon.sock"
        if (fileExists(socketPath)) {
            val socket = UnixSocket.connect(socketPath)
            if (socket.isOk) {
                socket.getOrElse { null }?.close()
                alive++
                continue
            }
        }
        if (removeDirectoryRecursive(fullDir).isOk) {
            reaped++
        } else {
            failed++
        }
    }
    return ReapResult(reaped, alive, failed)
}
