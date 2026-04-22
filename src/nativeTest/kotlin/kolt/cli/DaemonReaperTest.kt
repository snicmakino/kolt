@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.fileExists
import kolt.infra.net.SUN_PATH_CAPACITY
import kolt.infra.net.fillSockaddrUn
import kolt.infra.writeFileAsString
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.linux.sockaddr_un
import platform.posix.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonReaperTest {

    @Test
    fun emptyDaemonDirReturnsZeroReaped() {
        val dir = createTempDir("reaper-empty-")
        val result = reapStaleDaemons(dir)
        assertEquals(0, result.reaped)
        assertEquals(0, result.alive)
    }

    @Test
    fun directoryWithoutSocketIsReaped() {
        val base = createTempDir("reaper-nosock-")
        val projectDir = "$base/abc123"
        val versionDir = "$projectDir/2.3.20"
        ensureDirectoryRecursive(versionDir).getOrElse { error("mkdir failed") }
        writeFileAsString("$versionDir/jvm-compiler-daemon.log", "some log").getOrElse { error("write failed") }

        val result = reapStaleDaemons(base)
        assertEquals(1, result.reaped)
        assertFalse(fileExists(versionDir))
        assertFalse(fileExists(projectDir), "empty project dir should be removed")
    }

    @Test
    fun directoryWithOrphanedSocketIsReaped() {
        val base = createTempDir("reaper-orphan-")
        val projectDir = "$base/def456"
        val versionDir = "$projectDir/2.3.20"
        ensureDirectoryRecursive(versionDir).getOrElse { error("mkdir failed") }
        // A regular file named jvm-compiler-daemon.sock — connect will fail (not a real socket)
        writeFileAsString("$versionDir/jvm-compiler-daemon.sock", "").getOrElse { error("write failed") }

        val result = reapStaleDaemons(base)
        assertEquals(1, result.reaped)
        assertFalse(fileExists(versionDir))
        assertFalse(fileExists(projectDir))
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun liveDaemonIsPreserved() {
        val base = createTempDir("reaper-alive-")
        val projectDir = "$base/live123"
        val versionDir = "$projectDir/2.3.20"
        ensureDirectoryRecursive(versionDir).getOrElse { error("mkdir failed") }

        val socketPath = "$versionDir/jvm-compiler-daemon.sock"
        val listenFd = bindAndListen(socketPath)
        try {
            val result = reapStaleDaemons(base)
            assertEquals(0, result.reaped)
            assertEquals(1, result.alive)
            assertTrue(fileExists(versionDir), "live daemon dir must not be deleted")
            assertTrue(fileExists(projectDir))
        } finally {
            close(listenFd)
            unlink(socketPath)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun mixedAliveAndStale() {
        val base = createTempDir("reaper-mixed-")

        val staleProjectDir = "$base/stale111"
        val staleVersionDir = "$staleProjectDir/2.3.0"
        ensureDirectoryRecursive(staleVersionDir).getOrElse { error("mkdir failed") }
        writeFileAsString("$staleVersionDir/jvm-compiler-daemon.sock", "").getOrElse { error("write failed") }

        val aliveProjectDir = "$base/alive222"
        val aliveVersionDir = "$aliveProjectDir/2.3.20"
        ensureDirectoryRecursive(aliveVersionDir).getOrElse { error("mkdir failed") }
        val socketPath = "$aliveVersionDir/jvm-compiler-daemon.sock"
        val listenFd = bindAndListen(socketPath)
        try {
            val result = reapStaleDaemons(base)
            assertEquals(1, result.reaped)
            assertEquals(1, result.alive)
            assertFalse(fileExists(staleVersionDir))
            assertFalse(fileExists(staleProjectDir))
            assertTrue(fileExists(aliveVersionDir))
            assertTrue(fileExists(aliveProjectDir))
        } finally {
            close(listenFd)
            unlink(socketPath)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun bindAndListen(path: String): Int = memScoped {
        val fd = socket(AF_UNIX, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed" }
        val addr = alloc<sockaddr_un>()
        val addrLen = fillSockaddrUn(addr, path.encodeToByteArray())
        check(bind(fd, addr.ptr.reinterpret(), addrLen) == 0) { "bind() failed: ${strerror(errno)?.toKString()}" }
        check(listen(fd, 1) == 0) { "listen() failed" }
        fd
    }

    @Test
    fun nonexistentBaseDirReturnsZero() {
        val result = reapStaleDaemons("/tmp/kolt-reaper-nonexistent-${kotlin.random.Random.nextLong()}")
        assertEquals(0, result.reaped)
        assertEquals(0, result.alive)
    }

    // #181: `createDaemonBackend` fingerprints the socket filename
    // (`jvm-compiler-daemon-noplugins.sock` or `jvm-compiler-daemon-<8hex>.sock`). The reaper probed
    // only the bare `jvm-compiler-daemon.sock`, so fingerprinted orphans never got
    // swept. Post-fix, enumeration matches any `daemon*.sock` under a
    // version dir.
    @Test
    fun fingerprintedOrphanedSocketIsReaped() {
        val base = createTempDir("reaper-fp-orphan-")
        val projectDir = "$base/fp111"
        val versionDir = "$projectDir/2.3.20"
        ensureDirectoryRecursive(versionDir).getOrElse { error("mkdir failed") }
        writeFileAsString("$versionDir/jvm-compiler-daemon-noplugins.sock", "").getOrElse { error("write failed") }

        val result = reapStaleDaemons(base)

        assertEquals(1, result.reaped)
        assertFalse(fileExists(versionDir))
        assertFalse(fileExists(projectDir))
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun fingerprintedLiveDaemonIsPreserved() {
        val base = createTempDir("reaper-fp-alive-")
        val projectDir = "$base/fp222"
        val versionDir = "$projectDir/2.3.20"
        ensureDirectoryRecursive(versionDir).getOrElse { error("mkdir failed") }

        val socketPath = "$versionDir/jvm-compiler-daemon-abcd1234.sock"
        val listenFd = bindAndListen(socketPath)
        try {
            val result = reapStaleDaemons(base)
            assertEquals(0, result.reaped)
            assertEquals(1, result.alive)
            assertTrue(fileExists(versionDir), "live fingerprinted daemon dir must not be deleted")
        } finally {
            close(listenFd)
            unlink(socketPath)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun versionDirWithOneLiveFingerprintSurvivesEvenIfSiblingIsStale() {
        // Mixed state: one fingerprint's daemon is alive, a sibling
        // fingerprint's socket is stale. The version dir must stay because
        // reaping would unlink the live socket; the stale sibling rides
        // along and gets swept the next time it's the only one left.
        val base = createTempDir("reaper-fp-mixed-")
        val projectDir = "$base/fp333"
        val versionDir = "$projectDir/2.3.20"
        ensureDirectoryRecursive(versionDir).getOrElse { error("mkdir failed") }

        writeFileAsString("$versionDir/jvm-compiler-daemon-noplugins.sock", "").getOrElse { error("write failed") }
        val liveSocket = "$versionDir/jvm-compiler-daemon-abcd1234.sock"
        val listenFd = bindAndListen(liveSocket)
        try {
            val result = reapStaleDaemons(base)
            assertEquals(0, result.reaped, "version dir must not be wiped while a fingerprint is alive")
            assertEquals(1, result.alive)
            assertTrue(fileExists(liveSocket), "live socket must not be unlinked")
            assertTrue(fileExists(versionDir))
        } finally {
            close(listenFd)
            unlink(liveSocket)
        }
    }

    @Test
    fun icSiblingIsNotReaped() {
        val base = createTempDir("reaper-ic-")
        val icDir = "$base/ic/2.3.20/somehash"
        ensureDirectoryRecursive(icDir).getOrElse { error("mkdir failed") }
        writeFileAsString("$icDir/state.bin", "ic state").getOrElse { error("write failed") }

        val result = reapStaleDaemons(base)
        assertEquals(0, result.reaped)
        assertTrue(fileExists(icDir), "ic state must not be touched by daemon reaper")
    }

    private fun createTempDir(prefix: String): String {
        val template = "/tmp/${prefix}XXXXXX"
        val buf = template.encodeToByteArray().copyOf(template.length + 1)
        buf.usePinned { pinned ->
            val result = mkdtemp(pinned.addressOf(0))
                ?: error("mkdtemp failed")
            return result.toKString()
        }
    }
}
