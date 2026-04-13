package kolt.infra.net.testfixture

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.net.UnixSocket
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class UnixEchoServerTest {
    @Test
    fun startProducesReachableSocket() {
        val server = UnixEchoServer.start().getOrElse { err ->
            fail("UnixEchoServer.start() failed: $err")
        }
        try {
            val client = UnixSocket.connect(server.socketPath)
            assertTrue(
                client.isOk,
                "expected connect to succeed for ${server.socketPath}, got ${client.getError()}",
            )
            client.getOrElse { fail("unreachable") }.close()
        } finally {
            server.close()
        }
    }
}
