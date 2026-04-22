package kolt.nativedaemon.protocol

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageTest {

    private val json = Json { classDiscriminator = "type" }

    @Test
    fun `NativeCompile carries a flat args list`() {
        val msg: Message = Message.NativeCompile(
            args = listOf("-target", "linux_x64", "src/Main.kt", "-p", "library", "-nopack", "-o", "build/main-klib"),
        )

        val encoded = json.encodeToString(Message.serializer(), msg)
        val decoded = json.decodeFromString(Message.serializer(), encoded)

        assertEquals(msg, decoded)
    }

    @Test
    fun `NativeCompile serializes with type discriminator NativeCompile`() {
        val msg: Message = Message.NativeCompile(args = listOf("-target", "linux_x64"))

        val encoded = json.encodeToString(Message.serializer(), msg)

        assertTrue(encoded.contains("\"type\":\"NativeCompile\""), "expected type discriminator, got: $encoded")
    }

    @Test
    fun `NativeCompileResult round-trips with exitCode and stderr`() {
        val msg: Message = Message.NativeCompileResult(
            exitCode = 0,
            stderr = "warning: deprecated API usage",
        )

        val encoded = json.encodeToString(Message.serializer(), msg)
        val decoded = json.decodeFromString(Message.serializer(), encoded)

        assertEquals(msg, decoded)
    }

    @Test
    fun `NativeCompileResult with non-zero exit code preserves stderr blob`() {
        val msg: Message = Message.NativeCompileResult(
            exitCode = 1,
            stderr = "error: unresolved reference: foo\n    at src/Main.kt:3",
        )

        val decoded = json.decodeFromString(
            Message.serializer(),
            json.encodeToString(Message.serializer(), msg),
        )

        assertEquals(msg, decoded)
    }

    @Test
    fun `control messages Ping Pong Shutdown round-trip`() {
        for (msg in listOf<Message>(Message.Ping, Message.Pong, Message.Shutdown)) {
            val decoded = json.decodeFromString(
                Message.serializer(),
                json.encodeToString(Message.serializer(), msg),
            )
            assertEquals(msg, decoded)
        }
    }

    @Test
    fun `NativeCompile accepts empty args list`() {
        val msg: Message = Message.NativeCompile(args = emptyList())

        val decoded = json.decodeFromString(
            Message.serializer(),
            json.encodeToString(Message.serializer(), msg),
        )

        assertEquals(msg, decoded)
    }
}
