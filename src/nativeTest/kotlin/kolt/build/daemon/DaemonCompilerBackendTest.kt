package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.build.CompileError
import kolt.build.CompileRequest
import kolt.daemon.wire.FrameError
import kolt.daemon.wire.Message
import kolt.infra.ProcessError
import kolt.infra.net.UnixSocketError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Fake frame-level connection used as the seam for DaemonCompilerBackend
// tests. Records the last request sent and replays a configured reply.
private class FakeConnection(
    val reply: Result<Message, FrameError> = Ok(
        Message.CompileResult(
            exitCode = 0,
            diagnostics = emptyList(),
            stdout = "",
            stderr = "",
        ),
    ),
    val sendResult: Result<Unit, FrameError> = Ok(Unit),
) : DaemonConnection {
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

// Test clock: `nowMs` advances by the argument each time `sleep` is
// invoked. Lets us verify retry-budget exhaustion without burning
// wall-clock seconds.
private class FakeClock(var nowMs: Long = 0) {
    val clock: () -> Long = { nowMs }
    val sleeper: (Int) -> Unit = { ms -> nowMs += ms.toLong() }
}

private fun sampleRequest() = CompileRequest(
    workingDir = "/proj",
    classpath = listOf("/dep/a.jar"),
    sources = listOf("src"),
    outputPath = "build/classes",
    moduleName = "my-app",
    extraArgs = listOf("-jvm-target", "17"),
)

private fun newBackend(
    connector: DaemonConnector,
    spawner: DaemonSpawner = { _, _ -> Ok(Unit) },
    clock: FakeClock = FakeClock(),
): DaemonCompilerBackend = DaemonCompilerBackend(
    javaBin = "/opt/jdk/bin/java",
    daemonJarPath = "/opt/kolt/libexec/kolt-compiler-daemon-all.jar",
    compilerJars = listOf("/kt/lib/a.jar", "/kt/lib/b.jar"),
    btaImplJars = listOf("/opt/kolt/libexec/kolt-bta-impl/kotlin-build-tools-impl.jar"),
    socketPath = "/tmp/kolt-daemon-test.sock",
    logPath = "/tmp/kolt-daemon-test.log",
    connector = connector,
    spawner = spawner,
    clockMs = clock.clock,
    sleeper = clock.sleeper,
)

class DaemonCompilerBackendHappyPathTest {

    @Test
    fun successfulCompileMapsCompileResultToOutcome() {
        val fake = FakeConnection(
            reply = Ok(
                Message.CompileResult(
                    exitCode = 0,
                    diagnostics = emptyList(),
                    stdout = "compiled 1 file",
                    stderr = "",
                ),
            ),
        )
        val backend = newBackend(connector = { Ok(fake) })

        val result = backend.compile(sampleRequest())
        val outcome = assertNotNull(result.get())
        assertEquals("compiled 1 file", outcome.stdout)
        assertEquals("", outcome.stderr)
        assertTrue(fake.closed, "connection should be closed after compile")
    }

    @Test
    fun compileRequestFieldsAreForwardedVerbatimToWireMessage() {
        // Wire contract: no adapter layer between CompileRequest and
        // Message.Compile. A regression here would break JVM/native
        // protocol parity and is cheap to catch at the unit-test level.
        val fake = FakeConnection()
        val backend = newBackend(connector = { Ok(fake) })

        val request = sampleRequest()
        backend.compile(request)

        val sent = assertIs<Message.Compile>(fake.lastSent)
        assertEquals(request.workingDir, sent.workingDir)
        assertEquals(request.classpath, sent.classpath)
        assertEquals(request.sources, sent.sources)
        assertEquals(request.outputPath, sent.outputPath)
        assertEquals(request.moduleName, sent.moduleName)
        assertEquals(request.extraArgs, sent.extraArgs)
    }

    @Test
    fun nonZeroExitCodeMapsToCompilationFailed() {
        // Daemon ran kotlinc but user's Kotlin did not compile. This
        // is not fallback-eligible — retrying with a subprocess
        // kotlinc would produce the same user-visible failure.
        val fake = FakeConnection(
            reply = Ok(
                Message.CompileResult(
                    exitCode = 1,
                    diagnostics = emptyList(),
                    stdout = "",
                    stderr = "Main.kt:3:5 error: expected ';'",
                ),
            ),
        )
        val backend = newBackend(connector = { Ok(fake) })

        val err = assertNotNull(backend.compile(sampleRequest()).getError())
        val failed = assertIs<CompileError.CompilationFailed>(err)
        assertEquals(1, failed.exitCode)
        assertEquals("Main.kt:3:5 error: expected ';'", failed.stderr)
    }

    @Test
    fun daemonProtocolErrorMapsToBackendUnavailable() {
        // The JVM daemon's writeProtocolError wire contract emits
        // exitCode=2, empty diagnostics, empty stdout, and a stderr
        // prefixed "protocol error: ". Route those to
        // BackendUnavailable so FallbackCompilerBackend falls through
        // to subprocess compile instead of surfacing a confusing
        // CompilationFailed.
        val fake = FakeConnection(
            reply = Ok(
                Message.CompileResult(
                    exitCode = 2,
                    diagnostics = emptyList(),
                    stdout = "",
                    stderr = "protocol error: malformed frame body",
                ),
            ),
        )
        val backend = newBackend(connector = { Ok(fake) })

        val err = assertNotNull(backend.compile(sampleRequest()).getError())
        val unavailable = assertIs<CompileError.BackendUnavailable.Other>(err)
        assertTrue(
            unavailable.detail.contains("protocol error: malformed frame body"),
            "detail should carry the daemon's stderr: ${unavailable.detail}",
        )
    }

    @Test
    fun kotlincInternalErrorExitCode2DoesNotFalsePositive() {
        // A real kotlinc internal error can also produce exitCode=2
        // but carries real compiler output, not the "protocol error:"
        // prefix. Make sure the sniff is narrow enough to leave that
        // path as CompilationFailed.
        val fake = FakeConnection(
            reply = Ok(
                Message.CompileResult(
                    exitCode = 2,
                    diagnostics = emptyList(),
                    stdout = "",
                    stderr = "error: compiler backend internal error: NPE in JVMBackend",
                ),
            ),
        )
        val backend = newBackend(connector = { Ok(fake) })

        val err = assertNotNull(backend.compile(sampleRequest()).getError())
        val failed = assertIs<CompileError.CompilationFailed>(err)
        assertEquals(2, failed.exitCode)
    }
}

class DaemonCompilerBackendConnectAndSpawnTest {

    @Test
    fun noSpawnWhenFirstConnectSucceeds() {
        var spawnCalls = 0
        val backend = newBackend(
            connector = { Ok(FakeConnection()) },
            spawner = { _, _ -> spawnCalls++; Ok(Unit) },
        )
        backend.compile(sampleRequest())
        assertEquals(0, spawnCalls, "daemon should not be spawned when already listening")
    }

    @Test
    fun enoentTriggersSpawnAndThenRetrySucceeds() {
        // First connect fails with ENOENT (socket missing) so the
        // backend spawns a daemon; the second connect (after the
        // simulated sleep) succeeds.
        var attempts = 0
        var spawnCalls = 0
        val fake = FakeConnection()
        val connector: DaemonConnector = {
            attempts++
            if (attempts == 1) {
                Err(UnixSocketError.ConnectFailed(it, platform.posix.ENOENT, "No such file"))
            } else {
                Ok(fake)
            }
        }
        val backend = newBackend(
            connector = connector,
            spawner = { _, _ -> spawnCalls++; Ok(Unit) },
        )

        val result = backend.compile(sampleRequest())
        assertNotNull(result.get())
        assertEquals(1, spawnCalls)
        assertEquals(2, attempts)
    }

    @Test
    fun econnrefusedIsAlsoRetryable() {
        var attempts = 0
        val connector: DaemonConnector = {
            attempts++
            if (attempts == 1) {
                Err(UnixSocketError.ConnectFailed(it, platform.posix.ECONNREFUSED, "Connection refused"))
            } else {
                Ok(FakeConnection())
            }
        }
        val backend = newBackend(connector = connector)
        val result = backend.compile(sampleRequest())
        assertNotNull(result.get())
    }

    @Test
    fun eaccesIsFatalAndDoesNotTriggerSpawn() {
        // EACCES on the socket path means the daemon cannot bind to
        // the user's ~/.kolt/daemon/ either — retrying under a fresh
        // spawn will fail identically. Short-circuit to
        // BackendUnavailable immediately so the build falls back.
        var spawnCalls = 0
        val connector: DaemonConnector = {
            Err(UnixSocketError.ConnectFailed(it, platform.posix.EACCES, "Permission denied"))
        }
        val backend = newBackend(
            connector = connector,
            spawner = { _, _ -> spawnCalls++; Ok(Unit) },
        )
        val err = assertNotNull(backend.compile(sampleRequest()).getError())
        assertIs<CompileError.BackendUnavailable.Other>(err)
        assertEquals(0, spawnCalls, "fatal errno must not trigger spawn")
    }

    @Test
    fun invalidArgumentIsMappedToInternalMisuse() {
        // kolt itself built a bad socket path (e.g. the rendered path
        // exceeds sun_path capacity). This is a kolt bug, not a user
        // bug — surface it loudly rather than silently fall back.
        val connector: DaemonConnector = {
            Err(UnixSocketError.InvalidArgument("path exceeds sun_path capacity"))
        }
        val backend = newBackend(connector = connector)
        val err = assertNotNull(backend.compile(sampleRequest()).getError())
        val misuse = assertIs<CompileError.InternalMisuse>(err)
        assertTrue(misuse.detail.contains("sun_path"))
    }

    @Test
    fun spawnFailureIsMappedToBackendUnavailable() {
        // Simulate an unplausible-but-possible spawn failure: fork
        // returned -1. The retry loop never starts and the build
        // falls back cleanly.
        val connector: DaemonConnector = {
            Err(UnixSocketError.ConnectFailed(it, platform.posix.ENOENT, "No such file"))
        }
        val backend = newBackend(
            connector = connector,
            spawner = { _, _ -> Err(ProcessError.ForkFailed) },
        )
        val err = assertNotNull(backend.compile(sampleRequest()).getError())
        val unavailable = assertIs<CompileError.BackendUnavailable.Other>(err)
        assertTrue(unavailable.detail.contains("spawn"))
    }

    @Test
    fun retryExhaustedAfterBudgetReturnsBackendUnavailable() {
        // Every retry attempt returns ENOENT. Each fake sleep
        // advances the clock by the requested amount, so the 3000 ms
        // budget is blown after a finite number of iterations — no
        // wall-clock wait in the test.
        val clock = FakeClock()
        var attempts = 0
        val connector: DaemonConnector = {
            attempts++
            Err(UnixSocketError.ConnectFailed(it, platform.posix.ENOENT, "No such file"))
        }
        val backend = newBackend(
            connector = connector,
            spawner = { _, _ -> Ok(Unit) },
            clock = clock,
        )
        val err = assertNotNull(backend.compile(sampleRequest()).getError())
        val unavailable = assertIs<CompileError.BackendUnavailable.Other>(err)
        assertTrue(unavailable.detail.contains("within 3000ms"))
        assertTrue(attempts > 1, "expected at least one retry attempt after spawn")
        assertTrue(clock.nowMs >= 3000L, "fake clock should have advanced past the budget")
    }
}

class DaemonCompilerBackendReplyMappingTest {

    @Test
    fun unexpectedReplyTypeIsMappedToBackendUnavailable() {
        val fake = FakeConnection(reply = Ok(Message.Pong))
        val backend = newBackend(connector = { Ok(fake) })
        val err = assertNotNull(backend.compile(sampleRequest()).getError())
        val unavailable = assertIs<CompileError.BackendUnavailable.Other>(err)
        assertTrue(unavailable.detail.contains("Pong"))
    }

    @Test
    fun transportErrorOnReadMapsToBackendUnavailable() {
        val fake = FakeConnection(
            reply = Err(FrameError.Transport(UnixSocketError.RecvFailed(104, "Connection reset by peer"))),
        )
        val backend = newBackend(connector = { Ok(fake) })
        val err = assertNotNull(backend.compile(sampleRequest()).getError())
        val unavailable = assertIs<CompileError.BackendUnavailable.Other>(err)
        assertTrue(unavailable.detail.contains("Connection reset"))
    }

    @Test
    fun eofOnReadMapsToBackendUnavailable() {
        val fake = FakeConnection(reply = Err(FrameError.Eof))
        val backend = newBackend(connector = { Ok(fake) })
        val err = assertNotNull(backend.compile(sampleRequest()).getError())
        assertIs<CompileError.BackendUnavailable.Other>(err)
    }

    @Test
    fun sendSerializationFailureIsInternalMisuse() {
        // A Malformed error on the write path means our own codec
        // refused to serialise the request — i.e. kolt built a
        // structurally invalid message. That is an internal bug,
        // distinct from the daemon rejecting the wire.
        val fake = FakeConnection(sendResult = Err(FrameError.Malformed("not serialisable")))
        val backend = newBackend(connector = { Ok(fake) })
        val err = assertNotNull(backend.compile(sampleRequest()).getError())
        val misuse = assertIs<CompileError.InternalMisuse>(err)
        assertTrue(misuse.detail.contains("serialise"))
    }
}

class IsRetryableConnectErrorTest {
    @Test fun enoentIsRetryable() {
        assertTrue(isRetryableConnectError(UnixSocketError.ConnectFailed("/p", platform.posix.ENOENT, "x")))
    }
    @Test fun econnrefusedIsRetryable() {
        assertTrue(isRetryableConnectError(UnixSocketError.ConnectFailed("/p", platform.posix.ECONNREFUSED, "x")))
    }
    @Test fun eaccesIsNotRetryable() {
        assertFalse(isRetryableConnectError(UnixSocketError.ConnectFailed("/p", platform.posix.EACCES, "x")))
    }
    @Test fun invalidArgumentIsNotRetryable() {
        assertFalse(isRetryableConnectError(UnixSocketError.InvalidArgument("too long")))
    }
}
