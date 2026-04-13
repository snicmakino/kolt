package kolt.daemon.server

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import kolt.daemon.host.CompileHostError
import kolt.daemon.host.CompileOutcome
import kolt.daemon.host.CompileRequest
import kolt.daemon.host.CompilerHost
import kolt.daemon.protocol.FrameCodec
import kolt.daemon.protocol.Message
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DaemonServerTest {

    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private lateinit var server: DaemonServer
    private lateinit var serverThread: Thread

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("kolt-daemon-server-")
        socketPath = socketDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.stop() }
        runCatching { serverThread.join(2_000) }
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { Files.deleteIfExists(socketDir) }
    }

    private fun startServer(host: CompilerHost) {
        server = DaemonServer(socketPath, host)
        serverThread = Thread({ server.serve() }, "daemon-server-test").apply {
            isDaemon = true
            start()
        }
        val deadline = System.currentTimeMillis() + 2_000
        while (!Files.exists(socketPath) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    private fun connect(): SocketChannel =
        SocketChannel.open(StandardProtocolFamily.UNIX).also {
            it.connect(UnixDomainSocketAddress.of(socketPath))
        }

    @Test
    fun `responds to Ping with Pong`() {
        startServer(FakeHost())
        connect().use { ch ->
            val input = Channels.newInputStream(ch)
            val output = Channels.newOutputStream(ch)
            FrameCodec.writeFrame(output, Message.Ping)
            val response = FrameCodec.readFrame(input).get()
            assertEquals(Message.Pong, response)
        }
    }

    @Test
    fun `delegates Compile to the host and returns CompileResult`() {
        val calls = AtomicInteger(0)
        val host = FakeHost(
            onCompile = { req ->
                calls.incrementAndGet()
                Ok(CompileOutcome(exitCode = 0, stdout = "", stderr = "stderr:${req.moduleName}"))
            },
        )
        startServer(host)

        connect().use { ch ->
            val input = Channels.newInputStream(ch)
            val output = Channels.newOutputStream(ch)
            FrameCodec.writeFrame(
                output,
                Message.Compile(
                    workingDir = "/w",
                    classpath = emptyList(),
                    sources = listOf("A.kt"),
                    outputPath = "out.jar",
                    moduleName = "mod-a",
                    extraArgs = emptyList(),
                ),
            )
            val response = FrameCodec.readFrame(input).get()
            assertNotNull(response)
            val result = response as Message.CompileResult
            assertEquals(0, result.exitCode)
            assertEquals("stderr:mod-a", result.stderr)
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun `handles multiple frames on the same connection`() {
        startServer(FakeHost())
        connect().use { ch ->
            val input = Channels.newInputStream(ch)
            val output = Channels.newOutputStream(ch)
            repeat(3) {
                FrameCodec.writeFrame(output, Message.Ping)
                assertEquals(Message.Pong, FrameCodec.readFrame(input).get())
            }
        }
    }

    @Test
    fun `Shutdown message stops the server and cleans up the socket file`() {
        startServer(FakeHost())
        connect().use { ch ->
            FrameCodec.writeFrame(Channels.newOutputStream(ch), Message.Shutdown)
        }
        serverThread.join(2_000)
        assertEquals(false, serverThread.isAlive, "server thread should have exited")
        assertEquals(false, Files.exists(socketPath), "socket file should have been removed on exit")
    }

    @Test
    fun `rejects server-only messages with a protocol error reply`() {
        startServer(FakeHost())
        connect().use { ch ->
            val input = Channels.newInputStream(ch)
            val output = Channels.newOutputStream(ch)
            FrameCodec.writeFrame(output, Message.Pong)
            val response = FrameCodec.readFrame(input).get() as Message.CompileResult
            assertEquals(2, response.exitCode)
            kotlin.test.assertTrue(
                response.stderr.contains("protocol error"),
                "expected protocol error in stderr, got: ${response.stderr}",
            )
        }
    }

    private class FakeHost(
        private val onCompile: (CompileRequest) -> Result<CompileOutcome, CompileHostError> =
            { Ok(CompileOutcome(0, "", "")) },
    ) : CompilerHost {
        override fun compile(request: CompileRequest): Result<CompileOutcome, CompileHostError> =
            onCompile(request)
    }
}
