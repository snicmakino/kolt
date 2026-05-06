@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.build.daemon.projectHashOf
import kolt.infra.eprintln
import kolt.infra.executeCommand
import kolt.infra.fileExists
import kolt.infra.net.SUN_PATH_CAPACITY
import kolt.infra.net.fillSockaddrUn
import kolt.infra.readFileAsString
import kolt.infra.writeFileAsString
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.linux.sockaddr_un
import platform.posix.AF_UNIX
import platform.posix.ECONNABORTED
import platform.posix.EINTR
import platform.posix.PATH_MAX
import platform.posix.SOCK_STREAM
import platform.posix.accept
import platform.posix.bind
import platform.posix.errno
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.listen
import platform.posix.mkdtemp
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.recv
import platform.posix.send
import platform.posix.sockaddr
import platform.posix.socket
import platform.posix.unlink

private const val GATE_ENV = "KOLT_DAEMON_JAR"
// Distinct from the top-level `FIXTURE_KOTLIN_VERSION` const in
// MultiShapeDaemonTestCoverageIT so this file's private const does not
// collide at link time.
private const val STALE_FIXTURE_KOTLIN_VERSION = "2.3.20"

// Single literal "$" token for use inside the bash heredoc raw strings so
// `$?` is not interpreted as a Kotlin template lookup.
private const val D = "$"

class StaleDaemonRecycleIT {

  // R4.1, R4.2, R5.1, R5.2: stale daemon at the project's daemon socket
  // path replies with a frame the new client cannot use. The first build
  // must surface the recycle notice and complete via subprocess fallback;
  // the second build (after the fake server has released the socket)
  // must run cleanly with no recycle notice.
  @Test
  fun staleDaemonReplyTriggersRecycleAndNextBuildSpawnsFresh() {
    if (!ensureGateOrSkip()) return

    val fixtureDir = scaffoldFixtureWithStaleSocket()
    val daemonDir = expectedDaemonDir(fixtureDir, STALE_FIXTURE_KOTLIN_VERSION)
    val socketPath = "$daemonDir/jvm-compiler-daemon-noplugins.sock"

    ensureDir(daemonDir)
    val server =
      FakeStaleDaemonServer.bind(socketPath).getOrElse {
        fail("FakeStaleDaemonServer.bind($socketPath) failed: $it")
      }

    var firstStderr = ""
    var firstExit = -1
    var secondStderr = ""
    var secondExit = -1
    try {
      firstExit = runKoltBuild(fixtureDir, "b1")
      firstStderr = readOptional(fixtureDir, "b1.stderr") ?: ""
    } finally {
      server.close()
    }

    secondExit = runKoltBuild(fixtureDir, "b2")
    secondStderr = readOptional(fixtureDir, "b2.stderr") ?: ""

    val firstStdout = readOptional(fixtureDir, "b1.stdout") ?: ""
    val secondStdout = readOptional(fixtureDir, "b2.stdout") ?: ""

    // (a) 1st build emits the stale-daemon recycle notice.
    assertTrue(
      firstStderr.contains("stale compiler daemon detected"),
      "expected stale-daemon notice on 1st build stderr; got stderr=$firstStderr",
    )
    assertTrue(
      firstStderr.contains("recycling"),
      "expected 'recycling' word in 1st build stderr; got stderr=$firstStderr",
    )

    // (b) 1st build completes via subprocess fallback with exit 0.
    assertEquals(
      0,
      firstExit,
      "1st build must exit 0 via subprocess fallback; stdout=$firstStdout stderr=$firstStderr",
    )

    // (c) 2nd build completes cleanly. Whether it spawns a fresh daemon
    // or falls back to subprocess depends on socket-release timing, so
    // assert exit-0 only — implementation details (daemon vs subprocess)
    // are intentionally not pinned (design.md §System Flows R5).
    assertEquals(0, secondExit, "2nd build must exit 0; stdout=$secondStdout stderr=$secondStderr")

    // (d) 2nd build does NOT re-emit the recycle notice. The flag is
    // reset per compile pass, but a fresh daemon (or fresh subprocess
    // path) should not trip the wire-mismatch detector again.
    assertFalse(
      secondStderr.contains("stale compiler daemon detected"),
      "2nd build must not re-emit stale-daemon notice; got stderr=$secondStderr",
    )
  }
}

