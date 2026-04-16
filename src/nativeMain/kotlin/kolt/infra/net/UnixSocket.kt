package kolt.infra.net

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.linux.sockaddr_un
import platform.posix.AF_UNIX
import platform.posix.EINTR
import platform.posix.SHUT_WR
import platform.posix.SOCK_STREAM
import platform.posix.errno
import platform.posix.recv
import platform.posix.send
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.socket
import platform.posix.strerror

// Not thread-safe: concurrent close() can double-close the fd.
class UnixSocket internal constructor(private val fd: Int) : AutoCloseable {
    private var closed = false

    @OptIn(ExperimentalForeignApi::class)
    fun sendAll(bytes: ByteArray): Result<Unit, UnixSocketError> {
        if (bytes.isEmpty()) return Ok(Unit)
        var offset = 0
        bytes.usePinned { pinned ->
            while (offset < bytes.size) {
                val n = send(
                    fd,
                    pinned.addressOf(offset),
                    (bytes.size - offset).convert(),
                    0,
                )
                if (n < 0L) {
                    val e = errno
                    if (e == EINTR) continue
                    return Err(UnixSocketError.SendFailed(e, strerrorMessage(e)))
                }
                if (n == 0L) {
                    return Err(
                        UnixSocketError.SendFailed(
                            errno = 0,
                            message = "send() returned 0 after $offset of ${bytes.size} bytes",
                        ),
                    )
                }
                offset += n.toInt()
            }
        }
        return Ok(Unit)
    }

    @OptIn(ExperimentalForeignApi::class)
    fun recvExact(n: Int): Result<ByteArray, UnixSocketError> {
        if (n < 0) {
            return Err(
                UnixSocketError.InvalidArgument(
                    "recvExact length must be non-negative, was $n",
                ),
            )
        }
        val buf = ByteArray(n)
        if (n == 0) return Ok(buf)
        var offset = 0
        buf.usePinned { pinned ->
            while (offset < n) {
                val got = recv(
                    fd,
                    pinned.addressOf(offset),
                    (n - offset).convert(),
                    0,
                )
                if (got < 0L) {
                    val e = errno
                    if (e == EINTR) continue
                    return Err(UnixSocketError.RecvFailed(e, strerrorMessage(e)))
                }
                if (got == 0L) {
                    return Err(UnixSocketError.UnexpectedEof(received = offset, expected = n))
                }
                offset += got.toInt()
            }
        }
        return Ok(buf)
    }

    @OptIn(ExperimentalForeignApi::class)
    fun shutdownWrite(): Result<Unit, UnixSocketError> {
        if (shutdown(fd, SHUT_WR) != 0) {
            val e = errno
            return Err(UnixSocketError.ShutdownFailed(e, strerrorMessage(e)))
        }
        return Ok(Unit)
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun close() {
        if (closed) return
        closed = true
        platform.posix.close(fd)
    }

    companion object {
        @OptIn(ExperimentalForeignApi::class)
        fun connect(path: String): Result<UnixSocket, UnixSocketError> {
            val pathBytes = path.encodeToByteArray()
            if (pathBytes.size >= SUN_PATH_CAPACITY) {
                return Err(
                    UnixSocketError.InvalidArgument(
                        "socket path exceeds sun_path capacity ($SUN_PATH_CAPACITY bytes): $path",
                    ),
                )
            }

            val fd = socket(AF_UNIX, SOCK_STREAM, 0)
            if (fd < 0) {
                val e = errno
                return Err(UnixSocketError.ConnectFailed(path, e, strerrorMessage(e)))
            }

            return memScoped {
                val addr = alloc<sockaddr_un>()
                val addrLen = fillSockaddrUn(addr, pathBytes)
                var rc: Int
                while (true) {
                    rc = platform.posix.connect(
                        fd,
                        addr.ptr.reinterpret<sockaddr>(),
                        addrLen,
                    )
                    if (rc == 0) break
                    val e = errno
                    if (e != EINTR) {
                        platform.posix.close(fd)
                        return@memScoped Err(
                            UnixSocketError.ConnectFailed(path, e, strerrorMessage(e)),
                        )
                    }
                }
                Ok(UnixSocket(fd))
            }
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun strerrorMessage(e: Int): String =
            strerror(e)?.toKString() ?: "errno=$e"
    }
}

sealed interface UnixSocketError {
    data class ConnectFailed(
        val path: String,
        val errno: Int,
        val message: String,
    ) : UnixSocketError

    data class SendFailed(
        val errno: Int,
        val message: String,
    ) : UnixSocketError

    data class RecvFailed(
        val errno: Int,
        val message: String,
    ) : UnixSocketError

    data class UnexpectedEof(
        val received: Int,
        val expected: Int,
    ) : UnixSocketError

    data class ShutdownFailed(
        val errno: Int,
        val message: String,
    ) : UnixSocketError

    data class InvalidArgument(val detail: String) : UnixSocketError
}
