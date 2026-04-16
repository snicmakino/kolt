@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.ensureDirectory
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
        ensureDirectory(projectDir).getOrElse { error("mkdir failed") }
        writeFileAsString("$projectDir/daemon.log", "some log").getOrElse { error("write failed") }

        val result = reapStaleDaemons(base)
        assertEquals(1, result.reaped)
        assertFalse(fileExists(projectDir))
    }

    @Test
    fun directoryWithOrphanedSocketIsReaped() {
        val base = createTempDir("reaper-orphan-")
        val projectDir = "$base/def456"
        ensureDirectory(projectDir).getOrElse { error("mkdir failed") }
        // A regular file named daemon.sock — connect will fail (not a real socket)
        writeFileAsString("$projectDir/daemon.sock", "").getOrElse { error("write failed") }

        val result = reapStaleDaemons(base)
        assertEquals(1, result.reaped)
        assertFalse(fileExists(projectDir))
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun liveDaemonIsPreserved() {
        val base = createTempDir("reaper-alive-")
        val projectDir = "$base/live123"
        ensureDirectory(projectDir).getOrElse { error("mkdir failed") }

        val socketPath = "$projectDir/daemon.sock"
        val listenFd = bindAndListen(socketPath)
        try {
            val result = reapStaleDaemons(base)
            assertEquals(0, result.reaped)
            assertEquals(1, result.alive)
            assertTrue(fileExists(projectDir), "live daemon dir must not be deleted")
        } finally {
            close(listenFd)
            unlink(socketPath)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun mixedAliveAndStale() {
        val base = createTempDir("reaper-mixed-")

        val staleDir = "$base/stale111"
        ensureDirectory(staleDir).getOrElse { error("mkdir failed") }
        writeFileAsString("$staleDir/daemon.sock", "").getOrElse { error("write failed") }

        val aliveDir = "$base/alive222"
        ensureDirectory(aliveDir).getOrElse { error("mkdir failed") }
        val socketPath = "$aliveDir/daemon.sock"
        val listenFd = bindAndListen(socketPath)
        try {
            val result = reapStaleDaemons(base)
            assertEquals(1, result.reaped)
            assertEquals(1, result.alive)
            assertFalse(fileExists(staleDir))
            assertTrue(fileExists(aliveDir))
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
