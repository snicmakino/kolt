package kolt.build.nativedaemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.build.NativeCompileError
import kolt.infra.ProcessError
import kolt.infra.net.UnixSocketError
import kolt.nativedaemon.wire.FrameError
import kolt.nativedaemon.wire.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeConnection(
  val reply: Result<Message, FrameError> =
    Ok(Message.NativeCompileResult(exitCode = 0, stderr = "")),
  val sendResult: Result<Unit, FrameError> = Ok(Unit),
) : NativeDaemonConnection {
  var lastSent: Message? = null
  var closed = false

  override fun sendRequest(message: Message): Result<Unit, FrameError> {
    lastSent = message
    return sendResult
  }

  override fun receiveReply(): Result<Message, FrameError> = reply

  override fun close() {
    closed = true
  }
}

private class FakeClock(var nowMs: Long = 0) {
  val clock: () -> Long = { nowMs }
  val sleeper: (Int) -> Unit = { ms -> nowMs += ms.toLong() }
}

private fun newBackend(
  connector: NativeDaemonConnector,
  spawner: NativeDaemonSpawner = { _, _ -> Ok(Unit) },
  clock: FakeClock = FakeClock(),
  onSpawn: () -> Unit = {},
): NativeDaemonBackend =
  NativeDaemonBackend(
    javaBin = "/opt/jdk/bin/java",
    daemonLaunchArgs = listOf("@/opt/kolt/libexec/classpath/kolt-native-compiler-daemon.argfile"),
    konancJar = "/opt/konan/konan/lib/kotlin-native-compiler-embeddable.jar",
    konanHome = "/opt/konan",
    socketPath = "/tmp/kolt-native-daemon-test.sock",
    logPath = "/tmp/kolt-native-daemon-test.log",
    connector = connector,
    spawner = spawner,
    clockMs = clock.clock,
    sleeper = clock.sleeper,
    onSpawn = onSpawn,
  )

private val sampleArgs =
  listOf(
    "-target",
    "linux_x64",
    "src/Main.kt",
    "-p",
    "library",
    "-nopack",
    "-Xenable-incremental-compilation",
    "-Xic-cache-dir=build/debug/.ic-cache",
    "-o",
    "build/debug/m-klib",
  )

class NativeDaemonBackendHappyPathTest {

  @Test
  fun successfulCompileMapsResultToOutcome() {
    val fake =
      FakeConnection(
        reply = Ok(Message.NativeCompileResult(exitCode = 0, stderr = "warning: deprecated"))
      )
    val backend = newBackend(connector = { Ok(fake) })

    val result = backend.compile(sampleArgs)

    val outcome = assertNotNull(result.get())
    assertEquals("warning: deprecated", outcome.stderr)
    // Connection is closed even on success (AutoCloseable.use).
    assertTrue(fake.closed)
  }

  @Test
  fun argsAreForwardedUnchangedOnTheWire() {
    val fake = FakeConnection()
    val backend = newBackend(connector = { Ok(fake) })

    backend.compile(sampleArgs)

    val sent = assertIs<Message.NativeCompile>(fake.lastSent)
    assertEquals(sampleArgs, sent.args)
  }

  @Test
  fun nonZeroExitCodeMapsToCompilationFailed() {
    val fake =
      FakeConnection(
        reply =
          Ok(Message.NativeCompileResult(exitCode = 1, stderr = "error: unresolved reference: foo"))
      )
    val backend = newBackend(connector = { Ok(fake) })

    val err = backend.compile(sampleArgs).getError()

    val failed = assertIs<NativeCompileError.CompilationFailed>(err)
    assertEquals(1, failed.exitCode)
    assertTrue(failed.stderr.contains("unresolved reference"))
  }

  @Test
  fun exitCode2WithProtocolErrorPrefixIsDaemonSideFailure() {
    // DaemonServer.writeProtocolError emits this shape on wire-protocol
    // violations (EOF mid-frame, client-sent server-only message). The
    // client must treat it as BackendUnavailable so the fallback kicks in.
    val fake =
      FakeConnection(
        reply =
          Ok(
            Message.NativeCompileResult(
              exitCode = 2,
              stderr = "protocol error: client sent server-only message NativeCompileResult",
            )
          )
      )
    val backend = newBackend(connector = { Ok(fake) })

    val err = backend.compile(sampleArgs).getError()

    val unavailable = assertIs<NativeCompileError.BackendUnavailable.Other>(err)
    assertTrue(unavailable.detail.contains("native daemon failure"))
  }

  @Test
  fun exitCode2WithInvocationFailedPrefixIsDaemonSideFailure() {
    // DaemonServer.errorToReply emits this shape when the reflective
    // K2Native.exec throws. Same fallback treatment as a protocol error.
    val fake =
      FakeConnection(
        reply =
          Ok(
            Message.NativeCompileResult(
              exitCode = 2,
              stderr = "native compiler invocation failed: classloader cracked",
            )
          )
      )
    val backend = newBackend(connector = { Ok(fake) })

    val err = backend.compile(sampleArgs).getError()

    assertIs<NativeCompileError.BackendUnavailable.Other>(err)
  }

