package kolt.infra.net.testfixture

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import platform.linux.sockaddr_un
import platform.posix.AF_UNIX
import platform.posix.SOCK_STREAM
import platform.posix.bind
import platform.posix.errno
import platform.posix.getpid
import platform.posix.listen
import platform.posix.memset
import platform.posix.sockaddr
import platform.posix.socket
import platform.posix.socklen_t
import platform.posix.strerror
import platform.posix.unlink

/**
 * Test-only AF_UNIX server used as the "other side" for UnixSocket tests.
 *
 * S3.2 skeleton: only owns the listening socket lifecycle (bind / listen
 * / unlink). No accept thread and no request handling yet — those land
 * in S3.3 when the first round-trip test requires them.
 *
 * Not thread-safe; each test owns its own instance.
 */
class UnixEchoServer private constructor(
    val socketPath: String,
    private val listenFd: Int,
) : AutoCloseable {
    private var closed = false

    @OptIn(ExperimentalForeignApi::class)
    override fun close() {
        if (closed) return
        closed = true
        platform.posix.close(listenFd)
        unlink(socketPath)
    }

    companion object {
        private const val BACKLOG = 8

        @OptIn(ExperimentalForeignApi::class)
        fun start(): Result<UnixEchoServer, StartError> {
            val path = generateUniquePath()

            val fd = socket(AF_UNIX, SOCK_STREAM, 0)
            if (fd < 0) {
                val e = errno
                return Err(StartError("socket", e, strerrorMessage(e)))
            }

            return memScoped {
                val addr = alloc<sockaddr_un>()
                memset(addr.ptr, 0, sizeOf<sockaddr_un>().convert())
                addr.sun_family = AF_UNIX.convert()
                val pathBytes = path.encodeToByteArray()
                val sunPath = addr.sun_path
                for (i in pathBytes.indices) {
                    sunPath[i] = pathBytes[i]
                }
                sunPath[pathBytes.size] = 0
                val sunPathOffset =
                    sunPath.rawValue.toLong() - addr.ptr.rawValue.toLong()
                val addrLen: socklen_t =
                    (sunPathOffset.toInt() + pathBytes.size + 1).convert()

                if (bind(fd, addr.ptr.reinterpret<sockaddr>(), addrLen) != 0) {
                    val e = errno
                    platform.posix.close(fd)
                    return@memScoped Err(StartError("bind", e, strerrorMessage(e)))
                }
                if (listen(fd, BACKLOG) != 0) {
                    val e = errno
                    platform.posix.close(fd)
                    unlink(path)
                    return@memScoped Err(StartError("listen", e, strerrorMessage(e)))
                }
                Ok(UnixEchoServer(path, fd))
            }
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun generateUniquePath(): String {
            val pid = getpid()
            val hex = "0123456789abcdef"
            val suffix = (0 until 12).map { hex.random() }.joinToString("")
            return "/tmp/kolt-uds-test-$pid-$suffix.sock"
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun strerrorMessage(e: Int): String =
            strerror(e)?.toKString() ?: "errno=$e"
    }

    data class StartError(
        val step: String,
        val errno: Int,
        val message: String,
    )
}
