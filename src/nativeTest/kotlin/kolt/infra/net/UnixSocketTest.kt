package kolt.infra.net

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.net.testfixture.UnixEchoServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class UnixSocketTest {
    @Test
    fun connectToNonexistentPathReturnsConnectFailed() {
        val path = "/tmp/kolt-uds-does-not-exist-${uniqueSuffix()}.sock"

        val result = UnixSocket.connect(path)

        assertTrue(result.isErr, "expected connect to fail for nonexistent path")
        val error = assertIs<UnixSocketError.ConnectFailed>(result.getError())
        assertEquals(path, error.path)
    }

    @Test
    fun recvExactReturnsInvalidArgumentForNegativeLength() {
        // A stray invalid fd cannot reach the recv() call because the
        // length check fires first, so this test is independent of
        // any server.
        UnixSocket(fd = -1).use { socket ->
            val result = socket.recvExact(-1)
            val err = assertIs<UnixSocketError.InvalidArgument>(result.getError())
            assertTrue(
                err.detail.contains("-1"),
                "detail should echo the offending length: ${err.detail}",
            )
        }
    }

    @Test
    fun recvExactReturnsUnexpectedEofWhenPeerProvidesFewerBytes() {
        // The echo server hands back exactly three bytes; asking for
        // four exercises the partial-read branch of recvExact.
        UnixEchoServer.start(handler = { byteArrayOf(1, 2, 3) })
            .getOrElse { fail("start failed: $it") }
            .use { server ->
                UnixSocket.connect(server.socketPath)
                    .getOrElse { fail("connect failed: $it") }
                    .use { client ->
                        client.shutdownWrite()
                            .getOrElse { fail("shutdownWrite failed: $it") }
                        val result = client.recvExact(4)
                        val err = assertIs<UnixSocketError.UnexpectedEof>(result.getError())
                        assertEquals(3, err.received)
                        assertEquals(4, err.expected)
                    }
            }
    }

    @Test
    fun sendAllOnClosedFdReturnsSendFailed() {
        // fd = -1 is never a valid descriptor, so send() returns -1
        // with EBADF before the kernel can ever touch a real socket.
        // This keeps the test deterministic and free of SIGPIPE risk.
        UnixSocket(fd = -1).use { socket ->
            val result = socket.sendAll(byteArrayOf(1, 2, 3))
            val err = assertIs<UnixSocketError.SendFailed>(result.getError())
            assertTrue(err.errno != 0, "expected a non-zero errno, got ${err.errno}")
        }
    }

    private fun uniqueSuffix(): String {
        val chars = ('a'..'z').toList()
        return (0 until 8).map { chars.random() }.joinToString("")
    }
}