// ---------- Gate / harness ----------

// Module-level so a single skip notice covers every @Test in this class
// across the JVM lifetime, mirroring MultiShapeDaemonTestCoverageIT.
private var skipNoticePrinted = false

private fun ensureGateOrSkip(): Boolean {
  val raw = getenv(GATE_ENV)?.toKString()
  if (raw.isNullOrEmpty()) {
    if (!skipNoticePrinted) {
      skipNoticePrinted = true
      eprintln("StaleDaemonRecycleIT: skipped (set $GATE_ENV to a daemon thin jar to enable)")
    }
    return false
  }
  if (!fileExists(raw)) {
    fail("$GATE_ENV points to non-existent path: $raw")
  }
  return true
}

private fun locateKoltKexe(): String {
  val cwd =
    currentWorkingDir() ?: error("StaleDaemonRecycleIT: getcwd() failed; cannot locate kolt.kexe")
  val candidates = listOf("$cwd/build/debug/kolt.kexe", "$cwd/build/release/kolt.kexe")
  return candidates.firstOrNull { fileExists(it) }
    ?: error(
      "StaleDaemonRecycleIT: kolt.kexe not built; run `kolt build` first. " +
        "Looked under: $candidates"
    )
}

private fun currentWorkingDir(): String? = memScoped {
  val buf = allocArray<ByteVar>(PATH_MAX)
  getcwd(buf, PATH_MAX.toULong())?.toKString()
}

private fun createTempDir(prefix: String): String {
  val template = "/tmp/${prefix}XXXXXX"
  val buf = template.encodeToByteArray().copyOf(template.length + 1)
  buf.usePinned { pinned ->
    val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed for prefix '$prefix'")
    return result.toKString()
  }
}

private fun scaffoldFixtureWithStaleSocket(): String {
  val dir = createTempDir("kolt-it-stale-")
  writeFileAsString("$dir/kolt.toml", FIXTURE_TOML).getOrElse {
    error("write kolt.toml: ${it.path}")
  }
  val mainDir = "$dir/src/main/kotlin"
  executeCommand(listOf("mkdir", "-p", mainDir)).getOrElse { error("mkdir main: $it") }
  writeFileAsString("$mainDir/Main.kt", FIXTURE_MAIN).getOrElse {
    error("write Main.kt: ${it.path}")
  }
  return dir
}

private fun ensureDir(path: String) {
  executeCommand(listOf("mkdir", "-p", path)).getOrElse { error("mkdir -p $path: $it") }
}

// Mirrors KoltPaths.daemonDir layout: `$HOME/.kolt/daemon/<projectHash>/<kotlinVersion>`.
// Pinning the algorithm here means a drift in production projectHashOf or
// path layout fails this test loud rather than silently bypassing the
// fake server.
private fun expectedDaemonDir(absProjectPath: String, kotlinVersion: String): String {
  val home = getenv("HOME")?.toKString() ?: fail("HOME env var not set; cannot resolve daemon dir")
  val projectHash = projectHashOf(absProjectPath)
  return "$home/.kolt/daemon/$projectHash/$kotlinVersion"
}

private fun runKoltBuild(fixtureDir: String, fileStem: String): Int {
  val kolt = locateKoltKexe()
  val daemonJar =
    getenv(GATE_ENV)?.toKString()
      ?: error("$GATE_ENV must be set when runKoltBuild runs (gate already passed)")
  val script =
    """
        set -u
        cd "$fixtureDir"
        "$kolt" build > $fileStem.stdout 2> $fileStem.stderr
        echo $D? > $fileStem.exit
        """
      .trimIndent()
  executeCommand(listOf("bash", "-c", script), extraEnv = mapOf(GATE_ENV to daemonJar)).getOrElse {
    // executeCommand returns Err for any non-zero exit including the kolt
    // exit code — we proceed and read the exit-code file harness wrote.
  }
  val raw =
    readFileAsString("$fixtureDir/$fileStem.exit").getOrElse {
      error("missing $fixtureDir/$fileStem.exit — harness did not record an exit code")
    }
  return raw.trim().toIntOrNull()
    ?: error("could not parse exit code from $fixtureDir/$fileStem.exit: '$raw'")
}

