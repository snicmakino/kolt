package kolt.infra.net

import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UnixSocketTest {
    @Test
    fun connectToNonexistentPathReturnsConnectFailed() {
        val path = "/tmp/kolt-uds-does-not-exist-${uniqueSuffix()}.sock"

        val result = UnixSocket.connect(path)

        assertTrue(result.isErr, "expected connect to fail for nonexistent path")
        val error = assertIs<UnixSocketError.ConnectFailed>(result.getError())
        assertEquals(path, error.path)
    }

    private fun uniqueSuffix(): String {
        val chars = ('a'..'z').toList()
        return (0 until 8).map { chars.random() }.joinToString("")
    }
}
