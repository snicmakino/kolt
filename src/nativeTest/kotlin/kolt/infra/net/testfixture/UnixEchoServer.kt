package kolt.infra.net.testfixture

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlin.concurrent.AtomicInt
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.linux.sockaddr_un
import platform.posix.AF_UNIX
import platform.posix.ENAMETOOLONG
import platform.posix.SOCK_STREAM
import platform.posix.accept
import platform.posix.bind
import platform.posix.errno
import platform.posix.getpid
import platform.posix.listen
import platform.posix.memset
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.value
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.recv
import platform.posix.send
import platform.posix.sockaddr
import platform.posix.socket
import platform.posix.socklen_t
import platform.posix.strerror
import platform.posix.unlink

/**
 * Test-only AF_UNIX server used as the "other side" for UnixSocket tests.
 *
 * Each instance owns a listening socket plus a dedicated accept thread
 * that serves one connection at a time using the configured handler.
 * The request side of every connection is read until EOF (the client
 * must half-close its write side via `UnixSocket.shutdownWrite()`),
 * the handler produces a response, and the response is written back
 * before the worker closes the client fd and loops.
 *
 * Not thread-safe; each test owns its own instance.
 */
@OptIn(ExperimentalForeignApi::class)
class UnixEchoServer private constructor(
    val socketPath: String,
    private val listenFd: Int,
    // pthread_t on linuxX64 glibc is `unsigned long`, so we carry it as
    // ULong and reinterpret at the pthread_join call site; this keeps
    // the opt-in surface small and the field type straightforward.
    private val workerThread: ULong,
    private val workerRef: StableRef<EchoWorkerState>,
    private val state: EchoWorkerState,
) : AutoCloseable {
    private var closed = false

    @OptIn(ExperimentalForeignApi::class)
    override fun close() {
        if (closed) return
        closed = true
        state.shutdown.value = 1
        // close(listenFd) does not reliably unblock a thread parked in
        // accept() on Linux, and shutdown() on a listening socket
        // returns ENOTCONN. Force one extra accept() to return by
        // connecting to our own path; the worker sees the shutdown
        // flag on the next iteration and exits.
        selfConnectToWakeAccept(socketPath)
        pthread_join(workerThread, null)
        workerRef.dispose()
        platform.posix.close(listenFd)
        unlink(socketPath)
    }

    companion object {
        private const val BACKLOG = 8

        // Linux caps AF_UNIX pathnames at 108 bytes including the NUL
        // terminator; see unix(7). Kept in sync with UnixSocket.
        private const val SUN_PATH_CAPACITY = 108

        @OptIn(ExperimentalForeignApi::class)
        fun start(
            handler: (ByteArray) -> ByteArray = { it },
        ): Result<UnixEchoServer, StartError> {
            val path = generateUniquePath()
            val pathBytes = path.encodeToByteArray()
            if (pathBytes.size >= SUN_PATH_CAPACITY) {
                return Err(
                    StartError(
                        step = "path",
                        errno = ENAMETOOLONG,
                        message = "path exceeds sun_path capacity ($SUN_PATH_CAPACITY bytes)",
                    ),
                )
            }

            val fd = socket(AF_UNIX, SOCK_STREAM, 0)
            if (fd < 0) {
                val e = errno
                return Err(StartError("socket", e, strerrorMessage(e)))
            }

            val bindListen = bindAndListen(fd, path, pathBytes)
            if (bindListen != null) return Err(bindListen)

            val workerState = EchoWorkerState(
                listenFd = fd,
                handler = handler,
                shutdown = AtomicInt(0),
            )
            val ref = StableRef.create(workerState)

            var createdThread: ULong = 0uL
            var createRc = 0
            memScoped {
                val threadVar = alloc<ULongVar>()
                createRc = pthread_create(
                    threadVar.ptr.reinterpret(),
                    null,
                    staticCFunction(::echoWorkerMain),
                    ref.asCPointer(),
                )
                if (createRc == 0) {
                    createdThread = threadVar.value
                }
            }
            if (createRc != 0) {
                ref.dispose()
                platform.posix.close(fd)
                unlink(path)
                return Err(
                    StartError(
                        step = "pthread_create",
                        errno = createRc,
                        message = "pthread_create failed with rc=$createRc",
                    ),
                )
            }

            return Ok(
                UnixEchoServer(
                    socketPath = path,
                    listenFd = fd,
                    workerThread = createdThread,
                    workerRef = ref,
                    state = workerState,
                ),
            )
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun bindAndListen(
            fd: Int,
            path: String,
            pathBytes: ByteArray,
        ): StartError? = memScoped {
            val addr = alloc<sockaddr_un>()
            memset(addr.ptr, 0, sizeOf<sockaddr_un>().convert())
            addr.sun_family = AF_UNIX.convert()
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
                return@memScoped StartError("bind", e, strerrorMessage(e))
            }
            if (listen(fd, BACKLOG) != 0) {
                val e = errno
                platform.posix.close(fd)
                unlink(path)
                return@memScoped StartError("listen", e, strerrorMessage(e))
            }
            null
        }

        @OptIn(ExperimentalForeignApi::class)
        private fun selfConnectToWakeAccept(path: String) {
            val fd = socket(AF_UNIX, SOCK_STREAM, 0)
            if (fd < 0) return
            memScoped {
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
                platform.posix.connect(fd, addr.ptr.reinterpret<sockaddr>(), addrLen)
            }
            platform.posix.close(fd)
        }

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

internal class EchoWorkerState(
    val listenFd: Int,
    val handler: (ByteArray) -> ByteArray,
    val shutdown: AtomicInt,
)

@OptIn(ExperimentalForeignApi::class)
private fun echoWorkerMain(arg: COpaquePointer?): COpaquePointer? {
    if (arg == null) return null
    val state = arg.asStableRef<EchoWorkerState>().get()
    runAcceptLoop(state)
    return null
}

@OptIn(ExperimentalForeignApi::class)
private fun runAcceptLoop(state: EchoWorkerState) {
    while (true) {
        val clientFd = accept(state.listenFd, null, null)
        if (clientFd < 0) return
        if (state.shutdown.value != 0) {
            platform.posix.close(clientFd)
            return
        }
        serveOne(clientFd, state.handler)
        platform.posix.close(clientFd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun serveOne(clientFd: Int, handler: (ByteArray) -> ByteArray) {
    val request = readUntilEof(clientFd) ?: return
    val response = handler(request)
    if (response.isNotEmpty()) {
        writeAll(clientFd, response)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readUntilEof(fd: Int): ByteArray? {
    val chunks = mutableListOf<ByteArray>()
    val buf = ByteArray(8192)
    while (true) {
        val n = buf.usePinned { pinned ->
            recv(fd, pinned.addressOf(0), buf.size.convert(), 0)
        }
        when {
            n == 0L -> break
            n < 0L -> return null
            else -> chunks.add(buf.copyOf(n.toInt()))
        }
    }
    val total = chunks.sumOf { it.size }
    val result = ByteArray(total)
    var pos = 0
    for (c in chunks) {
        c.copyInto(result, pos)
        pos += c.size
    }
    return result
}

@OptIn(ExperimentalForeignApi::class)
private fun writeAll(fd: Int, data: ByteArray) {
    var offset = 0
    data.usePinned { pinned ->
        while (offset < data.size) {
            val n = send(
                fd,
                pinned.addressOf(offset),
                (data.size - offset).convert(),
                0,
            )
            if (n <= 0L) return
            offset += n.toInt()
        }
    }
}
