package kolt.daemon.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirror of JVM kolt.daemon.protocol.Message. Field/serial names are
// load-bearing wire contract (ADR 0016) — drift breaks the daemon.
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
    // #376: governs the daemon's BTA workingDirectory segment so main
    // and test compiles in the same project don't share an inputsCache.
    val compileScope: CompileScope = CompileScope.Main,
    // #376: forwarded as `-Xfriend-paths=<dir>` so the test compile
    // sees `internal` symbols from the main classes.
    val friendPaths: List<String> = emptyList(),
  ) : Message

  @Serializable
  @SerialName("CompileResult")
  data class CompileResult(
    val exitCode: Int,
    val diagnostics: List<Diagnostic>,
    val stdout: String,
    val stderr: String,
  ) : Message

  @Serializable @SerialName("Ping") data object Ping : Message

  @Serializable @SerialName("Pong") data object Pong : Message

  @Serializable @SerialName("Shutdown") data object Shutdown : Message
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

// #376 — mirror of `kolt.daemon.protocol.CompileScope`. Wire-level enum
// shared by the native client and the JVM daemon.
@Serializable
enum class CompileScope {
  Main,
  Test,
}
