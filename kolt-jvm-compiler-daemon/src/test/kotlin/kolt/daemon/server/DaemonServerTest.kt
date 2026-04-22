package kolt.daemon.server

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import kolt.daemon.ic.IcError
import kolt.daemon.ic.IcRequest
import kolt.daemon.ic.IcResponse
import kolt.daemon.ic.IcStateLayout
import kolt.daemon.ic.IncrementalCompiler
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
    private lateinit var icRoot: Path
    private lateinit var server: DaemonServer
    private lateinit var serverThread: Thread

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("kolt-daemon-server-")
        socketPath = socketDir.resolve("jvm-compiler-daemon.sock")
        icRoot = Files.createTempDirectory("kolt-daemon-server-ic-")
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.stop() }
        runCatching { serverThread.join(2_000) }
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { Files.deleteIfExists(socketDir) }
        runCatching { icRoot.toFile().deleteRecursively() }
    }

    private fun startServer(compiler: IncrementalCompiler) {
        server = DaemonServer(
            socketPath = socketPath,
            compiler = compiler,
            icRoot = icRoot,
            kotlinVersion = "2.3.20",
        )
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
        startServer(FakeCompiler())
        connect().use { ch ->
            val input = Channels.newInputStream(ch)
            val output = Channels.newOutputStream(ch)
            FrameCodec.writeFrame(output, Message.Ping)
            val response = FrameCodec.readFrame(input).get()
            assertEquals(Message.Pong, response)
        }
    }

    @Test
    fun `delegates Compile to the incremental compiler and returns CompileResult`() {
        val calls = AtomicInteger(0)
        val observedRequests = mutableListOf<IcRequest>()
        val compiler = FakeCompiler(
            onCompile = { req ->
                calls.incrementAndGet()
                observedRequests += req
                Ok(IcResponse(wallMillis = 7, compiledFileCount = 1))
            },
        )
        startServer(compiler)

        connect().use { ch ->
            val input = Channels.newInputStream(ch)
            val output = Channels.newOutputStream(ch)
            FrameCodec.writeFrame(
                output,
                Message.Compile(
                    workingDir = "/w",
                    classpath = emptyList(),
                    sources = listOf("A.kt"),
                    outputPath = "build/classes",
                    moduleName = "mod-a",
                    extraArgs = emptyList(),
                ),
            )
            val response = FrameCodec.readFrame(input).get()
            assertNotNull(response)
            val result = response as Message.CompileResult
            assertEquals(0, result.exitCode)
            assertEquals(1, calls.get())
            val observed = observedRequests.single()
            assertEquals(Path.of("/w"), observed.projectRoot)
            assertEquals(listOf(Path.of("A.kt")), observed.sources)
            assertEquals(Path.of("build/classes"), observed.outputDir)
            // ADR 0019 §5 / B-2a carryover #5 + #6: workingDir must be
            // daemon-owned IC state under `icRoot / kotlinVersion /
            // projectIdFor(projectRoot)`, not a projectRoot placeholder.
            // projectId must be produced by `IcStateLayout` so the two
            // call sites cannot drift apart.
            assertEquals(IcStateLayout.projectIdFor(Path.of("/w")), observed.projectId)
            assertEquals(
                IcStateLayout.workingDirFor(icRoot, "2.3.20", Path.of("/w")),
                observed.workingDir,
            )
        }
    }

    @Test
    fun `CompilationFailed from the adapter becomes a non-zero CompileResult`() {
        val compiler = FakeCompiler(
            onCompile = { _ ->
                com.github.michaelbull.result.Err(IcError.CompilationFailed(listOf("Main.kt: error: unresolved reference")))
            },
        )
        startServer(compiler)

        connect().use { ch ->
            val input = Channels.newInputStream(ch)
            val output = Channels.newOutputStream(ch)
            FrameCodec.writeFrame(
                output,
                Message.Compile(
                    workingDir = "/w",
                    classpath = emptyList(),
                    sources = listOf("Main.kt"),
                    outputPath = "build/classes",
                    moduleName = "m",
                ),
            )
            val result = FrameCodec.readFrame(input).get() as Message.CompileResult
            assertEquals(1, result.exitCode)
            kotlin.test.assertTrue(result.stderr.contains("unresolved reference"))
        }
    }

    @Test
    fun `handles multiple frames on the same connection`() {
        startServer(FakeCompiler())
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
        startServer(FakeCompiler())
        connect().use { ch ->
            FrameCodec.writeFrame(Channels.newOutputStream(ch), Message.Shutdown)
        }
        serverThread.join(2_000)
        assertEquals(false, serverThread.isAlive, "server thread should have exited")
        assertEquals(false, Files.exists(socketPath), "socket file should have been removed on exit")
    }

    @Test
    fun `rejects server-only messages with a protocol error reply`() {
        startServer(FakeCompiler())
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

    private class FakeCompiler(
        private val onCompile: (IcRequest) -> Result<IcResponse, IcError> = { _ ->
            Ok(IcResponse(wallMillis = 0, compiledFileCount = 0))
        },
    ) : IncrementalCompiler {
        override fun compile(request: IcRequest): Result<IcResponse, IcError> = onCompile(request)
    }
}
