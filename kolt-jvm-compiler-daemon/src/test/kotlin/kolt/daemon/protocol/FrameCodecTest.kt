package kolt.daemon.protocol

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
  fun `round-trips a Compile frame`() {
    val msg: Message =
      Message.Compile(
        workingDir = "/w",
        classpath = listOf("a.jar"),
        sources = listOf("A.kt"),
        outputPath = "out.jar",
        moduleName = "main",
        extraArgs = emptyList(),
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
    val failing =
      object : OutputStream() {
        override fun write(b: Int) {
          throw IOException("broken pipe")
        }
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
}
