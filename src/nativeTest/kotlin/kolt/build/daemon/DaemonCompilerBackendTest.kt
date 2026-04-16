package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.build.CompileError
import kolt.build.CompileRequest
import kolt.daemon.wire.Diagnostic
import kolt.daemon.wire.FrameError
import kolt.daemon.wire.Message
import kolt.daemon.wire.Severity
import kolt.infra.ProcessError
import kolt.infra.net.UnixSocketError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    pluginJars: Map<String, List<String>> = emptyMap(),
): DaemonCompilerBackend = DaemonCompilerBackend(
    javaBin = "/opt/jdk/bin/java",
    daemonJarPath = "/opt/kolt/libexec/kolt-compiler-daemon-all.jar",
    compilerJars = listOf("/kt/lib/a.jar", "/kt/lib/b.jar"),
    btaImplJars = listOf("/opt/kolt/libexec/kolt-bta-impl/kotlin-build-tools-impl.jar"),
    socketPath = "/tmp/kolt-daemon-test.sock",
    logPath = "/tmp/kolt-daemon-test.log",
    pluginJars = pluginJars,
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
    fun diagnosticsFieldIsThreadedThroughToCompilationFailed() {
        val fake = FakeConnection(
            reply = Ok(
                Message.CompileResult(
                    exitCode = 1,
                    diagnostics = listOf(
                        Diagnostic(
                            severity = Severity.Error,
                            file = "/tmp/Main.kt",
                            line = 3,
                            column = 5,
                            message = "expected ';'",
                        ),
                        Diagnostic(
                            severity = Severity.Warning,
                            file = "/tmp/Main.kt",
                            line = 1,
                            column = 1,
                            message = "unused import",
                        ),
                    ),
                    stdout = "",
                    stderr = "",
                ),
            ),
        )
        val backend = newBackend(connector = { Ok(fake) })

        val err = assertNotNull(backend.compile(sampleRequest()).getError())
        val failed = assertIs<CompileError.CompilationFailed>(err)
        assertEquals(2, failed.diagnostics.size)
        assertEquals("expected ';'", failed.diagnostics[0].message)
        assertEquals(Severity.Warning, failed.diagnostics[1].severity)
    }

    @Test
    fun daemonProtocolErrorMapsToBackendUnavailable() {
        // exitCode=2 + "protocol error: " prefix is the daemon's writeProtocolError
        // wire shape; route to BackendUnavailable so the fallback fires.
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
        // Real kotlinc internal error also exits 2 but without the
        // "protocol error:" prefix — must stay CompilationFailed.
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

    // #112: spawnArgv must include --bta-impl-jars; without it the daemon
    // silently falls back to subprocess compile (green CI, quiet regression).
    @Test
    fun spawnArgvIncludesBtaImplJarsFlag() {
        var captured: List<String>? = null
        var attempt = 0
        val connector: DaemonConnector = { path ->
            attempt++
            if (attempt == 1) {
                Err(UnixSocketError.ConnectFailed(path, platform.posix.ENOENT, "No such file"))
            } else {
                Ok(FakeConnection())
            }
        }
        val backend = newBackend(
            connector = connector,
            spawner = { argv, _ -> captured = argv; Ok(Unit) },
        )

        backend.compile(sampleRequest())

        val argv = assertNotNull(captured, "spawner must have been invoked after ENOENT")
        val flagIdx = argv.indexOf("--bta-impl-jars")
        assertTrue(flagIdx >= 0, "spawnArgv must include --bta-impl-jars, got: $argv")
        assertTrue(flagIdx + 1 < argv.size, "--bta-impl-jars must be followed by a classpath value")
        assertTrue(
            argv[flagIdx + 1].contains("kotlin-build-tools-impl"),
            "--bta-impl-jars value must contain kotlin-build-tools-impl jar, got: ${argv[flagIdx + 1]}",
        )
    }

    @Test
    fun spawnArgvOmitsPluginJarsFlagWhenEmpty() {
        var captured: List<String>? = null
        var attempt = 0
        val connector: DaemonConnector = { path ->
            attempt++
            if (attempt == 1) {
                Err(UnixSocketError.ConnectFailed(path, platform.posix.ENOENT, "No such file"))
            } else {
                Ok(FakeConnection())
            }
        }
        val backend = newBackend(
            connector = connector,
            spawner = { argv, _ -> captured = argv; Ok(Unit) },
            pluginJars = emptyMap(),
        )

        backend.compile(sampleRequest())

        val argv = assertNotNull(captured)
        assertFalse(
            argv.contains("--plugin-jars"),
            "spawnArgv must omit --plugin-jars when no plugins are enabled, got: $argv",
        )
    }

    // #65: wire format is `alias=cp1:cp2` — ':' matches File.pathSeparator on the daemon side.
    @Test
    fun spawnArgvSerialisesSinglePluginWithColonSeparatedClasspath() {
        var captured: List<String>? = null
        var attempt = 0
        val connector: DaemonConnector = { path ->
            attempt++
            if (attempt == 1) {
                Err(UnixSocketError.ConnectFailed(path, platform.posix.ENOENT, "No such file"))
            } else {
                Ok(FakeConnection())
            }
        }
        val backend = newBackend(
            connector = connector,
            spawner = { argv, _ -> captured = argv; Ok(Unit) },
            pluginJars = mapOf("serialization" to listOf("/kt/lib/ser1.jar", "/kt/lib/ser2.jar")),
        )

        backend.compile(sampleRequest())

        val argv = assertNotNull(captured)
        val flagIdx = argv.indexOf("--plugin-jars")
        assertTrue(flagIdx >= 0, "spawnArgv must include --plugin-jars, got: $argv")
        assertEquals("serialization=/kt/lib/ser1.jar:/kt/lib/ser2.jar", argv[flagIdx + 1])
    }

    // #65: multiple aliases joined with ';'. Iteration order matters —
    // a non-LinkedHashMap would break pluginsFingerprint stability.
    @Test
    fun spawnArgvSerialisesMultiplePluginsSemicolonSeparated() {
        var captured: List<String>? = null
        var attempt = 0
        val connector: DaemonConnector = { path ->
            attempt++
            if (attempt == 1) {
                Err(UnixSocketError.ConnectFailed(path, platform.posix.ENOENT, "No such file"))
            } else {
                Ok(FakeConnection())
            }
        }
        val backend = newBackend(
            connector = connector,
            spawner = { argv, _ -> captured = argv; Ok(Unit) },
            pluginJars = linkedMapOf(
                "serialization" to listOf("/p/ser.jar"),
                "allopen" to listOf("/p/open.jar"),
            ),
        )

        backend.compile(sampleRequest())

        val argv = assertNotNull(captured)
        val flagIdx = argv.indexOf("--plugin-jars")
        assertTrue(flagIdx >= 0, "spawnArgv must include --plugin-jars, got: $argv")
        assertEquals("serialization=/p/ser.jar;allopen=/p/open.jar", argv[flagIdx + 1])
    }

    @Test
    fun enoentTriggersSpawnAndThenRetrySucceeds() {
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
