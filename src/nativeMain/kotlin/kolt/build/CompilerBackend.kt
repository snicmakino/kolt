package kolt.build

import com.github.michaelbull.result.Result
import kolt.daemon.wire.CompileScope
import kolt.daemon.wire.Diagnostic
import kolt.daemon.wire.Severity

interface CompilerBackend {
  fun compile(request: CompileRequest): Result<CompileOutcome, CompileError>
}

// Field order kept identical to Message.Compile — divergence goes in DaemonCompilerBackend.
data class CompileRequest(
  val workingDir: String,
  val classpath: List<String>,
  val sources: List<String>,
  val outputPath: String,
  val moduleName: String,
  val extraArgs: List<String> = emptyList(),
  val compileScope: CompileScope = CompileScope.Main,
  val friendPaths: List<String> = emptyList(),
)

data class CompileOutcome(val stdout: String, val stderr: String)

sealed interface CompileError {
  // Daemon path: diagnostics populated from Message.CompileResult.diagnostics.
  // Subprocess path: empty (kotlinc writes to inherited stderr directly).
  // Use renderCompilationFailure() to reunite both streams without double-printing.
  data class CompilationFailed(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val diagnostics: List<Diagnostic> = emptyList(),
  ) : CompileError

  sealed interface BackendUnavailable : CompileError {
    data object ForkFailed : BackendUnavailable

    data object WaitFailed : BackendUnavailable

    data object SignalKilled : BackendUnavailable

    data object PopenFailed : BackendUnavailable

    data class Other(val detail: String) : BackendUnavailable
  }

  data object NoCommand : CompileError

  data class InternalMisuse(val detail: String) : CompileError
}

// Daemon-path only: reunites structured diagnostics and leftover stderr.
// Subprocess-path callers skip this — kotlinc already wrote to inherited stderr.
internal fun renderCompilationFailure(error: CompileError.CompilationFailed): String {
  val parts = mutableListOf<String>()
  for (diagnostic in error.diagnostics) {
    parts += formatDiagnostic(diagnostic)
  }
  if (error.stderr.isNotEmpty()) parts += error.stderr.trimEnd('\n')
  return parts.joinToString("\n")
}

private fun formatDiagnostic(diagnostic: Diagnostic): String {
  val location =
    if (diagnostic.file == null) {
      ""
    } else
      buildString {
        append(diagnostic.file)
        if (diagnostic.line != null) append(':').append(diagnostic.line)
        if (diagnostic.column != null) append(':').append(diagnostic.column)
      }
  val severity =
    when (diagnostic.severity) {
      Severity.Error -> "error"
      Severity.Warning -> "warning"
      Severity.Info -> "info"
      Severity.Logging -> "logging"
    }
  return if (location.isEmpty()) {
    "$severity: ${diagnostic.message}"
  } else {
    "$location: $severity: ${diagnostic.message}"
  }
}

fun formatCompileError(error: CompileError, context: String): String =
  when (error) {
    is CompileError.CompilationFailed -> "error: $context failed with exit code ${error.exitCode}"
    is CompileError.BackendUnavailable.ForkFailed -> "error: failed to start $context process"
    is CompileError.BackendUnavailable.PopenFailed -> "error: failed to start $context process"
    is CompileError.BackendUnavailable.WaitFailed -> "error: failed waiting for $context process"
    is CompileError.BackendUnavailable.SignalKilled -> "error: $context process was killed"
    is CompileError.BackendUnavailable.Other ->
      "error: $context backend unavailable: ${error.detail}"
    is CompileError.NoCommand -> "error: no command to execute"
    is CompileError.InternalMisuse -> "error: $context internal bug: ${error.detail}"
  }
