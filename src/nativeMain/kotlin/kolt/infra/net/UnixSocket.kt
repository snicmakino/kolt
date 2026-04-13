package kolt.infra.net

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.linux.sockaddr_un
import platform.posix.AF_UNIX
import platform.posix.ENAMETOOLONG
import platform.posix.SHUT_WR
import platform.posix.SOCK_STREAM
import platform.posix.errno
import platform.posix.memset
import platform.posix.recv
import platform.posix.send
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.socket
import platform.posix.socklen_t
import platform.posix.strerror

/**
 * AF_UNIX stream socket client, used by the native daemon client.
 *
 * Raw cinterop types (sockaddr_un, socket/connect/send/recv/close) are
 * confined to this file so callers never see them.
 *
 * Not thread-safe. The close-flag guard is a plain check-then-act, so
 * concurrent `close()` calls can double-close the underlying fd — and
 * once the kernel recycles a descriptor number, the second close will
 * hit an unrelated fd. Callers must confine a `UnixSocket` instance to
 * a single thread, or synchronize externally.
 */
class UnixSocket internal constructor(private val fd: Int) : AutoCloseable {
    private var closed = false

    /**
     * Write all of `bytes` to the socket, looping over partial sends.
     */
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
                if (n < 0) {
                    val e = errno
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

    /**
     * Read exactly `n` bytes from the socket. A premature EOF surfaces
     * as `UnexpectedEof` with the count of bytes received so far.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun recvExact(n: Int): Result<ByteArray, UnixSocketError> {
        if (n < 0) {
            return Err(
                UnixSocketError.RecvFailed(
                    errno = 0,
                    message = "recvExact length must be non-negative, was $n",
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
                if (got < 0) {
                    val e = errno
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

    /**
     * Half-close the write side of the socket, signalling EOF to the
     * peer while still allowing incoming bytes to be read.
     */
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
        // Linux caps AF_UNIX pathnames at 108 bytes including the NUL
        // terminator; see unix(7).
        private const val SUN_PATH_CAPACITY = 108

        @OptIn(ExperimentalForeignApi::class)
        fun connect(path: String): Result<UnixSocket, UnixSocketError> {
            val pathBytes = path.encodeToByteArray()
            if (pathBytes.size >= SUN_PATH_CAPACITY) {
                return Err(
                    UnixSocketError.ConnectFailed(
                        path = path,
                        errno = ENAMETOOLONG,
                        message = "path exceeds sun_path capacity ($SUN_PATH_CAPACITY bytes)",
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
                memset(addr.ptr, 0, sizeOf<sockaddr_un>().convert())
                addr.sun_family = AF_UNIX.convert()
                val sunPath = addr.sun_path
                for (i in pathBytes.indices) {
                    sunPath[i] = pathBytes[i]
                }
                sunPath[pathBytes.size] = 0

                // addrlen = offsetof(sockaddr_un, sun_path) + strlen(path) + 1.
                // Using the offset (rather than sizeof(sockaddr_un)) keeps
                // the door open for abstract sockets (path starting with NUL).
                val sunPathOffset =
                    sunPath.rawValue.toLong() - addr.ptr.rawValue.toLong()
                val addrLen: socklen_t =
                    (sunPathOffset.toInt() + pathBytes.size + 1).convert()
                val rc = platform.posix.connect(
                    fd,
                    addr.ptr.reinterpret<sockaddr>(),
                    addrLen,
                )
                if (rc != 0) {
                    val e = errno
                    platform.posix.close(fd)
                    Err(UnixSocketError.ConnectFailed(path, e, strerrorMessage(e)))
                } else {
                    Ok(UnixSocket(fd))
                }
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
}
