package kolt.infra.net.testfixture

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.net.UnixSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class UnixEchoServerTest {
    @Test
    fun startProducesReachableSocket() {
        UnixEchoServer.start().getOrElse { fail("start failed: $it") }.use { server ->
            UnixSocket.connect(server.socketPath)
                .getOrElse { fail("connect failed: $it") }
                .close()
        }
    }

    @Test
    fun smallRoundTripEchoesBytes() {
        val payload = "hello, kolt daemon".encodeToByteArray()
        UnixEchoServer.start().getOrElse { fail("start failed: $it") }.use { server ->
            roundTrip(server.socketPath, payload).let { echoed ->
                assertContentEquals(payload, echoed)
            }
        }
    }

    @Test
    fun largeRoundTripExercisesPartialIO() {
        // 1 MiB forces sendAll/recvExact past the default AF_UNIX
        // socket buffer (~200 KiB on Linux) so the loops actually run.
        val payload = ByteArray(1 shl 20) { (it and 0xFF).toByte() }
        UnixEchoServer.start().getOrElse { fail("start failed: $it") }.use { server ->
            val echoed = roundTrip(server.socketPath, payload)
            assertContentEquals(payload, echoed)
        }
    }

    private fun roundTrip(path: String, payload: ByteArray): ByteArray {
        val client = UnixSocket.connect(path).getOrElse { fail("connect failed: $it") }
        try {
            client.sendAll(payload).getOrElse { fail("sendAll failed: $it") }
            client.shutdownWrite().getOrElse { fail("shutdownWrite failed: $it") }
            val result = client.recvExact(payload.size)
            assertTrue(
                result.isOk,
                "recvExact(${payload.size}) failed: ${result.getError()}",
            )
            return result.getOrElse { fail("unreachable") }
        } finally {
            client.close()
        }
    }
}
