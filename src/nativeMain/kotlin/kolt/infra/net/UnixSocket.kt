package kolt.infra.net

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
import platform.posix.ENAMETOOLONG
import platform.posix.SOCK_STREAM
import platform.posix.errno
import platform.posix.memset
import platform.posix.sockaddr
import platform.posix.socket
import platform.posix.socklen_t
import platform.posix.strerror

/**
 * AF_UNIX stream socket client, used by the native daemon client.
 *
 * Raw cinterop types (sockaddr_un, socket/connect/send/recv/close) are
 * confined to this file so callers never see them.
 */
class UnixSocket internal constructor(internal val fd: Int) : AutoCloseable {
    private var closed = false

    @OptIn(ExperimentalForeignApi::class)
    override fun close() {
        if (closed) return
        closed = true
        platform.posix.close(fd)
    }

    companion object {
        // sizeof(sockaddr_un.sun_path) on Linux.
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
}
