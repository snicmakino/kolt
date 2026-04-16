package kolt.daemon.wire

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
    fun roundTripEchoPreservesCompileMessage() {
        val message = Message.Compile(
            workingDir = "/tmp/project",
            classpath = listOf("/tmp/a.jar", "/tmp/b.jar"),
            sources = listOf("src/Main.kt", "src/Util.kt"),
            outputPath = "build/out.jar",
            moduleName = "main",
            extraArgs = listOf("-Xfoo", "-Xbar"),
        )

        val decoded = roundTripViaByteEcho(message)

        assertEquals(message, decoded)
    }

    @Test
    fun roundTripEchoPreservesCompileResultWithDiagnostics() {
        val message = Message.CompileResult(
            exitCode = 1,
            diagnostics = listOf(
                Diagnostic(
                    severity = Severity.Error,
                    file = "src/Main.kt",
                    line = 12,
                    column = 4,
                    message = "unresolved reference: foo",
                ),
                Diagnostic(
                    severity = Severity.Warning,
                    file = null,
                    line = null,
                    column = null,
                    message = "no main class",
                ),
            ),
            stdout = "hello",
            stderr = "error: unresolved reference: foo",
        )

        assertEquals(message, roundTripViaByteEcho(message))
    }

    @Test
    fun roundTripEchoPreservesDataObjects() {
        assertEquals(Message.Ping, roundTripViaByteEcho(Message.Ping))
        assertEquals(Message.Pong, roundTripViaByteEcho(Message.Pong))
        assertEquals(Message.Shutdown, roundTripViaByteEcho(Message.Shutdown))
    }

    @Test
    fun writeFrameRejectsBodyLargerThanMaxBodyBytes() {
        // A CompileResult whose stderr alone is larger than 64 MiB
        // guarantees the serialised body crosses the limit.
        val oversize = Message.CompileResult(
            exitCode = 0,
            diagnostics = emptyList(),
            stdout = "",
            stderr = "a".repeat(FrameCodec.MAX_BODY_BYTES + 1),
        )

        UnixEchoServer.start().getOrElse { fail("start failed: $it") }.use { server ->
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

    @Test
    fun readFrameReturnsEofOnCleanClose() {
        UnixEchoServer.start(handler = { ByteArray(0) })
            .getOrElse { fail("start failed: $it") }
            .use { server -> withReadFrame(server) { it.shutdownWrite() } }
            .let { err ->
                assertEquals(FrameError.Eof, err, "expected Eof on clean close")
            }
    }

    @Test
    fun readFrameReturnsTruncatedOnPartialHeader() {
        UnixEchoServer.start(handler = { byteArrayOf(0x00, 0x00) })
            .getOrElse { fail("start failed: $it") }
            .use { server -> withReadFrame(server) { it.shutdownWrite() } }
            .let { err ->
                val t = assertIs<FrameError.Truncated>(err)
                assertEquals(4, t.wantedBytes)
                assertEquals(2, t.gotBytes)
            }
    }

    @Test
    fun readFrameReturnsMalformedOnFrameLengthAboveMax() {
        val oversizeHeader = encodeBigEndianU32(FrameCodec.MAX_BODY_BYTES + 1)
        UnixEchoServer.start(handler = { oversizeHeader })
            .getOrElse { fail("start failed: $it") }
            .use { server -> withReadFrame(server) { it.shutdownWrite() } }
            .let { err ->
                val m = assertIs<FrameError.Malformed>(err)
                assertTrue(
                    m.reason.contains("invalid frame length"),
                    "reason should flag invalid frame length: ${m.reason}",
                )
            }
    }

    @Test
    fun readFrameReturnsTruncatedOnPartialBody() {
        val claimedLen = 10
        val framed = encodeBigEndianU32(claimedLen) + byteArrayOf(1, 2, 3)
        UnixEchoServer.start(handler = { framed })
            .getOrElse { fail("start failed: $it") }
            .use { server -> withReadFrame(server) { it.shutdownWrite() } }
            .let { err ->
                val t = assertIs<FrameError.Truncated>(err)
                assertEquals(claimedLen, t.wantedBytes)
                assertEquals(3, t.gotBytes)
            }
    }

    @Test
    fun readFrameReturnsMalformedOnZeroLengthBody() {
        // The wire contract has no legitimate zero-length body — an
        // empty JSON string is not a valid `Message` — so lock that in
        // as Malformed rather than silently trusting the peer.
        val framed = encodeBigEndianU32(0)
        UnixEchoServer.start(handler = { framed })
            .getOrElse { fail("start failed: $it") }
            .use { server -> withReadFrame(server) { it.shutdownWrite() } }
            .let { err ->
                assertIs<FrameError.Malformed>(err)
            }
    }

    @Test
    fun readFrameReturnsMalformedOnBadJsonBody() {
        val body = "not json at all".encodeToByteArray()
        val framed = encodeBigEndianU32(body.size) + body
        UnixEchoServer.start(handler = { framed })
            .getOrElse { fail("start failed: $it") }
            .use { server -> withReadFrame(server) { it.shutdownWrite() } }
            .let { err ->
                assertIs<FrameError.Malformed>(err)
            }
    }

    private fun roundTripViaByteEcho(message: Message): Message {
        UnixEchoServer.start().getOrElse { fail("start failed: $it") }.use { server ->
            UnixSocket.connect(server.socketPath)
                .getOrElse { fail("connect failed: $it") }
                .use { client ->
                    FrameCodec.writeFrame(client, message)
                        .getOrElse { fail("writeFrame failed: $it") }
                    client.shutdownWrite()
                        .getOrElse { fail("shutdownWrite failed: $it") }
                    return FrameCodec.readFrame(client)
                        .getOrElse { fail("readFrame failed: $it") }
                }
        }
    }

    private fun withReadFrame(
        server: UnixEchoServer,
        prime: (UnixSocket) -> Unit,
    ): FrameError {
        UnixSocket.connect(server.socketPath)
            .getOrElse { fail("connect failed: $it") }
            .use { client ->
                prime(client)
                val result = FrameCodec.readFrame(client)
                assertTrue(result.isErr, "expected readFrame to fail")
                return result.getError() ?: fail("unreachable")
            }
    }

    private fun encodeBigEndianU32(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        (value and 0xff).toByte(),
    )
}
