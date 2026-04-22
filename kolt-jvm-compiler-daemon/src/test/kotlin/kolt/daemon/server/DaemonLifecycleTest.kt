package kolt.daemon.server

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import kolt.daemon.ic.IcError
import kolt.daemon.ic.IcRequest
import kolt.daemon.ic.IcResponse
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DaemonLifecycleTest {

    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private lateinit var icRoot: Path
    private lateinit var serverThread: Thread
    private lateinit var server: DaemonServer

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("kolt-daemon-lifecycle-")
        socketPath = socketDir.resolve("jvm-compiler-daemon.sock")
        icRoot = Files.createTempDirectory("kolt-daemon-lifecycle-ic-")
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.stop() }
        runCatching { serverThread.join(2_000) }
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { Files.deleteIfExists(socketDir) }
        runCatching { icRoot.toFile().deleteRecursively() }
    }

    @Test
    fun `server exits after serving maxCompiles compiles`() {
        val compiler = alwaysSuccess()
        server = DaemonServer(
            socketPath = socketPath,
            compiler = compiler,
            icRoot = icRoot,
            kotlinVersion = "2.3.20",
            config = DaemonConfig(
                idleTimeoutMillis = 60_000,
                maxCompiles = 2,
                heapWatermarkBytes = Long.MAX_VALUE,
            ),
        )
        serverThread = Thread({ server.serve() }, "daemon-lifecycle").apply {
            isDaemon = true
            start()
        }
        waitForSocket()

        SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
            ch.connect(UnixDomainSocketAddress.of(socketPath))
            val input = Channels.newInputStream(ch)
            val output = Channels.newOutputStream(ch)
            repeat(2) {
                FrameCodec.writeFrame(
                    output,
                    Message.Compile(
                        workingDir = "/w",
                        classpath = emptyList(),
                        sources = listOf("A.kt"),
                        outputPath = "build/classes",
                        moduleName = "m",
                    ),
                )
                FrameCodec.readFrame(input)
            }
        }

        serverThread.join(2_000)
        assertFalse(serverThread.isAlive, "server should have exited after maxCompiles")
    }

    @Test
    fun `idle timeout stops server when no connection arrives`() {
        server = DaemonServer(
            socketPath = socketPath,
            compiler = alwaysSuccess(),
            icRoot = icRoot,
            kotlinVersion = "2.3.20",
            config = DaemonConfig(
                idleTimeoutMillis = 1_000,
                maxCompiles = Int.MAX_VALUE,
                heapWatermarkBytes = Long.MAX_VALUE,
            ),
        )
        serverThread = Thread({ server.serve() }, "daemon-idle").apply {
            isDaemon = true
            start()
        }
        waitForSocket()

        serverThread.join(5_000)
        assertEquals(false, serverThread.isAlive, "server should have exited after idle timeout")
    }

    @Test
    fun `preServeHook fires exactly once after bind and before accept loop`() {
        val hookCalls = AtomicInteger(0)
        server = DaemonServer(
            socketPath = socketPath,
            compiler = alwaysSuccess(),
            icRoot = icRoot,
            kotlinVersion = "2.3.20",
            config = DaemonConfig(
                idleTimeoutMillis = 500,
                maxCompiles = Int.MAX_VALUE,
                heapWatermarkBytes = Long.MAX_VALUE,
            ),
            preServeHook = {
                // Socket must already exist when the hook fires — this
                // is the ordering ADR 0019 §Negative relies on so that
                // the IC reaper never races with a client connection
                // attempt.
                assertTrue(Files.exists(socketPath), "socket must be bound before hook fires")
                hookCalls.incrementAndGet()
            },
        )
        serverThread = Thread({ server.serve() }, "daemon-pre-serve-hook").apply {
            isDaemon = true
            start()
        }
        waitForSocket()
        // Hook must fire synchronously between bind and accept loop, so
        // the socket existing already proves the hook ran exactly once.
        // Asserting here (before idle-driven shutdown) keeps the test
        // independent of watchdog timing.
        assertEquals(1, hookCalls.get(), "preServeHook must fire exactly once per serve()")
    }

    @Test
    fun `serve swallows preServeHook failures and keeps serving`() {
        val compiler = alwaysSuccess()
        server = DaemonServer(
            socketPath = socketPath,
            compiler = compiler,
            icRoot = icRoot,
            kotlinVersion = "2.3.20",
            config = DaemonConfig(
                idleTimeoutMillis = 60_000,
                maxCompiles = 1,
                heapWatermarkBytes = Long.MAX_VALUE,
            ),
            preServeHook = { throw RuntimeException("reaper blew up") },
        )
        serverThread = Thread({ server.serve() }, "daemon-hook-failure").apply {
            isDaemon = true
            start()
        }
        waitForSocket()

        SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
            ch.connect(UnixDomainSocketAddress.of(socketPath))
            val input = Channels.newInputStream(ch)
            val output = Channels.newOutputStream(ch)
            FrameCodec.writeFrame(
                output,
                Message.Compile(
                    workingDir = "/w",
                    classpath = emptyList(),
                    sources = listOf("A.kt"),
                    outputPath = "build/classes",
                    moduleName = "m",
                ),
            )
            FrameCodec.readFrame(input)
        }

        serverThread.join(2_000)
        assertFalse(serverThread.isAlive, "server should have served the compile despite hook failure")
    }

    @Test
    fun `serve returns BindFailed when the parent directory cannot be created`() {
        val impossible = Path.of("/dev/null/kolt-daemon-impossible/jvm-compiler-daemon.sock")
        server = DaemonServer(
            socketPath = impossible,
            compiler = alwaysSuccess(),
            icRoot = icRoot,
            kotlinVersion = "2.3.20",
            config = DaemonConfig(),
        )
        serverThread = Thread({}, "noop").apply { start(); join() }

        val result = server.serve()
        val err = result.getError()
        assertNotNull(err)
        assertIs<DaemonError.BindFailed>(err)
    }

    private fun alwaysSuccess(): IncrementalCompiler = object : IncrementalCompiler {
        override fun compile(request: IcRequest): Result<IcResponse, IcError> =
            Ok(IcResponse(wallMillis = 0, compiledFileCount = 0))
    }

    private fun waitForSocket() {
        val deadline = System.currentTimeMillis() + 2_000
        while (!Files.exists(socketPath) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }
}
