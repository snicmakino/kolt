package kolt.infra.testfixture

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.ULongVar
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
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.ECONNABORTED
import platform.posix.EINTR
import platform.posix.INADDR_LOOPBACK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.errno
import platform.posix.fputs
import platform.posix.getsockname
import platform.posix.htonl
import platform.posix.htons
import platform.posix.listen
import platform.posix.memset
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.stderr
import platform.posix.strerror

// Loopback HTTP/1.1 stub server for downloader-layer tests. Mirrors
// UnixEchoServer's pthread + StableRef + AtomicInt shutdown pattern but
// binds AF_INET on 127.0.0.1:0 (kernel-assigned port) and speaks just
// enough HTTP to drive a single libcurl GET: read request bytes until
// the CRLFCRLF terminator, write a canned response, then exit.
//
// Single-shot: each instance accepts exactly one connection and then
// stops; tests that need a redirect chain spin up two instances and
// chain them via Response.Redirect.
@OptIn(ExperimentalForeignApi::class)
class LoopbackHttpServer
private constructor(
  val port: Int,
  private val listenFd: Int,
  private val workerThread: ULong,
  private val workerRef: StableRef<WorkerState>,
  private val state: WorkerState,
) : AutoCloseable {
  private var closed = false

  override fun close() {
    if (closed) return
    closed = true
    state.shutdown.value = 1
    // Force any thread parked in accept() to return so the worker can
    // observe `shutdown` and exit; close() alone does not reliably
    // unblock accept() on Linux. A connect() to our own port wakes it.
    selfConnectToWakeAccept(port)
    pthread_join(workerThread, null)
    workerRef.dispose()
    platform.posix.close(listenFd)
  }

  // Returns the captured headers from the single served request, blocking
  // until the worker thread has finished writing the access log. The
  // pthread_join in close() guarantees memory visibility for tests that
  // only inspect after `use {}` exits, but tests typically read the log
  // while still inside use {} — busy-wait on the completion flag here.
  fun awaitAccessLog(timeoutMillis: Long = 5_000): AccessLog {
    val deadline = currentMonoMillis() + timeoutMillis
    while (state.completed.value == 0) {
      if (currentMonoMillis() > deadline) {
        return AccessLog(rawHeaderBlock = "<timeout waiting for request>", headers = emptyMap())
      }
      sleepMillis(5)
    }
    return state.accessLog.value
      ?: AccessLog(rawHeaderBlock = "<no request received>", headers = emptyMap())
  }

  // What the server should write back to the client. The test pins the
  // exact shape (200 with a small body or 302 with a Location to another
  // host:port). Each Response renders to a single ByteArray that the
  // worker writes verbatim — keeps the wire format inspectable.
  sealed class Response {
    abstract fun render(): ByteArray

    data class Ok(val body: String) : Response() {
      override fun render(): ByteArray {
        val bodyBytes = body.encodeToByteArray()
        val head =
          "HTTP/1.1 200 OK\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        return head.encodeToByteArray() + bodyBytes
      }
    }

    data class Redirect(val location: String) : Response() {
      override fun render(): ByteArray {
        val head =
          "HTTP/1.1 302 Found\r\n" +
            "Location: $location\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        return head.encodeToByteArray()
      }
    }

    // Arbitrary-status response, used by RepositoryAuthFailureTest to drive
    // 401 / 403 / 404 paths through `downloadFromRepositories`. The `reason`
    // is the HTTP/1.1 reason phrase ("Unauthorized" / "Forbidden" / etc.);
    // it's wire-only — libcurl doesn't surface it back to callers, but it
    // keeps the response line spec-conformant.
    data class WithStatus(val code: Int, val reason: String, val body: String) : Response() {
      override fun render(): ByteArray {
        val bodyBytes = body.encodeToByteArray()
        val head =
          "HTTP/1.1 $code $reason\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        return head.encodeToByteArray() + bodyBytes
      }
    }

    companion object {
      fun ok(body: String): Response = Ok(body)

      fun redirect(location: String): Response = Redirect(location)

      fun withStatus(code: Int, reason: String, body: String): Response =
        WithStatus(code, reason, body)
    }
  }

  // Captured request preview. `rawHeaderBlock` is the verbatim request
  // line plus headers as the wire delivered them, which lets tests
  // assert on header presence/absence with simple `contains` checks.
  // `headers` is a name -> value map (case-insensitive lookup helpers
  // below) for tests that want the exact value of a single header.
  data class AccessLog(val rawHeaderBlock: String, val headers: Map<String, String>) {
    fun authorization(): String? =
      headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value
  }

  data class StartError(val step: String, val errno: Int, val message: String)

  companion object {
    private const val BACKLOG = 1

    fun start(response: Response): Result<LoopbackHttpServer, StartError> {
      val responseBytes = response.render()

      val fd = socket(AF_INET, SOCK_STREAM, 0)
      if (fd < 0) {
        val e = errno
        return Err(StartError("socket", e, strerrorMessage(e)))
      }

      // SO_REUSEADDR lets the kernel rebind ports left in TIME_WAIT from
      // previous test runs without a 30-60s delay; the port itself is
      // ephemeral so collisions are unlikely, but the option is cheap.
      memScoped {
        val one = alloc<IntVar>().apply { value = 1 }
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
      }

      val boundPort = bindEphemeralAndListen(fd)
      if (boundPort is BindResult.Failed) {
        platform.posix.close(fd)
        return Err(boundPort.error)
      }
      val port = (boundPort as BindResult.Bound).port

      val workerState =
        WorkerState(
          listenFd = fd,
          response = responseBytes,
          shutdown = AtomicInt(0),
          completed = AtomicInt(0),
          accessLog = AtomicReference<AccessLog?>(null),
        )
      val ref = StableRef.create(workerState)

      var createdThread: ULong = 0uL
      var createRc = 0
      memScoped {
        val threadVar = alloc<ULongVar>()
        createRc =
          pthread_create(
            threadVar.ptr.reinterpret(),
            null,
            staticCFunction(::loopbackWorkerMain),
            ref.asCPointer(),
          )
        if (createRc == 0) {
          createdThread = threadVar.value
        }
      }
      if (createRc != 0) {
        ref.dispose()
        platform.posix.close(fd)
        return Err(
          StartError(
            step = "pthread_create",
            errno = createRc,
            message = "pthread_create failed with rc=$createRc",
          )
        )
      }

      return Ok(
        LoopbackHttpServer(
          port = port,
          listenFd = fd,
          workerThread = createdThread,
          workerRef = ref,
          state = workerState,
        )
      )
    }

    private sealed class BindResult {
      data class Bound(val port: Int) : BindResult()

      data class Failed(val error: StartError) : BindResult()
    }

    private fun bindEphemeralAndListen(fd: Int): BindResult = memScoped {
      val addr = alloc<sockaddr_in>()
      memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
      addr.sin_family = AF_INET.convert()
      addr.sin_port = htons(0u)
      addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK.convert())

      if (bind(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) != 0) {
        val e = errno
        return@memScoped BindResult.Failed(StartError("bind", e, strerrorMessage(e)))
      }
      if (listen(fd, BACKLOG) != 0) {
        val e = errno
        return@memScoped BindResult.Failed(StartError("listen", e, strerrorMessage(e)))
      }

      val boundAddr = alloc<sockaddr_in>()
      val boundLen = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
      if (getsockname(fd, boundAddr.ptr.reinterpret<sockaddr>(), boundLen.ptr) != 0) {
        val e = errno
        return@memScoped BindResult.Failed(StartError("getsockname", e, strerrorMessage(e)))
      }
      val port = ntohs(boundAddr.sin_port).toInt() and 0xFFFF
      BindResult.Bound(port)
    }

    private fun selfConnectToWakeAccept(port: Int) {
      val fd = socket(AF_INET, SOCK_STREAM, 0)
      if (fd < 0) return
      memScoped {
        val addr = alloc<sockaddr_in>()
        memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
        addr.sin_family = AF_INET.convert()
        addr.sin_port = htons(port.toUShort())
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK.convert())
        platform.posix.connect(
          fd,
          addr.ptr.reinterpret<sockaddr>(),
          sizeOf<sockaddr_in>().convert(),
        )
      }
      platform.posix.close(fd)
    }

    private fun strerrorMessage(e: Int): String = strerror(e)?.toKString() ?: "errno=$e"
  }
}

