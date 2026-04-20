package kolt.nativedaemon.protocol

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class FrameCodecTest {

    @Test
    fun `round-trips a Ping frame`() {
        val out = ByteArrayOutputStream()
        FrameCodec.writeFrame(out, Message.Ping)

        val decoded = FrameCodec.readFrame(ByteArrayInputStream(out.toByteArray())).get()
        assertEquals(Message.Ping, decoded)
    }

    @Test
    fun `round-trips a NativeCompile frame`() {
        val msg: Message = Message.NativeCompile(
            args = listOf("-target", "linux_x64", "src/Main.kt", "-p", "library", "-nopack", "-o", "build/main-klib"),
        )
        val out = ByteArrayOutputStream()
        FrameCodec.writeFrame(out, msg)

        assertEquals(msg, FrameCodec.readFrame(ByteArrayInputStream(out.toByteArray())).get())
    }

    @Test
    fun `round-trips a NativeCompileResult frame`() {
        val msg: Message = Message.NativeCompileResult(
            exitCode = 1,
            stderr = "error: unresolved reference: foo",
        )
        val out = ByteArrayOutputStream()
        FrameCodec.writeFrame(out, msg)

        assertEquals(msg, FrameCodec.readFrame(ByteArrayInputStream(out.toByteArray())).get())
    }

    @Test
    fun `reads multiple frames back to back`() {
        val out = ByteArrayOutputStream()
        FrameCodec.writeFrame(out, Message.Ping)
        FrameCodec.writeFrame(out, Message.Pong)
        FrameCodec.writeFrame(out, Message.Shutdown)

        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(Message.Ping, FrameCodec.readFrame(input).get())
        assertEquals(Message.Pong, FrameCodec.readFrame(input).get())
        assertEquals(Message.Shutdown, FrameCodec.readFrame(input).get())
    }

    @Test
    fun `empty stream returns Eof`() {
        val err = FrameCodec.readFrame(ByteArrayInputStream(ByteArray(0))).getError()
        assertEquals(FrameError.Eof, err)
    }

    @Test
    fun `truncated length prefix returns Truncated`() {
        val err = FrameCodec.readFrame(ByteArrayInputStream(byteArrayOf(0, 0))).getError()
        assertNotNull(err)
        assertIs<FrameError.Truncated>(err)
    }

    @Test
    fun `truncated body returns Truncated`() {
        val out = ByteArrayOutputStream()
        FrameCodec.writeFrame(out, Message.Ping)
        val full = out.toByteArray()
        val cut = full.copyOf(full.size - 2)

        val err = FrameCodec.readFrame(ByteArrayInputStream(cut)).getError()
        assertNotNull(err)
        assertIs<FrameError.Truncated>(err)
    }

    @Test
    fun `malformed JSON body returns Malformed`() {
        val body = "not json".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, body.size.toByte()))
        out.write(body)

        val err = FrameCodec.readFrame(ByteArrayInputStream(out.toByteArray())).getError()
        assertNotNull(err)
        assertIs<FrameError.Malformed>(err)
    }

    @Test
    fun `writeFrame returns Io error when the stream throws`() {
        val failing = object : OutputStream() {
            override fun write(b: Int) { throw IOException("broken pipe") }
        }
        val err = FrameCodec.writeFrame(failing, Message.Ping).getError()
        assertNotNull(err)
        assertIs<FrameError.Io>(err)
    }

    @Test
    fun `negative length returns Malformed`() {
        val bytes = byteArrayOf(-1, -1, -1, -1)
        val err = FrameCodec.readFrame(ByteArrayInputStream(bytes)).getError()
        assertNotNull(err)
        assertIs<FrameError.Malformed>(err)
    }

    @Test
    fun `writeFrame returns Malformed when body exceeds MAX_BODY_BYTES`() {
        // One arg whose JSON-encoded length alone overflows the cap. The actual
        // body grows slightly past the arg size (type discriminator, field name,
        // quotes), guaranteeing we cross the threshold.
        val oversized = "x".repeat(FrameCodec.MAX_BODY_BYTES + 1)
        val msg: Message = Message.NativeCompile(args = listOf(oversized))

        val err = FrameCodec.writeFrame(ByteArrayOutputStream(), msg).getError()
        assertNotNull(err)
        assertIs<FrameError.Malformed>(err)
    }

    @Test
    fun `reads a NativeCompile NativeCompileResult pair back to back`() {
        val request: Message = Message.NativeCompile(
            args = listOf("-target", "linux_x64", "src/Main.kt", "-p", "library", "-nopack"),
        )
        val response: Message = Message.NativeCompileResult(
            exitCode = 0,
            stderr = "warning: deprecated API usage at src/Main.kt:3",
        )
        val out = ByteArrayOutputStream()
        FrameCodec.writeFrame(out, request)
        FrameCodec.writeFrame(out, response)

        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(request, FrameCodec.readFrame(input).get())
        assertEquals(response, FrameCodec.readFrame(input).get())
    }
}
