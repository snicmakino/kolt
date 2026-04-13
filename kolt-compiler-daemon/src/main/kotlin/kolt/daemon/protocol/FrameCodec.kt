package kolt.daemon.protocol

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

sealed interface FrameError {
    data object Eof : FrameError
    data class Truncated(val wantedBytes: Int, val gotBytes: Int) : FrameError
    data class Malformed(val reason: String) : FrameError
    data class Io(val reason: String) : FrameError
}

object FrameCodec {

    private val json = Json { classDiscriminator = "type" }

    private const val MAX_BODY_BYTES = 64 * 1024 * 1024

    fun writeFrame(out: OutputStream, message: Message) {
        val body = json.encodeToString(Message.serializer(), message).toByteArray(Charsets.UTF_8)
        val len = body.size
        out.write((len ushr 24) and 0xff)
        out.write((len ushr 16) and 0xff)
        out.write((len ushr 8) and 0xff)
        out.write(len and 0xff)
        out.write(body)
        out.flush()
    }

    fun readFrame(input: InputStream): Result<Message, FrameError> {
        val header = ByteArray(4)
        val headerRead = readFully(input, header)
        if (headerRead == 0) return Err(FrameError.Eof)
        if (headerRead < 4) return Err(FrameError.Truncated(wantedBytes = 4, gotBytes = headerRead))

        val len = ((header[0].toInt() and 0xff) shl 24) or
            ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or
            (header[3].toInt() and 0xff)

        if (len < 0 || len > MAX_BODY_BYTES) {
            return Err(FrameError.Malformed("invalid frame length: $len"))
        }

        val body = ByteArray(len)
        val bodyRead = readFully(input, body)
        if (bodyRead < len) return Err(FrameError.Truncated(wantedBytes = len, gotBytes = bodyRead))

        return try {
            Ok(json.decodeFromString(Message.serializer(), body.toString(Charsets.UTF_8)))
        } catch (e: SerializationException) {
            Err(FrameError.Malformed(e.message ?: "serialization error"))
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) return total
            total += n
        }
        return total
    }
}