  @Test
  fun exitCode2WithoutKnownPrefixIsKonancInternalError() {
    // `ExitCode.INTERNAL_ERROR.code == 2`. If konanc itself returns it
    // (JVM OOM, analyzer bug, etc.), the stderr will NOT start with one
    // of our daemon-side prefixes — pass it through as CompilationFailed
    // so the subprocess path does not waste a retry on the same bug.
    val fake =
      FakeConnection(
        reply =
          Ok(
            Message.NativeCompileResult(
              exitCode = 2,
              stderr = "error: unexpected internal error: java.lang.NullPointerException at ...",
            )
          )
      )
    val backend = newBackend(connector = { Ok(fake) })

    val err = backend.compile(sampleArgs).getError()

    val failed = assertIs<NativeCompileError.CompilationFailed>(err)
    assertEquals(2, failed.exitCode)
  }

  @Test
  fun unexpectedReplyTypeBecomesBackendUnavailable() {
    val fake = FakeConnection(reply = Ok(Message.Pong))
    val backend = newBackend(connector = { Ok(fake) })

    val err = backend.compile(sampleArgs).getError()

    val unavailable = assertIs<NativeCompileError.BackendUnavailable.Other>(err)
    assertTrue(unavailable.detail.contains("unexpected reply type"))
  }

  @Test
  fun sendErrorMalformedMapsToInternalMisuse() {
    val fake = FakeConnection(sendResult = Err(FrameError.Malformed("oversize body")))
    val backend = newBackend(connector = { Ok(fake) })

    val err = backend.compile(sampleArgs).getError()

    val misuse = assertIs<NativeCompileError.InternalMisuse>(err)
    assertTrue(misuse.detail.contains("oversize body"))
  }

  @Test
  fun receiveErrorTransportMapsToBackendUnavailable() {
    val fake =
      FakeConnection(
        reply =
          Err(FrameError.Transport(UnixSocketError.RecvFailed(errno = 5, message = "I/O error")))
      )
    val backend = newBackend(connector = { Ok(fake) })

    val err = backend.compile(sampleArgs).getError()

    assertIs<NativeCompileError.BackendUnavailable.Other>(err)
  }
}

class NativeDaemonBackendConnectAndSpawnTest {

  @Test
  fun noSpawnOnFirstSuccessfulConnect() {
    var spawnCount = 0
    val fake = FakeConnection()
    val backend =
      newBackend(
        connector = { Ok(fake) },
        spawner = { _, _ ->
          spawnCount++
          Ok(Unit)
        },
      )

    backend.compile(sampleArgs)

    assertEquals(0, spawnCount, "spawner must not fire when first connect succeeds")
  }

  @Test
  fun enoentTriggersSpawnThenRetryConnect() {
    var callCount = 0
    val fake = FakeConnection()
    val backend =
      newBackend(
        connector = { _ ->
          callCount++
          if (callCount == 1) {
            Err(UnixSocketError.ConnectFailed(path = "/tmp/s", errno = 2, message = "ENOENT"))
          } else {
            Ok(fake)
          }
        }
      )

    val result = backend.compile(sampleArgs)

    assertNotNull(result.get())
    assertEquals(2, callCount, "expected one failed + one successful connect")
  }

  @Test
  fun econnrefusedTriggersSpawnThenRetryConnect() {
    var callCount = 0
    val fake = FakeConnection()
    val backend =
      newBackend(
        connector = { _ ->
          callCount++
          if (callCount == 1) {
            Err(
              UnixSocketError.ConnectFailed(path = "/tmp/s", errno = 111, message = "ECONNREFUSED")
            )
          } else {
            Ok(fake)
          }
        }
      )

    val result = backend.compile(sampleArgs)

    assertNotNull(result.get())
  }

  @Test
  fun fatalErrnoReturnsImmediatelyWithoutSpawn() {
    var spawnCount = 0
    val backend =
      newBackend(
        connector = { _ ->
          Err(UnixSocketError.ConnectFailed(path = "/tmp/s", errno = 13, message = "EACCES"))
        },
        spawner = { _, _ ->
          spawnCount++
          Ok(Unit)
        },
      )

    val err = backend.compile(sampleArgs).getError()

    assertIs<NativeCompileError.BackendUnavailable.Other>(err)
    assertEquals(0, spawnCount, "fatal errno must not trigger a spawn attempt")
  }

  @Test
  fun invalidArgumentMapsToInternalMisuse() {
    val backend =
      newBackend(
        connector = { _ ->
          Err(UnixSocketError.InvalidArgument("socket path exceeds SUN_PATH_CAPACITY"))
        }
      )

    val err = backend.compile(sampleArgs).getError()

    val misuse = assertIs<NativeCompileError.InternalMisuse>(err)
    assertTrue(misuse.detail.contains("SUN_PATH_CAPACITY"))
  }

