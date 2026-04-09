package keel

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.cinterop.*
import platform.posix.*

sealed class ProcessError {
    data object EmptyArgs : ProcessError()
    data object ForkFailed : ProcessError()
    data object WaitFailed : ProcessError()
    data class NonZeroExit(val exitCode: Int) : ProcessError()
    data object SignalKilled : ProcessError()
    data object PopenFailed : ProcessError()
}

fun formatProcessError(error: ProcessError, context: String): String = when (error) {
    is ProcessError.NonZeroExit -> "error: $context failed with exit code ${error.exitCode}"
    is ProcessError.EmptyArgs -> "error: no command to execute"
    is ProcessError.ForkFailed -> "error: failed to start $context process"
    is ProcessError.WaitFailed -> "error: failed waiting for $context process"
    is ProcessError.SignalKilled -> "error: $context process was killed"
    is ProcessError.PopenFailed -> "error: failed to start $context process"
}

@OptIn(ExperimentalForeignApi::class)
fun executeCommand(args: List<String>): Result<Int, ProcessError> {
    if (args.isEmpty()) return Err(ProcessError.EmptyArgs)

    val pid = fork()
    if (pid < 0) {
        return Err(ProcessError.ForkFailed)
    }
    if (pid == 0) {
        // child process
        memScoped {
            val argv = allocArray<CPointerVar<ByteVar>>(args.size + 1)
            for (i in args.indices) {
                argv[i] = args[i].cstr.ptr
            }
            argv[args.size] = null
            execvp(args[0], argv)
            // execvp only returns on error
            _exit(127)
        }
    }
    // parent process
    memScoped {
        val status = alloc<IntVar>()
        while (waitpid(pid, status.ptr, 0) == -1) {
            if (errno != EINTR) return Err(ProcessError.WaitFailed)
        }
        val raw = status.value
        // WIFEXITED: (status & 0x7F) == 0
        return if ((raw and 0x7F) == 0) {
            val exitCode = (raw shr 8) and 0xFF
            if (exitCode == 0) Ok(0) else Err(ProcessError.NonZeroExit(exitCode))
        } else {
            Err(ProcessError.SignalKilled)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun executeAndCapture(command: String): Result<String, ProcessError> {
    val fp = popen(command, "r") ?: return Err(ProcessError.PopenFailed)
    val output = StringBuilder()
    memScoped {
        val buffer = allocArray<ByteVar>(4096)
        while (true) {
            val line = fgets(buffer, 4096, fp) ?: break
            output.append(line.toKString())
        }
    }
    val status = pclose(fp)
    // WIFEXITED check
    val exitCode = if ((status and 0x7F) == 0) {
        (status shr 8) and 0xFF
    } else {
        return Err(ProcessError.SignalKilled)
    }
    return if (exitCode == 0) {
        Ok(output.toString())
    } else {
        Err(ProcessError.NonZeroExit(exitCode))
    }
}
