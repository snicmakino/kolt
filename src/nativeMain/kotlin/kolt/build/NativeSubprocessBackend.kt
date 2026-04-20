package kolt.build

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.infra.ProcessError
import kolt.infra.executeCommand

// Baseline native backend: invokes `konanc` as a subprocess. This is the
// pre-ADR-0024 behaviour kept intact so FallbackNativeCompilerBackend can
// retry here when the daemon is unreachable. stderr is not captured —
// konanc inherits the parent process's stderr fd, same as the JVM-side
// SubprocessCompilerBackend.
class NativeSubprocessBackend(
    private val konancBin: String,
) : NativeCompilerBackend {

    override fun compile(args: List<String>): Result<NativeCompileOutcome, NativeCompileError> {
        val argv = nativeSubprocessArgv(konancBin, args)
        executeCommand(argv).getOrElse { err ->
            return Err(mapProcessErrorToNativeCompileError(err))
        }
        return Ok(NativeCompileOutcome(stderr = ""))
    }
}

// `args` are the konanc argv AFTER the binary (ADR 0024 §4); the subprocess
// path prepends the binary here. The daemon path forwards `args` as-is.
internal fun nativeSubprocessArgv(konancBin: String, args: List<String>): List<String> = buildList {
    add(konancBin)
    addAll(args)
}

internal fun mapProcessErrorToNativeCompileError(error: ProcessError): NativeCompileError = when (error) {
    is ProcessError.EmptyArgs -> NativeCompileError.NoCommand
    is ProcessError.ForkFailed -> NativeCompileError.BackendUnavailable.ForkFailed
    is ProcessError.WaitFailed -> NativeCompileError.BackendUnavailable.WaitFailed
    is ProcessError.PopenFailed -> NativeCompileError.BackendUnavailable.PopenFailed
    is ProcessError.NonZeroExit -> NativeCompileError.CompilationFailed(
        exitCode = error.exitCode,
        stderr = "",
    )
    is ProcessError.SignalKilled -> NativeCompileError.BackendUnavailable.SignalKilled
}
