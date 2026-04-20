package kolt.nativedaemon.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ADR 0024 §4: native daemon carries konanc args as a flat list rather than
// the structured fields used by the JVM daemon's Message.Compile. K2Native's
// entry point is `exec(PrintStream, String[])` and there is no BTA equivalent
// for native, so decomposing into structured fields adds complexity with no
// downstream benefit. The native daemon's Message sealed interface is
// intentionally separate from `kolt.daemon.protocol.Message` per ADR 0020 §2
// (separate daemon processes) and §1 here — the two wire formats evolve
// independently and share only the frame envelope format.
@Serializable
sealed interface Message {

    @Serializable
    @SerialName("NativeCompile")
    data class NativeCompile(
        val args: List<String>,
    ) : Message

    // ADR 0024 §4: stderr is a blob, same shape as the subprocess fallback path.
    // Structured diagnostic parsing is out of scope (see `kolt.daemon.protocol.Diagnostic`
    // on the JVM side — not mirrored here).
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
