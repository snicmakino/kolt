package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.fileExists
import kolt.infra.listSubdirectories
import kolt.infra.net.UnixSocket
import kolt.infra.removeDirectoryRecursive

data class ReapResult(val reaped: Int, val alive: Int, val failed: Int)

// Sibling of <projectHash> dirs under daemonBaseDir; holds incremental
// compile state, not daemon sockets. Never touched by the reaper.
private const val IC_STATE_DIR_NAME = "ic"

// Probe-based reaper for orphaned daemon sockets. Layout per ADR 0022:
// `<base>/<projectHash>/<kotlinVersion>/daemon.sock`. A version dir is
// reaped when: (a) no `daemon.sock` exists, or (b) connect fails. A
// project dir is removed only after all of its version subdirs are reaped.
// Invariant: a live daemon is never touched.
internal fun reapStaleDaemons(daemonBaseDir: String): ReapResult {
    if (!fileExists(daemonBaseDir)) return ReapResult(0, 0, 0)
    val projectDirs = listSubdirectories(daemonBaseDir).getOrElse { return ReapResult(0, 0, 0) }

    var reaped = 0
    var alive = 0
    var failed = 0
    for (projectDir in projectDirs) {
        if (projectDir == IC_STATE_DIR_NAME) continue
        val projectFullDir = "$daemonBaseDir/$projectDir"

        // Pre-#138 layout: socket lived directly at <projectHash>/daemon.sock.
        // Probe before removing — a daemon spawned by an older kolt build may
        // still be alive and serving requests.
        val legacySocket = "$projectFullDir/daemon.sock"
        if (fileExists(legacySocket)) {
            val socket = UnixSocket.connect(legacySocket)
            if (socket.isOk) {
                socket.getOrElse { null }?.close()
                alive++
                continue
            }
            if (removeDirectoryRecursive(projectFullDir).isOk) reaped++ else failed++
            continue
        }

        val versionDirs = listSubdirectories(projectFullDir).getOrElse { continue }
        var versionsRemaining = 0
        for (versionDir in versionDirs) {
            val versionFullDir = "$projectFullDir/$versionDir"
            val socketPath = "$versionFullDir/daemon.sock"
            if (fileExists(socketPath)) {
                val socket = UnixSocket.connect(socketPath)
                if (socket.isOk) {
                    socket.getOrElse { null }?.close()
                    alive++
                    versionsRemaining++
                    continue
                }
            }
            if (removeDirectoryRecursive(versionFullDir).isOk) {
                reaped++
            } else {
                failed++
                versionsRemaining++
            }
        }
        if (versionsRemaining == 0) {
            removeDirectoryRecursive(projectFullDir)
        }
    }
    return ReapResult(reaped, alive, failed)
}
