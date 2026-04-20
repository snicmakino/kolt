package kolt.nativedaemon.server

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import kolt.nativedaemon.compiler.NativeCompileError
import kolt.nativedaemon.compiler.NativeCompileOutcome
import kolt.nativedaemon.compiler.NativeCompiler
import kolt.nativedaemon.protocol.FrameCodec
import kolt.nativedaemon.protocol.Message
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DaemonServerTest {

    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private lateinit var server: DaemonServer
    private lateinit var serverThread: Thread

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("kolt-native-daemon-server-")
        socketPath = socketDir.resolve("native-daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.stop() }
        runCatching { serverThread.join(2_000) }
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { Files.deleteIfExists(socketDir) }
    }

    private fun startServer(compiler: NativeCompiler) {
        server = DaemonServer(socketPath = socketPath, compiler = compiler)
        serverThread = Thread({ server.serve() }, "native-daemon-server-test").apply {
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
            assertEquals(Message.Pong, FrameCodec.readFrame(input).get())
        }
    }

    @Test
    fun `delegates NativeCompile to the compiler and returns NativeCompileResult`() {
        val observedArgs = mutableListOf<List<String>>()
        val compiler = FakeCompiler { args ->
            observedArgs += args
            Ok(NativeCompileOutcome(exitCode = 0, stderr = ""))
        }
        startServer(compiler)

        connect().use { ch ->
            val input = Channels.newInputStream(ch)
            val output = Channels.newOutputStream(ch)
            val argv = listOf("-target", "linux_x64", "src/Main.kt", "-p", "library", "-nopack", "-o", "build/m-klib")
            FrameCodec.writeFrame(output, Message.NativeCompile(args = argv))
            val response = FrameCodec.readFrame(input).get() as Message.NativeCompileResult
            assertEquals(0, response.exitCode)
            assertEquals("", response.stderr)
            assertEquals(argv, observedArgs.single())
        }
    }

    @Test
    fun `non-zero exit code from compiler passes through to client`() {
        val compiler = FakeCompiler {
            Ok(NativeCompileOutcome(exitCode = 1, stderr = "error: unresolved reference: foo"))
        }
        startServer(compiler)

        connect().use { ch ->
            val input = Channels.newInputStream(ch)
            val output = Channels.newOutputStream(ch)
            FrameCodec.writeFrame(output, Message.NativeCompile(args = listOf("-target", "linux_x64", "Bad.kt")))
            val response = FrameCodec.readFrame(input).get() as Message.NativeCompileResult
            assertEquals(1, response.exitCode)
            assertTrue(response.stderr.contains("unresolved reference"))
        }
    }

    @Test
    fun `InvocationFailed from the compiler becomes exitCode 2 with stderr`() {
        val compiler = FakeCompiler {
            Err(NativeCompileError.InvocationFailed(RuntimeException("K2Native.exec threw: classloader broken")))
        }
        startServer(compiler)

        connect().use { ch ->
            val input = Channels.newInputStream(ch)
            val output = Channels.newOutputStream(ch)
            FrameCodec.writeFrame(output, Message.NativeCompile(args = listOf("-target", "linux_x64")))
            val response = FrameCodec.readFrame(input).get() as Message.NativeCompileResult
            assertEquals(2, response.exitCode)
            assertTrue(response.stderr.contains("K2Native.exec threw"))
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
            val response = FrameCodec.readFrame(input).get() as Message.NativeCompileResult
            assertEquals(2, response.exitCode)
            assertTrue(
                response.stderr.contains("protocol error"),
                "expected protocol error in stderr, got: ${response.stderr}",
            )
        }
    }

    private class FakeCompiler(
        private val handler: (List<String>) -> Result<NativeCompileOutcome, NativeCompileError> = { _ ->
            Ok(NativeCompileOutcome(exitCode = 0, stderr = ""))
        },
    ) : NativeCompiler {
        override fun compile(args: List<String>): Result<NativeCompileOutcome, NativeCompileError> =
            handler(args)
    }
}
