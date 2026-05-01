package kolt.nativedaemon.server

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kolt.nativedaemon.compiler.NativeCompileError
import kolt.nativedaemon.compiler.NativeCompileOutcome
import kolt.nativedaemon.compiler.NativeCompiler
import kolt.nativedaemon.protocol.FrameCodec
import kolt.nativedaemon.protocol.Message
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class DaemonLifecycleTest {

  private lateinit var socketDir: Path
  private lateinit var socketPath: Path
  private lateinit var serverThread: Thread
  private lateinit var server: DaemonServer

  @BeforeTest
  fun setUp() {
    socketDir = Files.createTempDirectory("kolt-native-compiler-daemon-lifecycle-")
    socketPath = socketDir.resolve("native-compiler-daemon.sock")
  }

  @AfterTest
  fun tearDown() {
    runCatching { server.stop() }
    runCatching { serverThread.join(2_000) }
    runCatching { Files.deleteIfExists(socketPath) }
    runCatching { Files.deleteIfExists(socketDir) }
  }

  @Test
  fun `server exits after serving maxCompiles compiles`() {
    server =
      DaemonServer(
        socketPath = socketPath,
        compiler = alwaysSuccess(),
        config =
          DaemonConfig(
            idleTimeoutMillis = 60_000,
            maxCompiles = 2,
            heapWatermarkBytes = Long.MAX_VALUE,
          ),
      )
    serverThread =
      Thread({ server.serve() }, "native-daemon-lifecycle").apply {
        isDaemon = true
        start()
      }
    waitForSocket()

    SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
      ch.connect(UnixDomainSocketAddress.of(socketPath))
      val input = Channels.newInputStream(ch)
      val output = Channels.newOutputStream(ch)
      repeat(2) {
        FrameCodec.writeFrame(output, Message.NativeCompile(args = listOf("-target", "linux_x64")))
        FrameCodec.readFrame(input)
      }
    }

    serverThread.join(2_000)
    assertFalse(serverThread.isAlive, "server should have exited after maxCompiles")
  }

  @Test
  fun `idle timeout stops server when no connection arrives`() {
    server =
      DaemonServer(
        socketPath = socketPath,
        compiler = alwaysSuccess(),
        config =
          DaemonConfig(
            idleTimeoutMillis = 1_000,
            maxCompiles = Int.MAX_VALUE,
            heapWatermarkBytes = Long.MAX_VALUE,
          ),
      )
    serverThread =
      Thread({ server.serve() }, "native-daemon-idle").apply {
        isDaemon = true
        start()
      }
    waitForSocket()

    serverThread.join(5_000)
    assertFalse(serverThread.isAlive, "server should have exited after idle timeout")
  }

  @Test
  fun `serve returns BindFailed when the parent directory cannot be created`() {
    // `/dev/null/...` cannot be a directory — `Files.createDirectories`
    // fails here, which is the first bind-side failure mode the native
    // client needs to distinguish from a genuine connect-refused race.
    val impossible =
      Path.of("/dev/null/kolt-native-compiler-daemon-impossible/native-compiler-daemon.sock")
    server = DaemonServer(socketPath = impossible, compiler = alwaysSuccess())
    serverThread =
      Thread({}, "noop").apply {
        start()
        join()
      }

    val result = server.serve()
    val err = result.getError()
    assertNotNull(err)
    assertIs<DaemonError.BindFailed>(err)
  }

  private fun alwaysSuccess(): NativeCompiler =
    object : NativeCompiler {
      override fun compile(args: List<String>): Result<NativeCompileOutcome, NativeCompileError> =
        Ok(NativeCompileOutcome(exitCode = 0, stderr = ""))
    }

  private fun waitForSocket() {
    val deadline = System.currentTimeMillis() + 2_000
    while (!Files.exists(socketPath) && System.currentTimeMillis() < deadline) {
      Thread.sleep(10)
    }
  }
}
