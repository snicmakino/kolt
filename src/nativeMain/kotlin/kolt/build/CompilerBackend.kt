package kolt.build

import com.github.michaelbull.result.Result
import kolt.daemon.wire.Diagnostic
import kolt.daemon.wire.Severity

// A CompilerBackend turns a CompileRequest into a compiled output. Two
// implementations are planned:
//   - SubprocessCompilerBackend: invokes kotlinc via fork+exec (legacy path).
//   - DaemonCompilerBackend: sends Message.Compile over a Unix domain socket
//     to a warm kolt-compiler-daemon process (#14 Phase A).
//
// The interface exists so doBuild() can swap backends without knowing which
// one runs the compiler, and so the fallback policy can live in a composition
// (FallbackCompilerBackend) rather than inside either backend.
interface CompilerBackend {
    fun compile(request: CompileRequest): Result<CompileOutcome, CompileError>
}

// Mirrors kolt.daemon.protocol.Message.Compile (JVM-side wire type). Field
// order is kept identical to Message.Compile so DaemonCompilerBackend can
// translate positionally without a lossy adapter. Future divergence should be
// absorbed inside DaemonCompilerBackend, not here.
data class CompileRequest(
    val workingDir: String,
    val classpath: List<String>,
    val sources: List<String>,
    val outputPath: String,
    val moduleName: String,
    val extraArgs: List<String> = emptyList(),
)

data class CompileOutcome(
    val stdout: String,
    val stderr: String,
)

// CompileError variants carry enough discriminators to reproduce the exact
// user-facing wording formatProcessError used before the CompilerBackend seam
// was extracted (see formatCompileError). Keeping wording parity is what makes
// the S1 refactor behaviour-preserving from the user's point of view.
sealed interface CompileError {
    // Compiler ran but the user's Kotlin source did not compile. Not fallback
    // eligible — the subprocess path would fail identically.
    //
    // `diagnostics` is the structured form of the compile errors
    // `BtaIncrementalCompiler` captures via its `KotlinLogger` hook and
    // `DaemonServer.icErrorToReply` splits out of the flat stderr stream
    // (see `DiagnosticParser` on the daemon side). Daemon path: populated
    // from `Message.CompileResult.diagnostics`. Subprocess path: empty,
    // because kotlinc's diagnostics go directly to the inherited stderr
    // and the native client never parses them. Callers that want a
    // complete, ordered user-visible error rendering should prefer
    // `renderCompilationFailure` below over reading `stderr` / `diagnostics`
    // independently — that helper reunites the two streams without risk
    // of double-printing on the daemon path.
    data class CompilationFailed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val diagnostics: List<Diagnostic> = emptyList(),
    ) : CompileError

    // Backend itself could not run the compiler. Fallback eligible. Distinct
    // variants preserve the legacy per-ProcessError wording; Other is reserved
    // for daemon-only failures (socket closed, protocol error etc.).
    sealed interface BackendUnavailable : CompileError {
        data object ForkFailed : BackendUnavailable
        data object WaitFailed : BackendUnavailable
        data object SignalKilled : BackendUnavailable
        data object PopenFailed : BackendUnavailable
        data class Other(val detail: String) : BackendUnavailable
    }

    // Empty argv / no command to execute. Kept as its own case because the
    // legacy wording "error: no command to execute" does not embed the context
    // string, unlike every other variant.
    data object NoCommand : CompileError

    // kolt's own bug (e.g. daemon exit code 64 without a clearer signal).
    // Logged as an error rather than a warning so self-inflicted problems do
    // not disappear into the fallback path silently.
    data class InternalMisuse(val detail: String) : CompileError
}

// Renders every diagnostic the daemon captured, plus any leftover plain-
// text stderr lines, in the order kotlinc would have produced them.
// Used by `BuildCommands` to print the full compile error body before
// the one-line summary `formatCompileError` produces. Pre-B-2c the
// daemon reply had everything in `stderr`; B-2c split the structured
// lines out into `diagnostics`. This helper handles both shapes so
// nothing goes missing regardless of which field is populated.
//
// Subprocess path callers should skip this helper — kotlinc already
// wrote its diagnostics to the inherited stderr stream before the
// CompilerBackend turned the non-zero exit into a CompilationFailed.
// Calling `renderCompilationFailure` on a subprocess-path error would
// typically produce an empty string anyway (both fields are empty),
// but the ordering invariant is that daemon-path callers print this
// block and subprocess-path callers do not.
fun renderCompilationFailure(error: CompileError.CompilationFailed): String {
    val parts = mutableListOf<String>()
    for (diagnostic in error.diagnostics) {
        parts += formatDiagnostic(diagnostic)
    }
    if (error.stderr.isNotEmpty()) parts += error.stderr.trimEnd('\n')
    return parts.joinToString("\n")
}

private fun formatDiagnostic(diagnostic: Diagnostic): String {
    val location = buildString {
        if (diagnostic.file != null) append(diagnostic.file)
        if (diagnostic.line != null) append(':').append(diagnostic.line)
        if (diagnostic.column != null) append(':').append(diagnostic.column)
    }
    val severity = when (diagnostic.severity) {
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

fun formatCompileError(error: CompileError, context: String): String = when (error) {
    is CompileError.CompilationFailed -> "error: $context failed with exit code ${error.exitCode}"
    is CompileError.BackendUnavailable.ForkFailed -> "error: failed to start $context process"
    is CompileError.BackendUnavailable.PopenFailed -> "error: failed to start $context process"
    is CompileError.BackendUnavailable.WaitFailed -> "error: failed waiting for $context process"
    is CompileError.BackendUnavailable.SignalKilled -> "error: $context process was killed"
    is CompileError.BackendUnavailable.Other -> "error: $context backend unavailable: ${error.detail}"
    is CompileError.NoCommand -> "error: no command to execute"
    is CompileError.InternalMisuse -> "error: $context internal bug: ${error.detail}"
}