private fun readOptional(dir: String, name: String): String? {
  val path = "$dir/$name"
  if (!fileExists(path)) return null
  return readFileAsString(path).getOrElse {
    return null
  }
}

// ---------- Fake stale daemon ----------

// Minimal AF_UNIX server that accepts one connection at a time, reads
// one Compile frame (4-byte big-endian length + JSON body), then writes
// back a frame whose JSON body is `{"type":"Pong"}`. The new client
// successfully decodes the reply but classifies the unexpected variant
// as a wire mismatch (DaemonCompilerBackend.mapReplyToOutcome).
//
// Patterned on UnixEchoServer (kolt.infra.net.testfixture) but tailored
// to a request/reply wire instead of read-until-EOF echo. Lives inline
// per the IT boundary: no shared fixture module is added.
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private class FakeStaleDaemonServer
private constructor(
  private val socketPath: String,
  private val listenFd: Int,
  private val workerThread: ULong,
  private val workerRef: StableRef<FakeServerState>,
  private val state: FakeServerState,
) : AutoCloseable {
  private var closed = false

  override fun close() {
    if (closed) return
    closed = true
    state.shutdown.value = 1
    // close(listenFd) will not unblock a thread parked in accept(). Force
    // an extra accept() return by self-connecting; the worker re-checks
    // the shutdown flag on the next loop iteration and exits.
    selfConnectToWakeAccept(socketPath)
    pthread_join(workerThread, null)
    workerRef.dispose()
    platform.posix.close(listenFd)
    unlink(socketPath)
  }

  companion object {
    private const val BACKLOG = 8

    fun bind(socketPath: String): Result<FakeStaleDaemonServer, String> {
      val pathBytes = socketPath.encodeToByteArray()
      if (pathBytes.size >= SUN_PATH_CAPACITY) {
        return Err("path exceeds sun_path capacity ($SUN_PATH_CAPACITY): $socketPath")
      }

      // A previous test run or an external daemon may have left a socket
      // file at this path; unlink it so bind() can succeed.
      unlink(socketPath)

      val fd = socket(AF_UNIX, SOCK_STREAM, 0)
      if (fd < 0) return Err("socket() failed errno=$errno")

      val bindErr = doBindAndListen(fd, socketPath, pathBytes)
      if (bindErr != null) return Err(bindErr)

      val workerState = FakeServerState(listenFd = fd, shutdown = AtomicInt(0))
      val ref = StableRef.create(workerState)

      var createdThread: ULong = 0uL
      var createRc = 0
      memScoped {
        val threadVar = alloc<ULongVar>()
        createRc =
          pthread_create(
            threadVar.ptr.reinterpret(),
            null,
            staticCFunction(::fakeServerWorkerMain),
            ref.asCPointer(),
          )
        if (createRc == 0) {
          createdThread = threadVar.value
        }
      }
      if (createRc != 0) {
        ref.dispose()
        platform.posix.close(fd)
        unlink(socketPath)
        return Err("pthread_create failed rc=$createRc")
      }

      return Ok(
        FakeStaleDaemonServer(
          socketPath = socketPath,
          listenFd = fd,
          workerThread = createdThread,
          workerRef = ref,
          state = workerState,
        )
      )
    }

    private fun doBindAndListen(fd: Int, path: String, pathBytes: ByteArray): String? = memScoped {
      val addr = alloc<sockaddr_un>()
      val addrLen = fillSockaddrUn(addr, pathBytes)
      if (bind(fd, addr.ptr.reinterpret<sockaddr>(), addrLen) != 0) {
        val e = errno
        platform.posix.close(fd)
        return@memScoped "bind($path) failed errno=$e"
      }
      if (listen(fd, BACKLOG) != 0) {
        val e = errno
        platform.posix.close(fd)
        unlink(path)
        return@memScoped "listen($path) failed errno=$e"
      }
      null
    }

    private fun selfConnectToWakeAccept(path: String) {
      val fd = socket(AF_UNIX, SOCK_STREAM, 0)
      if (fd < 0) return
      memScoped {
        val addr = alloc<sockaddr_un>()
        val addrLen = fillSockaddrUn(addr, path.encodeToByteArray())
        platform.posix.connect(fd, addr.ptr.reinterpret<sockaddr>(), addrLen)
      }
      platform.posix.close(fd)
    }
  }
}

