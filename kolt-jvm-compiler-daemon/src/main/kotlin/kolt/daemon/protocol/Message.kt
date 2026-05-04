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
    // #376: compile scope governs the BTA workingDirectory segment so
    // main and test compiles in the same project don't share an
    // inputsCache. Defaults to Main so an old client that omits the
    // field keeps the pre-#376 behaviour for the main compile path.
    val compileScope: CompileScope = CompileScope.Main,
    // #376: friend paths translate to `-Xfriend-paths=<path>` so the
    // test compile can see `internal` symbols from the main compile's
    // classes directory. Empty for the main compile.
    val friendPaths: List<String> = emptyList(),
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

// Wire-level compile scope (#376). Mirror of `kolt.daemon.ic.CompileScope`;
// kept in the protocol package so the wire definition does not pull in
// the `:ic` adapter module.
@Serializable
enum class CompileScope {
  Main,
  Test,
}