// htons is exported by Kotlin/Native posix but ntohs is not in all
// host platform klibs; on little-endian Linux both are identical byte
// swaps so we use htons as the symmetric inverse.
@OptIn(ExperimentalForeignApi::class) private fun ntohs(value: UShort): UShort = htons(value)

internal class WorkerState(
  val listenFd: Int,
  val response: ByteArray,
  val shutdown: AtomicInt,
  val completed: AtomicInt,
  val accessLog: AtomicReference<LoopbackHttpServer.AccessLog?>,
)

@OptIn(ExperimentalForeignApi::class)
private fun loopbackWorkerMain(arg: COpaquePointer?): COpaquePointer? {
  if (arg == null) return null
  val state = arg.asStableRef<WorkerState>().get()
  try {
    runAcceptOnce(state)
  } finally {
    state.completed.value = 1
  }
  return null
}

@OptIn(ExperimentalForeignApi::class)
private fun runAcceptOnce(state: WorkerState) {
  while (true) {
    if (state.shutdown.value != 0) return
    val clientFd = accept(state.listenFd, null, null)
    if (clientFd < 0) {
      val e = errno
      if (e == EINTR || e == ECONNABORTED) continue
      if (state.shutdown.value != 0) return
      logStderr("LoopbackHttpServer: accept failed, errno=$e")
      return
    }
    if (state.shutdown.value != 0) {
      platform.posix.close(clientFd)
      return
    }
    serveOne(clientFd, state)
    platform.posix.close(clientFd)
    return
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun serveOne(clientFd: Int, state: WorkerState) {
  val headBlock = readRequestHead(clientFd) ?: return
  val headers = parseHeaders(headBlock)
  state.accessLog.value =
    LoopbackHttpServer.AccessLog(rawHeaderBlock = headBlock, headers = headers)
  writeAll(clientFd, state.response)
}

// Reads bytes from clientFd until the CRLFCRLF terminator that ends the
// HTTP request head. Returns the head as a String (decoded UTF-8) or
// null if the client closed early / a recv error occurred. We don't
// read the body: GET requests don't have one, and libcurl on the
// client side won't send body bytes for a plain GET.
@OptIn(ExperimentalForeignApi::class)
private fun readRequestHead(fd: Int): String? {
  val collected = mutableListOf<ByteArray>()
  val buf = ByteArray(4096)
  var totalLen = 0
  while (totalLen < MAX_REQUEST_HEAD_BYTES) {
    val n = buf.usePinned { pinned -> recv(fd, pinned.addressOf(0), buf.size.convert(), 0) }
    if (n == 0L) break
    if (n < 0L) {
      val e = errno
      if (e == EINTR) continue
      logStderr("LoopbackHttpServer: recv failed, errno=$e")
      return null
    }
    val chunk = buf.copyOf(n.toInt())
    collected.add(chunk)
    totalLen += chunk.size
    // Check terminator across the assembled prefix. CRLFCRLF can
    // straddle chunk boundaries, so concatenate before scanning.
    val assembled = concatAll(collected)
    val terminator = indexOfCRLFCRLF(assembled)
    if (terminator >= 0) {
      return assembled.decodeToString(0, terminator)
    }
  }
  return null
}

private const val MAX_REQUEST_HEAD_BYTES = 64 * 1024

private fun concatAll(parts: List<ByteArray>): ByteArray {
  val total = parts.sumOf { it.size }
  val out = ByteArray(total)
  var pos = 0
  for (p in parts) {
    p.copyInto(out, pos)
    pos += p.size
  }
  return out
}

private fun indexOfCRLFCRLF(data: ByteArray): Int {
  val needle = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A)
  outer@ for (i in 0..data.size - needle.size) {
    for (j in needle.indices) {
      if (data[i + j] != needle[j]) continue@outer
    }
    return i
  }
  return -1
}

private fun parseHeaders(headBlock: String): Map<String, String> {
  val out = mutableMapOf<String, String>()
  val lines = headBlock.split("\r\n")
  // Skip request line (index 0); remaining are "Name: Value" pairs.
  for (i in 1 until lines.size) {
    val line = lines[i]
    if (line.isEmpty()) continue
    val colon = line.indexOf(':')
    if (colon < 0) continue
    val name = line.substring(0, colon).trim()
    val value = line.substring(colon + 1).trim()
    out[name] = value
  }
  return out
}

@OptIn(ExperimentalForeignApi::class)
private fun writeAll(fd: Int, data: ByteArray) {
  var offset = 0
  data.usePinned { pinned ->
    while (offset < data.size) {
      val n = send(fd, pinned.addressOf(offset), (data.size - offset).convert(), 0)
      if (n < 0L) {
        val e = errno
        if (e == EINTR) continue
        logStderr("LoopbackHttpServer: send failed at $offset/${data.size}, errno=$e")
        return
      }
      if (n == 0L) {
        logStderr("LoopbackHttpServer: send returned 0 at $offset/${data.size}")
        return
      }
      offset += n.toInt()
    }
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun logStderr(message: String) {
  fputs("$message\n", stderr)
}

@OptIn(ExperimentalForeignApi::class)
private fun currentMonoMillis(): Long {
  memScoped {
    val ts = alloc<platform.posix.timespec>()
    platform.posix.clock_gettime(platform.posix.CLOCK_MONOTONIC.convert(), ts.ptr)
    return ts.tv_sec * 1000L + ts.tv_nsec / 1_000_000L
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun sleepMillis(ms: Long) {
  memScoped {
    val ts = alloc<platform.posix.timespec>()
    ts.tv_sec = ms / 1000L
    ts.tv_nsec = (ms % 1000L) * 1_000_000L
    platform.posix.nanosleep(ts.ptr, null)
  }
}
