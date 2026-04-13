package kolt.daemon.protocol

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
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

    const val MAX_BODY_BYTES: Int = 64 * 1024 * 1024

    fun writeFrame(out: OutputStream, message: Message): Result<Unit, FrameError> {
        val body = json.encodeToString(Message.serializer(), message).toByteArray(Charsets.UTF_8)
        val len = body.size
        if (len > MAX_BODY_BYTES) {
            return Err(FrameError.Malformed("frame body exceeds MAX_BODY_BYTES: $len"))
        }
        return try {
            out.write((len ushr 24) and 0xff)
            out.write((len ushr 16) and 0xff)
            out.write((len ushr 8) and 0xff)
            out.write(len and 0xff)
            out.write(body)
            out.flush()
            Ok(Unit)
        } catch (e: IOException) {
            Err(FrameError.Io(e.message ?: "write failed"))
        }
    }

    fun readFrame(input: InputStream): Result<Message, FrameError> {
        val header = ByteArray(4)
        val headerRead = try {
            readFully(input, header)
        } catch (e: IOException) {
            return Err(FrameError.Io(e.message ?: "read failed"))
        }
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
        val bodyRead = try {
            readFully(input, body)
        } catch (e: IOException) {
            return Err(FrameError.Io(e.message ?: "read failed"))
        }
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