internal class FakeServerState(val listenFd: Int, val shutdown: AtomicInt)

// JSON body of the unexpected-variant reply. The new client decodes this
// successfully, but `mapReplyToOutcome` classifies a Pong response to a
// Compile request as `BackendUnavailable.WireMismatch`.
private val PONG_JSON_BODY: ByteArray = "{\"type\":\"Pong\"}".encodeToByteArray()

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun fakeServerWorkerMain(arg: COpaquePointer?): COpaquePointer? {
  if (arg == null) return null
  val state = arg.asStableRef<FakeServerState>().get()
  while (true) {
    if (state.shutdown.value != 0) return null
    val clientFd = accept(state.listenFd, null, null)
    if (clientFd < 0) {
      val e = errno
      if (e == EINTR || e == ECONNABORTED) continue
      return null
    }
    if (state.shutdown.value != 0) {
      platform.posix.close(clientFd)
      return null
    }
    handleClient(clientFd)
    platform.posix.close(clientFd)
  }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun handleClient(fd: Int) {
  // Read the 4-byte big-endian length prefix.
  val header = recvExactBytes(fd, 4) ?: return
  val len =
    ((header[0].toInt() and 0xff) shl 24) or
      ((header[1].toInt() and 0xff) shl 16) or
      ((header[2].toInt() and 0xff) shl 8) or
      (header[3].toInt() and 0xff)
  if (len < 0 || len > 64 * 1024 * 1024) return
  // Drain the request body so the kernel side has nothing held; we do
  // not deserialise — any Compile JSON is fine.
  recvExactBytes(fd, len) ?: return

  val body = PONG_JSON_BODY
  val replyHeader = ByteArray(4)
  val bodyLen = body.size
  replyHeader[0] = ((bodyLen ushr 24) and 0xff).toByte()
  replyHeader[1] = ((bodyLen ushr 16) and 0xff).toByte()
  replyHeader[2] = ((bodyLen ushr 8) and 0xff).toByte()
  replyHeader[3] = (bodyLen and 0xff).toByte()
  if (!sendAllBytes(fd, replyHeader)) return
  sendAllBytes(fd, body)

  // The new client may follow the WireMismatch reply by sending a
  // best-effort Shutdown frame on the same connection. Drain whatever
  // arrives until the peer half-closes so the writer never sees
  // EPIPE / SIGPIPE before our close().
  drainUntilEof(fd)
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun recvExactBytes(fd: Int, n: Int): ByteArray? {
  if (n == 0) return ByteArray(0)
  val buf = ByteArray(n)
  var offset = 0
  buf.usePinned { pinned ->
    while (offset < n) {
      val got = recv(fd, pinned.addressOf(offset), (n - offset).convert(), 0)
      if (got < 0L) {
        val e = errno
        if (e == EINTR) continue
        return null
      }
      if (got == 0L) return null
      offset += got.toInt()
    }
  }
  return buf
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun sendAllBytes(fd: Int, data: ByteArray): Boolean {
  if (data.isEmpty()) return true
  var offset = 0
  data.usePinned { pinned ->
    while (offset < data.size) {
      val n = send(fd, pinned.addressOf(offset), (data.size - offset).convert(), 0)
      if (n < 0L) {
        val e = errno
        if (e == EINTR) continue
        return false
      }
      if (n == 0L) return false
      offset += n.toInt()
    }
  }
  return true
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun drainUntilEof(fd: Int) {
  val buf = ByteArray(4096)
  buf.usePinned { pinned ->
    while (true) {
      val got = recv(fd, pinned.addressOf(0), buf.size.convert(), 0)
      if (got < 0L) {
        val e = errno
        if (e == EINTR) continue
        return
      }
      if (got == 0L) return
    }
  }
}

// ---------- Fixture sources ----------

private val FIXTURE_TOML =
  """
        name = "stale-daemon-recycle-it"
        version = "0.0.1"
        kind = "lib"

        [kotlin]
        version = "$STALE_FIXTURE_KOTLIN_VERSION"

        [build]
        target = "jvm"
        jvm_target = "21"
        sources = ["src/main/kotlin"]
        """
    .trimIndent()

private val FIXTURE_MAIN =
  """
        package stale.daemon.recycle.it

        fun greet(): String = "hello-stale-daemon"
        """
    .trimIndent()
