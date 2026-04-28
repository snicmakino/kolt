package kolt.nativedaemon.wire

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.net.UnixSocket
import kolt.infra.net.testfixture.UnixEchoServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class FrameCodecTest {

  @Test
  fun roundTripEchoPreservesNativeCompileMessage() {
    val message =
      Message.NativeCompile(
        args =
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
      )

    val decoded = roundTripViaByteEcho(message)

    assertEquals(message, decoded)
  }

  @Test
  fun roundTripEchoPreservesNativeCompileResult() {
    val message =
      Message.NativeCompileResult(
        exitCode = 1,
        stderr = "error: unresolved reference: foo\n    at src/Main.kt:3",
      )

    assertEquals(message, roundTripViaByteEcho(message))
  }

  @Test
  fun roundTripEchoPreservesControlMessages() {
    assertEquals(Message.Ping, roundTripViaByteEcho(Message.Ping))
    assertEquals(Message.Pong, roundTripViaByteEcho(Message.Pong))
    assertEquals(Message.Shutdown, roundTripViaByteEcho(Message.Shutdown))
  }

  @Test
  fun readFrameReturnsEofOnCleanClose() {
    UnixEchoServer.start(handler = { ByteArray(0) })
      .getOrElse { fail("start failed: $it") }
      .use { server -> withReadFrame(server) { it.shutdownWrite() } }
      .let { err -> assertEquals(FrameError.Eof, err, "expected Eof on clean close") }
  }

  @Test
  fun readFrameReturnsMalformedOnBadJsonBody() {
    val body = "not json at all".encodeToByteArray()
    val framed = encodeBigEndianU32(body.size) + body
    UnixEchoServer.start(handler = { framed })
      .getOrElse { fail("start failed: $it") }
      .use { server -> withReadFrame(server) { it.shutdownWrite() } }
      .let { err -> assertIs<FrameError.Malformed>(err) }
  }

  @Test
  fun writeFrameRejectsBodyLargerThanMaxBodyBytes() {
    val oversize =
      Message.NativeCompileResult(exitCode = 1, stderr = "a".repeat(FrameCodec.MAX_BODY_BYTES + 1))
    UnixEchoServer.start()
      .getOrElse { fail("start failed: $it") }
      .use { server ->
        UnixSocket.connect(server.socketPath)
          .getOrElse { fail("connect failed: $it") }
          .use { client ->
            val result = FrameCodec.writeFrame(client, oversize)
            assertTrue(result.isErr, "expected oversize body to be rejected")
            val err = assertIs<FrameError.Malformed>(result.getError())
            assertTrue(
              err.reason.contains("MAX_BODY_BYTES"),
              "reason should mention MAX_BODY_BYTES: ${err.reason}",
            )
          }
      }
  }

  private fun roundTripViaByteEcho(message: Message): Message {
    UnixEchoServer.start()
      .getOrElse { fail("start failed: $it") }
      .use { server ->
        return UnixSocket.connect(server.socketPath)
          .getOrElse { fail("connect failed: $it") }
          .use { client ->
            FrameCodec.writeFrame(client, message).getOrElse { fail("write failed: $it") }
            client.shutdownWrite().getOrElse { fail("shutdownWrite failed: $it") }
            FrameCodec.readFrame(client).getOrElse { fail("read failed: $it") }
          }
      }
  }

  private fun withReadFrame(server: UnixEchoServer, prepare: (UnixSocket) -> Unit): FrameError {
    val socket = UnixSocket.connect(server.socketPath).getOrElse { fail("connect failed: $it") }
    return socket.use {
      prepare(it)
      val err = FrameCodec.readFrame(it).getError() ?: fail("expected read error")
      err
    }
  }

  private fun encodeBigEndianU32(value: Int): ByteArray =
    byteArrayOf(
      ((value ushr 24) and 0xff).toByte(),
      ((value ushr 16) and 0xff).toByte(),
      ((value ushr 8) and 0xff).toByte(),
      (value and 0xff).toByte(),
    )
}
