package kolt.build

import com.github.michaelbull.result.Result

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
    data class CompilationFailed(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
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