  @Test
  fun spawnFailureSurfacesAsBackendUnavailable() {
    val backend =
      newBackend(
        connector = { _ ->
          Err(UnixSocketError.ConnectFailed(path = "/tmp/s", errno = 2, message = "ENOENT"))
        },
        spawner = { _, _ -> Err(ProcessError.ForkFailed) },
      )

    val err = backend.compile(sampleArgs).getError()

    val unavailable = assertIs<NativeCompileError.BackendUnavailable.Other>(err)
    assertTrue(unavailable.detail.contains("spawn failed"))
  }

  @Test
  fun retryBudgetExhaustionReportsLastError() {
    val clock = FakeClock()
    var calls = 0
    val backend =
      newBackend(
        connector = { _ ->
          calls++
          Err(UnixSocketError.ConnectFailed(path = "/tmp/s", errno = 2, message = "ENOENT"))
        },
        clock = clock,
      )

    val err = backend.compile(sampleArgs).getError()

    val unavailable = assertIs<NativeCompileError.BackendUnavailable.Other>(err)
    assertTrue(
      unavailable.detail.contains("within 10000ms"),
      "expected budget mention, got: ${unavailable.detail}",
    )
    assertTrue(calls > 1, "expected multiple retry attempts before giving up")
  }

  @Test
  fun spawnArgvContainsXmx4GAndDaemonFlags() {
    var capturedArgv: List<String>? = null
    val backend =
      newBackend(
        connector = { _ ->
          Err(UnixSocketError.ConnectFailed(path = "/tmp/s", errno = 2, message = "ENOENT"))
        },
        spawner = { argv, _ ->
          capturedArgv = argv
          Err(ProcessError.ForkFailed)
        },
      )

    backend.compile(sampleArgs)

    val argv = assertNotNull(capturedArgv)
    // Pin the literal so a typo in the constant is caught here.
    assertEquals("-Xmx4G", NativeDaemonBackend.HEAP_CEILING_XMX)
    assertTrue(NativeDaemonBackend.HEAP_CEILING_XMX in argv, "missing heap ceiling: $argv")
    // ADR 0024 §8: the three daemon-side CLI flags are non-negotiable.
    assertTrue("--socket" in argv)
    assertTrue("--konanc-jar" in argv)
    assertTrue("--konan-home" in argv)
    assertEquals(
      "/opt/konan/konan/lib/kotlin-native-compiler-embeddable.jar",
      argv[argv.indexOf("--konanc-jar") + 1],
    )
    assertEquals("/opt/konan", argv[argv.indexOf("--konan-home") + 1])
    assertEquals("/opt/jdk/bin/java", argv.first())
  }

  @Test
  fun onSpawnFiresOnlyWhenSpawnIsTriggered() {
    var hits = 0
    val fake = FakeConnection()

    // Success on first connect: no onSpawn.
    newBackend(connector = { Ok(fake) }, onSpawn = { hits++ }).compile(sampleArgs)
    assertEquals(0, hits)

    // ENOENT first, then Ok: onSpawn fires exactly once.
    var callCount = 0
    newBackend(
        connector = { _ ->
          callCount++
          if (callCount == 1)
            Err(UnixSocketError.ConnectFailed("/tmp/s", errno = 2, message = "ENOENT"))
          else Ok(fake)
        },
        onSpawn = { hits++ },
      )
      .compile(sampleArgs)
    assertEquals(1, hits)
  }

  @Test
  fun exponentialBackoffDoublesUpToMax() {
    // Capture each sleep duration so we can pin the 10 -> 20 -> 40 -> 80
    // -> 160 -> 200 (cap) progression from ADR 0024's spike budget.
    val slept = mutableListOf<Int>()
    val clock = FakeClock()
    val budgetOverflowSleeper: (Int) -> Unit = { ms ->
      slept += ms
      clock.nowMs += ms.toLong()
    }
    val backend =
      NativeDaemonBackend(
        javaBin = "/java",
        daemonLaunchArgs = listOf("-cp", "/d.jar", "kolt.nativedaemon.MainKt"),
        konancJar = "/k.jar",
        konanHome = "/k",
        socketPath = "/tmp/s",
        logPath = null,
        connector = { _ ->
          Err(UnixSocketError.ConnectFailed(path = "/tmp/s", errno = 2, message = "ENOENT"))
        },
        spawner = { _, _ -> Ok(Unit) },
        clockMs = clock.clock,
        sleeper = budgetOverflowSleeper,
      )

    backend.compile(sampleArgs)

    // First few delays follow the doubling ladder, capped at MAX_BACKOFF_MS.
    assertTrue(slept.isNotEmpty())
    assertEquals(10, slept[0])
    if (slept.size > 1) assertEquals(20, slept[1])
    if (slept.size > 2) assertEquals(40, slept[2])
    // Every delay must stay in [INITIAL, MAX].
    for (ms in slept) {
      assertTrue(ms in 10..200, "delay out of range: $ms")
    }
  }

  @Test
  fun connectionIsClosedEvenOnSendFailure() {
    val fake = FakeConnection(sendResult = Err(FrameError.Malformed("bad")))
    val backend = newBackend(connector = { Ok(fake) })

    backend.compile(sampleArgs)

    assertTrue(fake.closed, "connection must close on send error")
  }
}
