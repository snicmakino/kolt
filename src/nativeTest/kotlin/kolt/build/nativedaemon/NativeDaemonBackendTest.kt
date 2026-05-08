package kolt.build.nativedaemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.build.NativeCompileError
import kolt.build.daemon.BOOTSTRAP_JDK_VERSION
import kolt.infra.ProcessError
import kolt.infra.net.UnixSocketError
import kolt.infra.output.ColorPolicy
import kolt.nativedaemon.wire.FrameError
import kolt.nativedaemon.wire.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeConnection(
  val reply: Result<Message, FrameError> =
    Ok(Message.NativeCompileResult(exitCode = 0, stderr = "")),
  val sendResult: Result<Unit, FrameError> = Ok(Unit),
  // Per-call override: index 0 = first sendRequest, index 1 = second, etc.
  // null entries fall through to sendResult.
  private val sendResults: List<Result<Unit, FrameError>?> = emptyList(),
) : NativeDaemonConnection {
  val sent: MutableList<Message> = mutableListOf()
  val lastSent: Message?
    get() = sent.lastOrNull()

  var closed = false

  override fun sendRequest(message: Message): Result<Unit, FrameError> {
    val index = sent.size
    sent += message
    val override = sendResults.getOrNull(index)
    return override ?: sendResult
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
  spawner: NativeDaemonSpawner = { _, _, _ -> Ok(Unit) },
  clock: FakeClock = FakeClock(),
  onSpawn: () -> Unit = {},
  warnSink: (String) -> Unit = {},
  colorPolicy: () -> ColorPolicy = { ColorPolicy.Never },
): NativeDaemonBackend =
  NativeDaemonBackend(
    javaBin = "/opt/jdk/bin/java",
    daemonLaunchArgs =
      listOf("-cp", "/opt/kolt/libexec/native-daemon.jar", "kolt.nativedaemon.MainKt"),
    konancJar = "/opt/konan/konan/lib/kotlin-native-compiler-embeddable.jar",
    konanHome = "/opt/konan",
    socketPath = "/tmp/kolt-native-daemon-test.sock",
    logPath = "/tmp/kolt-native-daemon-test.log",
    connector = connector,
    spawner = spawner,
    clockMs = clock.clock,
    sleeper = clock.sleeper,
    onSpawn = onSpawn,
    warnSink = warnSink,
    colorPolicy = colorPolicy,
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
  fun sendErrorMalformedMapsToInternalMisuse() {
    val fake = FakeConnection(sendResult = Err(FrameError.Malformed("oversize body")))
    val backend = newBackend(connector = { Ok(fake) })

    val err = backend.compile(sampleArgs).getError()

    val misuse = assertIs<NativeCompileError.InternalMisuse>(err)
    assertTrue(misuse.detail.contains("oversize body"))
  }
}

class NativeDaemonBackendWireMismatchTest {

  @Test
  fun eofOnReadMapsToWireMismatchAndSendsShutdown() {
    val fake = FakeConnection(reply = Err(FrameError.Eof))
    val backend = newBackend(connector = { Ok(fake) })
    val err = assertNotNull(backend.compile(sampleArgs).getError())
    val mismatch = assertIs<NativeCompileError.BackendUnavailable.WireMismatch>(err)
    assertTrue(
      mismatch.detail.contains("native daemon closed connection before replying"),
      "detail mismatch: ${mismatch.detail}",
    )
    assertEquals(2, fake.sent.size, "expected NativeCompile then Shutdown, got ${fake.sent}")
    assertIs<Message.NativeCompile>(fake.sent[0])
    assertEquals(Message.Shutdown, fake.sent[1])
  }

  @Test
  fun truncatedOnReadMapsToWireMismatchAndSendsShutdown() {
    val fake = FakeConnection(reply = Err(FrameError.Truncated(wantedBytes = 16, gotBytes = 7)))
    val backend = newBackend(connector = { Ok(fake) })
    val err = assertNotNull(backend.compile(sampleArgs).getError())
    val mismatch = assertIs<NativeCompileError.BackendUnavailable.WireMismatch>(err)
    assertTrue(mismatch.detail.contains("truncated reply"), "detail mismatch: ${mismatch.detail}")
    assertEquals(2, fake.sent.size)
    assertEquals(Message.Shutdown, fake.sent[1])
  }

  @Test
  fun malformedOnReadMapsToWireMismatchAndSendsShutdown() {
    val fake = FakeConnection(reply = Err(FrameError.Malformed("unknown discriminator")))
    val backend = newBackend(connector = { Ok(fake) })
    val err = assertNotNull(backend.compile(sampleArgs).getError())
    val mismatch = assertIs<NativeCompileError.BackendUnavailable.WireMismatch>(err)
    assertTrue(mismatch.detail.contains("malformed reply"), "detail mismatch: ${mismatch.detail}")
    assertEquals(2, fake.sent.size)
    assertEquals(Message.Shutdown, fake.sent[1])
  }

  @Test
  fun transportErrorOnReadMapsToWireMismatchAndSendsShutdown() {
    val fake =
      FakeConnection(
        reply =
          Err(FrameError.Transport(UnixSocketError.RecvFailed(errno = 5, message = "I/O error")))
      )
    val backend = newBackend(connector = { Ok(fake) })
    val err = assertNotNull(backend.compile(sampleArgs).getError())
    val mismatch = assertIs<NativeCompileError.BackendUnavailable.WireMismatch>(err)
    assertTrue(mismatch.detail.contains("I/O error"), "detail mismatch: ${mismatch.detail}")
    assertEquals(2, fake.sent.size)
    assertEquals(Message.Shutdown, fake.sent[1])
  }

  @Test
  fun unexpectedReplyVariantMapsToWireMismatchAndSendsShutdown() {
    val fake = FakeConnection(reply = Ok(Message.Pong))
    val backend = newBackend(connector = { Ok(fake) })
    val err = assertNotNull(backend.compile(sampleArgs).getError())
    val mismatch = assertIs<NativeCompileError.BackendUnavailable.WireMismatch>(err)
    assertTrue(
      mismatch.detail.contains("unexpected reply type"),
      "detail mismatch: ${mismatch.detail}",
    )
    assertTrue(mismatch.detail.contains("Pong"), "detail mismatch: ${mismatch.detail}")
    assertEquals(2, fake.sent.size, "expected NativeCompile then Shutdown, got ${fake.sent}")
    assertEquals(Message.Shutdown, fake.sent[1])
  }

  @Test
  fun shutdownSendFailureLogsWarningAndStillReturnsWireMismatch() {
    val warnings = mutableListOf<String>()
    val fake =
      FakeConnection(
        reply = Err(FrameError.Eof),
        // First sendRequest (NativeCompile) succeeds; second (Shutdown) fails.
        sendResults =
          listOf(Ok(Unit), Err(FrameError.Transport(UnixSocketError.SendFailed(32, "Broken pipe")))),
      )
    val backend = newBackend(connector = { Ok(fake) }, warnSink = { warnings += it })
    val err = assertNotNull(backend.compile(sampleArgs).getError())
    assertIs<NativeCompileError.BackendUnavailable.WireMismatch>(err)
    assertEquals(2, fake.sent.size, "Shutdown send must still be attempted")
    assertEquals(Message.Shutdown, fake.sent[1])
    assertTrue(
      warnings.any { it.contains("failed to send Shutdown") },
      "expected warn-line about failed Shutdown send, got: $warnings",
    )
  }

  @Test
  fun happyPathDoesNotSendShutdown() {
    val fake = FakeConnection()
    val backend = newBackend(connector = { Ok(fake) })
    backend.compile(sampleArgs)
    assertEquals(1, fake.sent.size, "happy path must send only NativeCompile, got ${fake.sent}")
    assertIs<Message.NativeCompile>(fake.sent[0])
  }

  @Test
  fun compilationFailedDoesNotSendShutdown() {
    val fake =
      FakeConnection(
        reply = Ok(Message.NativeCompileResult(exitCode = 1, stderr = "error: source error"))
      )
    val backend = newBackend(connector = { Ok(fake) })
    backend.compile(sampleArgs)
    assertEquals(1, fake.sent.size, "compilation failure must not trigger Shutdown")
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
        spawner = { _, _, _ ->
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
        spawner = { _, _, _ ->
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
        spawner = { _, _, _ -> Err(ProcessError.ForkFailed) },
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
        spawner = { argv, _, _ ->
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
  fun spawnArgvSilencesJdk23PlusKonancWarnings() {
    var capturedArgv: List<String>? = null
    val backend =
      newBackend(
        connector = { _ ->
          Err(UnixSocketError.ConnectFailed(path = "/tmp/s", errno = 2, message = "ENOENT"))
        },
        spawner = { argv, _, _ ->
          capturedArgv = argv
          Err(ProcessError.ForkFailed)
        },
      )

    backend.compile(sampleArgs)

    val argv = assertNotNull(capturedArgv)
    val unsafeIdx = argv.indexOf("--sun-misc-unsafe-memory-access=allow")
    val nativeAccessIdx = argv.indexOf("--enable-native-access=ALL-UNNAMED")
    assertTrue(unsafeIdx > 0, "missing --sun-misc-unsafe-memory-access flag: $argv")
    assertTrue(nativeAccessIdx > 0, "missing --enable-native-access flag: $argv")
    // Flags must precede `-cp` (and the daemon main class). After `-cp ...
    // MainKt`, the JVM hands tokens to the program's main, not its own arg
    // parser — flags placed there fail with `Unrecognized option` at startup.
    val cpIdx = argv.indexOf("-cp")
    assertTrue(cpIdx > 0, "test fixture must include -cp marker")
    assertTrue(unsafeIdx < cpIdx, "unsafe flag must precede -cp; argv=$argv")
    assertTrue(nativeAccessIdx < cpIdx, "native-access flag must precede -cp; argv=$argv")
  }

  // The daemon JVM unconditionally passes `--sun-misc-unsafe-memory-access`
  // and `--enable-native-access`, both of which require JDK 17+ and JEP 498
  // respectively. If BOOTSTRAP_JDK_VERSION is ever lowered below 23 the
  // daemon will fail to start with `Unrecognized option`. This pin catches
  // that drift at the source.
  @Test
  fun bootstrapJdkSupportsTheUnconditionalSpawnFlags() {
    val major = BOOTSTRAP_JDK_VERSION.takeWhile { it.isDigit() }.toIntOrNull()
    assertNotNull(major, "BOOTSTRAP_JDK_VERSION must parse as a major version")
    assertTrue(
      major >= 23,
      "BOOTSTRAP_JDK_VERSION ($BOOTSTRAP_JDK_VERSION) must be >= 23 to recognise " +
        "--sun-misc-unsafe-memory-access; gate the daemon spawnArgv flags on a JDK check first",
    )
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
        spawner = { _, _, _ -> Ok(Unit) },
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

class NativeDaemonBackendNoColorEnvTest {

  // When ColorPolicy disables stderr color, the native daemon spawn must
  // carry NO_COLOR=1 so the JVM daemon (and the konanc subprocess it spawns)
  // emits plain diagnostics. Mirrors DaemonCompilerBackendNoColorEnvTest.
  @Test
  fun spawnEnvIncludesNoColorWhenColorPolicyDisablesStderr() {
    var capturedEnv: Map<String, String>? = null
    var attempt = 0
    val connector: NativeDaemonConnector = { path ->
      attempt++
      if (attempt == 1) {
        Err(UnixSocketError.ConnectFailed(path, platform.posix.ENOENT, "No such file"))
      } else {
        Ok(FakeConnection())
      }
    }
    val backend =
      newBackend(
        connector = connector,
        spawner = { _, _, env ->
          capturedEnv = env
          Ok(Unit)
        },
        colorPolicy = { ColorPolicy.Never },
      )
    backend.compile(sampleArgs)
    val env = assertNotNull(capturedEnv, "spawner must have been invoked after ENOENT")
    assertEquals("1", env["NO_COLOR"], "expected NO_COLOR=1 in spawn env, got: $env")
  }

  // When color is enabled, kolt must not inject NO_COLOR — the daemon (and
  // its konanc subprocess) inherits parent env verbatim.
  @Test
  fun spawnEnvOmitsNoColorWhenColorPolicyAllowsStderr() {
    var capturedEnv: Map<String, String>? = null
    var attempt = 0
    val connector: NativeDaemonConnector = { path ->
      attempt++
      if (attempt == 1) {
        Err(UnixSocketError.ConnectFailed(path, platform.posix.ENOENT, "No such file"))
      } else {
        Ok(FakeConnection())
      }
    }
    val backend =
      newBackend(
        connector = connector,
        spawner = { _, _, env ->
          capturedEnv = env
          Ok(Unit)
        },
        colorPolicy = { ColorPolicy.Always },
      )
    backend.compile(sampleArgs)
    val env = assertNotNull(capturedEnv, "spawner must have been invoked after ENOENT")
    assertFalse(
      env.containsKey("NO_COLOR"),
      "expected spawn env to omit NO_COLOR when color enabled, got: $env",
    )
  }
}
