package kolt.daemon.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Native mirror of `kolt.daemon.protocol.Message` from the JVM
 * `kolt-compiler-daemon` subproject. Field names and serial names are
 * load-bearing — the JSON wire format documented in ADR 0016 is the
 * contract shared between the two sides, and drift in either file
 * breaks the daemon.
 *
 * This file is deliberately duplicated rather than shared via a
 * commonMain source set: kolt today only speaks this protocol from one
 * native client and one JVM server, so a shared module would trade
 * straight-line readability for generic build plumbing we do not need.
 */
@Serializable
sealed interface Message {

    @Serializable
    @SerialName("Compile")
    data class Compile(
        val workingDir: String,
        val classpath: List<String>,
        val sources: List<String>,
        val outputPath: String,
        val moduleName: String,
        val extraArgs: List<String> = emptyList(),
    ) : Message

    @Serializable
    @SerialName("CompileResult")
    data class CompileResult(
        val exitCode: Int,
        val diagnostics: List<Diagnostic>,
        val stdout: String,
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

@Serializable
data class Diagnostic(
    val severity: Severity,
    val file: String?,
    val line: Int?,
    val column: Int?,
    val message: String,
)

@Serializable
enum class Severity {
    Error,
    Warning,
    Info,
    Logging,
}
