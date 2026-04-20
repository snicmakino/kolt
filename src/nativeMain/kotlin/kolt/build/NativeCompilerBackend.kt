package kolt.build

import com.github.michaelbull.result.Result

// Separate from `CompilerBackend` on purpose: ADR 0024 §4 keeps the native
// compilation API flat (konanc args list) rather than the JVM-side structured
// `CompileRequest` (workingDir / classpath / sources / outputPath /
// moduleName). K2Native's entry point is `exec(PrintStream, String[])` and
// there is no BTA for native; a structured request would decompose only to
// recompose on the wire. Keeping the interface args-shaped means the client,
// the `NativeCompile` wire message, and `konanc` all see the same list.
//
// `args` here is the flat argv list *after* the compiler binary. The
// subprocess backend prepends `konanc`; the daemon backend forwards the
// list unchanged because the daemon already has the compiler loaded.
interface NativeCompilerBackend {
    fun compile(args: List<String>): Result<NativeCompileOutcome, NativeCompileError>
}

// Unlike `CompileOutcome`, no `stdout` field — ADR 0024 §4 collapses native
// compiler output into a single `stderr` blob. konanc writes diagnostics
// to stderr; stdout is not observed on either the daemon or subprocess
// path.
data class NativeCompileOutcome(
    val stderr: String,
)

sealed interface NativeCompileError {
    // A clean compiler run that returned non-zero: konanc reported a source
    // error, OOM'd, etc. Not fallback-eligible — the subprocess path would
    // fail the same way.
    data class CompilationFailed(
        val exitCode: Int,
        val stderr: String,
    ) : NativeCompileError

    // The daemon process is unreachable / the subprocess fork failed — the
    // *infrastructure* is unhealthy, not the source. FallbackNativeCompilerBackend
    // treats this as a retry signal to the subprocess path.
    sealed interface BackendUnavailable : NativeCompileError {
        data object ForkFailed : BackendUnavailable
        data object WaitFailed : BackendUnavailable
        data object SignalKilled : BackendUnavailable
        data object PopenFailed : BackendUnavailable
        data class Other(val detail: String) : BackendUnavailable
    }

    data object NoCommand : NativeCompileError

    data class InternalMisuse(val detail: String) : NativeCompileError
}

// Mirrors `isFallbackEligible` on the JVM side (ADR 0016 §5 / ADR 0024 §7).
fun isNativeFallbackEligible(error: NativeCompileError): Boolean = when (error) {
    is NativeCompileError.BackendUnavailable -> true
    is NativeCompileError.InternalMisuse -> true
    is NativeCompileError.CompilationFailed -> false
    NativeCompileError.NoCommand -> false
}

fun formatNativeCompileError(error: NativeCompileError, context: String): String = when (error) {
    is NativeCompileError.CompilationFailed -> "error: $context failed with exit code ${error.exitCode}"
    is NativeCompileError.BackendUnavailable.ForkFailed -> "error: failed to start $context process"
    is NativeCompileError.BackendUnavailable.PopenFailed -> "error: failed to start $context process"
    is NativeCompileError.BackendUnavailable.WaitFailed -> "error: failed waiting for $context process"
    is NativeCompileError.BackendUnavailable.SignalKilled -> "error: $context process was killed"
    is NativeCompileError.BackendUnavailable.Other -> "error: $context backend unavailable: ${error.detail}"
    is NativeCompileError.NoCommand -> "error: no command to execute"
    is NativeCompileError.InternalMisuse -> "error: $context internal bug: ${error.detail}"
}
