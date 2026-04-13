package kolt.build

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.infra.ProcessError
import kolt.infra.executeCommand

// Legacy path: fork+exec a managed kotlinc and let it write class files directly.
// This is what kolt has been doing since day one; S1 only wraps it behind the
// CompilerBackend seam so S5+ can slot a daemon-backed implementation beside it.
class SubprocessCompilerBackend(
    private val kotlincBin: String,
) : CompilerBackend {

    override fun compile(request: CompileRequest): Result<CompileOutcome, CompileError> {
        val argv = subprocessArgv(kotlincBin, request)
        executeCommand(argv).getOrElse { err ->
            return Err(mapProcessErrorToCompileError(err))
        }
        // kotlinc prints diagnostics directly to the inherited stdout/stderr,
        // so we have nothing to capture at this layer. DaemonCompilerBackend
        // will populate these from Message.CompileResult. Structured
        // diagnostics for both paths are deferred per ADR 0016.
        return Ok(CompileOutcome(stdout = "", stderr = ""))
    }
}

// Pure argv construction, factored out so BuilderTest-style unit tests can
// cross-check it against the legacy buildCommand() output without spawning a
// process. moduleName is intentionally not forwarded to kotlinc: the legacy
// path never set -module-name, and matching that exactly is what makes S1 a
// behaviour-preserving refactor. DaemonCompilerBackend uses moduleName for
// Message.Compile.moduleName, which is a separate concern.
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
    // execvp() failure in the child surfaces as _exit(127), which arrives here
    // as NonZeroExit(127) — indistinguishable from a kotlinc that legitimately
    // exited with 127. We treat the whole NonZeroExit family as a user-level
    // compilation failure, matching the legacy formatProcessError() behaviour.
    is ProcessError.NonZeroExit -> CompileError.CompilationFailed(
        exitCode = error.exitCode,
        stdout = "",
        stderr = "",
    )
    is ProcessError.SignalKilled -> CompileError.BackendUnavailable.SignalKilled
}
