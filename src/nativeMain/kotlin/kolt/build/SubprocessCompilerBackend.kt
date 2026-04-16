package kolt.build

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.infra.ProcessError
import kolt.infra.executeCommand

class SubprocessCompilerBackend(
    private val kotlincBin: String,
) : CompilerBackend {

    override fun compile(request: CompileRequest): Result<CompileOutcome, CompileError> {
        val argv = subprocessArgv(kotlincBin, request)
        executeCommand(argv).getOrElse { err ->
            return Err(mapProcessErrorToCompileError(err))
        }
        return Ok(CompileOutcome(stdout = "", stderr = ""))
    }
}

// moduleName intentionally not forwarded — subprocess path never set -module-name.
internal fun subprocessArgv(kotlincBin: String, request: CompileRequest): List<String> = buildList {
    add(kotlincBin)
    if (request.classpath.isNotEmpty()) {
        add("-cp")
        add(request.classpath.joinToString(":"))
    }
    addAll(request.sources)
    addAll(request.extraArgs)
    add("-d")
    add(request.outputPath)
}

internal fun mapProcessErrorToCompileError(error: ProcessError): CompileError = when (error) {
    is ProcessError.EmptyArgs -> CompileError.NoCommand
    is ProcessError.ForkFailed -> CompileError.BackendUnavailable.ForkFailed
    is ProcessError.WaitFailed -> CompileError.BackendUnavailable.WaitFailed
    is ProcessError.PopenFailed -> CompileError.BackendUnavailable.PopenFailed
    is ProcessError.NonZeroExit -> CompileError.CompilationFailed(
        exitCode = error.exitCode,
        stdout = "",
        stderr = "",
    )
    is ProcessError.SignalKilled -> CompileError.BackendUnavailable.SignalKilled
}
