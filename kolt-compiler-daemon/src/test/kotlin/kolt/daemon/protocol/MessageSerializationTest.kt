package kolt.daemon.protocol

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MessageSerializationTest {

    private val json = Json { classDiscriminator = "type" }

    @Test
    fun `Compile round-trips through JSON`() {
        val original: Message = Message.Compile(
            workingDir = "/tmp/project",
            classpath = listOf("/lib/a.jar", "/lib/b.jar"),
            sources = listOf("src/Main.kt", "src/Util.kt"),
            outputJar = "build/out.jar",
            moduleName = "main",
            extraArgs = listOf("-Xjsr305=strict"),
        )

        val encoded = json.encodeToString(Message.serializer(), original)
        val decoded = json.decodeFromString(Message.serializer(), encoded)

        assertEquals(original, decoded)
        assertIs<Message.Compile>(decoded)
    }

    @Test
    fun `Ping round-trips through JSON`() {
        val original: Message = Message.Ping
        val encoded = json.encodeToString(Message.serializer(), original)
        val decoded = json.decodeFromString(Message.serializer(), encoded)
        assertEquals(Message.Ping, decoded)
    }

    @Test
    fun `Shutdown round-trips through JSON`() {
        val original: Message = Message.Shutdown
        val encoded = json.encodeToString(Message.serializer(), original)
        val decoded = json.decodeFromString(Message.serializer(), encoded)
        assertEquals(Message.Shutdown, decoded)
    }

    @Test
    fun `CompileResult success round-trips through JSON`() {
        val original: Message = Message.CompileResult(
            exitCode = 0,
            diagnostics = emptyList(),
            stdout = "",
            stderr = "",
        )
        val encoded = json.encodeToString(Message.serializer(), original)
        val decoded = json.decodeFromString(Message.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `CompileResult with diagnostics round-trips through JSON`() {
        val original: Message = Message.CompileResult(
            exitCode = 1,
            diagnostics = listOf(
                Diagnostic(
                    severity = Severity.Error,
                    file = "src/Main.kt",
                    line = 12,
                    column = 5,
                    message = "unresolved reference: foo",
                ),
                Diagnostic(
                    severity = Severity.Warning,
                    file = null,
                    line = null,
                    column = null,
                    message = "unused parameter",
                ),
            ),
            stdout = "",
            stderr = "error: 1 error",
        )
        val encoded = json.encodeToString(Message.serializer(), original)
        val decoded = json.decodeFromString(Message.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `Pong round-trips through JSON`() {
        val original: Message = Message.Pong
        val encoded = json.encodeToString(Message.serializer(), original)
        val decoded = json.decodeFromString(Message.serializer(), encoded)
        assertEquals(Message.Pong, decoded)
    }
}
