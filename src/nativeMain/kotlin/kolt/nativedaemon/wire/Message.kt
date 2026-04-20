package kolt.nativedaemon.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Native-side mirror of kolt-native-daemon's kolt.nativedaemon.protocol.Message.
// Field names and @SerialName values are load-bearing wire contract (ADR 0024
// §4) — drift breaks the daemon. Kept independent from `kolt.daemon.wire.Message`
// (the JVM daemon's mirror) per ADR 0024 §1: two daemons, two protocols, one
// frame codec pattern shared in spirit but not in code.
@Serializable
sealed interface Message {

    @Serializable
    @SerialName("NativeCompile")
    data class NativeCompile(
        val args: List<String>,
    ) : Message

    @Serializable
    @SerialName("NativeCompileResult")
    data class NativeCompileResult(
        val exitCode: Int,
        val stderr: String,
    ) : Message

    @Serializable
    @SerialName("Ping")
    data object Ping : Message

    @Serializable
    @SerialName("Pong")
    data object Pong : Message

    @Serializable
    @SerialName("Shutdown")
    data object Shutdown : Message
}
