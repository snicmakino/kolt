package kolt.build

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError

// ADR 0024 §7: daemon is never load-bearing for correctness. On any
// BackendUnavailable / InternalMisuse from the primary (daemon) path, retry
// through the fallback (subprocess) path. Real compilation errors
// (CompilationFailed) pass through untouched — the subprocess would fail
// the same way and the retry would just add a cold-start tax.
//
// Composition contract: `primary` is the daemon path, `fallback` is the
// subprocess path. Do NOT flip them. `NativeSubprocessBackend` maps
// `ProcessError.SignalKilled` to `BackendUnavailable.SignalKilled`, which
// is fallback-eligible — if the subprocess sat on the primary side, a
// SIGKILL'd konanc would incorrectly trigger a daemon retry. The fallback
// path is the last resort, not a peer.
class FallbackNativeCompilerBackend(
    internal val primary: NativeCompilerBackend,
    internal val fallback: NativeCompilerBackend,
    private val onFallback: (NativeCompileError) -> Unit = {},
) : NativeCompilerBackend {

    override fun compile(args: List<String>): Result<NativeCompileOutcome, NativeCompileError> {
        val primaryResult = primary.compile(args)
        val primaryError = primaryResult.getError() ?: return primaryResult
        if (!isNativeFallbackEligible(primaryError)) return Err(primaryError)
        onFallback(primaryError)
        return fallback.compile(args)
    }
}
