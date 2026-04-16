package kolt.build

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError

// ADR 0016: daemon is never load-bearing for correctness.
class FallbackCompilerBackend(
    internal val primary: CompilerBackend,
    internal val fallback: CompilerBackend,
    private val onFallback: (CompileError) -> Unit = {},
) : CompilerBackend {

    override fun compile(request: CompileRequest): Result<CompileOutcome, CompileError> {
        val primaryResult = primary.compile(request)
        val primaryError = primaryResult.getError() ?: return primaryResult
        if (!isFallbackEligible(primaryError)) return Err(primaryError)
        onFallback(primaryError)
        return fallback.compile(request)
    }
}

fun isFallbackEligible(error: CompileError): Boolean = when (error) {
    is CompileError.BackendUnavailable -> true
    is CompileError.InternalMisuse -> true
    is CompileError.CompilationFailed -> false
    is CompileError.NoCommand -> false
}
