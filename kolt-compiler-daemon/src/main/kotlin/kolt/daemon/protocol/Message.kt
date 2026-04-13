package kolt.daemon.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

    // TODO(#14 Phase A): structured diagnostics — see ADR 0016.
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
