package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.fileExists
import kolt.infra.listFiles
import kolt.infra.listSubdirectories
import kolt.infra.net.UnixSocket
import kolt.infra.removeDirectoryRecursive

data class ReapResult(val reaped: Int, val alive: Int, val failed: Int)

// Sibling of <projectHash> dirs under daemonBaseDir; holds incremental
// compile state, not daemon sockets. Never touched by the reaper.
private const val IC_STATE_DIR_NAME = "ic"

// Probe-based reaper for orphaned daemon sockets. Layout per ADR 0022:
// `<base>/<projectHash>/<kotlinVersion>/jvm-compiler-daemon.sock`. A version dir is
// reaped when: (a) no `jvm-compiler-daemon.sock` exists, or (b) connect fails. A
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

        val versionDirs = listSubdirectories(projectFullDir).getOrElse { continue }
        var versionsRemaining = 0
        for (versionDir in versionDirs) {
            val versionFullDir = "$projectFullDir/$versionDir"
            // #181: enumerate all JVM daemon sockets in the version dir
            // (`jvm-compiler-daemon.sock`, `jvm-compiler-daemon-noplugins.sock`, `jvm-compiler-daemon-<8hex>.sock`),
            // not just the bare `jvm-compiler-daemon.sock`. If any fingerprint is alive,
            // the whole version dir stays — reaping would unlink the live
            // socket file. Stale siblings ride along until they're the only
            // ones left.
            val jvmSockets = listFiles(versionFullDir).getOrElse { emptyList() }
                .filter { isJvmDaemonSocket(it) }
                .map { "$versionFullDir/$it" }
            val aliveHere = jvmSockets.count { probeAlive(it) }
            if (aliveHere > 0) {
                alive += aliveHere
                versionsRemaining++
                continue
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

private fun probeAlive(socketPath: String): Boolean {
    val socket = UnixSocket.connect(socketPath)
    if (socket.isErr) return false
    socket.getOrElse { null }?.close()
    return true
}
