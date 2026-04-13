package kolt.daemon.wire

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.infra.net.UnixSocket
import kolt.infra.net.UnixSocketError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Length-prefix + JSON framing over a [UnixSocket] stream.
 *
 * Wire contract (see ADR 0016 §3 — the native and JVM sides must stay
 * in lockstep or the daemon breaks):
 *
 * - 4-byte big-endian unsigned length prefix, counting body bytes only.
 * - UTF-8 JSON body produced by `kotlinx.serialization` with
 *   `classDiscriminator = "type"`; sealed variants are identified by
 *   their `@SerialName`.
 * - Frames larger than [MAX_BODY_BYTES] are rejected symmetrically on
 *   both the write and read paths.
 *
 * All fallible paths return a [FrameError] — consistent with ADR 0001,
 * no exception ever escapes.
 */
object FrameCodec {

    const val MAX_BODY_BYTES: Int = 64 * 1024 * 1024

    private val json = Json { classDiscriminator = "type" }

    fun writeFrame(socket: UnixSocket, message: Message): Result<Unit, FrameError> {
        val body = try {
            json.encodeToString(Message.serializer(), message).encodeToByteArray()
        } catch (e: SerializationException) {
            return Err(FrameError.Malformed(e.message ?: "serialization error"))
        }
        val len = body.size
        if (len > MAX_BODY_BYTES) {
            return Err(FrameError.Malformed("frame body exceeds MAX_BODY_BYTES: $len"))
        }

        val header = ByteArray(4)
        header[0] = ((len ushr 24) and 0xff).toByte()
        header[1] = ((len ushr 16) and 0xff).toByte()
        header[2] = ((len ushr 8) and 0xff).toByte()
        header[3] = (len and 0xff).toByte()

        socket.sendAll(header).getOrElse { return Err(FrameError.Transport(it)) }
        socket.sendAll(body).getOrElse { return Err(FrameError.Transport(it)) }
        return Ok(Unit)
    }

    fun readFrame(socket: UnixSocket): Result<Message, FrameError> {
        val header = socket.recvExact(4).getOrElse { err ->
            return Err(headerTransportError(err))
        }

        val len = ((header[0].toInt() and 0xff) shl 24) or
            ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or
            (header[3].toInt() and 0xff)

        if (len < 0 || len > MAX_BODY_BYTES) {
            return Err(FrameError.Malformed("invalid frame length: $len"))
        }

        val body = socket.recvExact(len).getOrElse { err ->
            return Err(
                when (err) {
                    is UnixSocketError.UnexpectedEof ->
                        FrameError.Truncated(wantedBytes = len, gotBytes = err.received)
                    else -> FrameError.Transport(err)
                },
            )
        }

        return try {
            Ok(json.decodeFromString(Message.serializer(), body.decodeToString()))
        } catch (e: SerializationException) {
            Err(FrameError.Malformed(e.message ?: "serialization error"))
        }
    }

    // A clean EOF at the very start of a frame (zero bytes before the
    // header arrives) is a normal shutdown; a partial header is a
    // truncated frame. Everything else is a transport-level failure.
    private fun headerTransportError(err: UnixSocketError): FrameError = when (err) {
        is UnixSocketError.UnexpectedEof ->
            if (err.received == 0) FrameError.Eof
            else FrameError.Truncated(wantedBytes = 4, gotBytes = err.received)
        else -> FrameError.Transport(err)
    }
}

sealed interface FrameError {
    data object Eof : FrameError
    data class Truncated(val wantedBytes: Int, val gotBytes: Int) : FrameError
    data class Malformed(val reason: String) : FrameError
    data class Transport(val cause: UnixSocketError) : FrameError
}
