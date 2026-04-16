package kolt.infra

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
    is ProcessError.NonZeroExit -> "$context failed with exit code ${error.exitCode}"
    is ProcessError.EmptyArgs -> "no command to execute"
    is ProcessError.ForkFailed -> "failed to start $context process"
    is ProcessError.WaitFailed -> "failed waiting for $context process"
    is ProcessError.SignalKilled -> "$context process was killed"
    is ProcessError.PopenFailed -> "failed to start $context process"
}

@OptIn(ExperimentalForeignApi::class)
fun executeCommand(args: List<String>): Result<Int, ProcessError> {
    if (args.isEmpty()) return Err(ProcessError.EmptyArgs)

    val pid = fork()
    if (pid < 0) {
        return Err(ProcessError.ForkFailed)
    }
    if (pid == 0) {
        memScoped {
            val argv = allocArray<CPointerVar<ByteVar>>(args.size + 1)
            for (i in args.indices) {
                argv[i] = args[i].cstr.ptr
            }
            argv[args.size] = null
            execvp(args[0], argv)
            _exit(127)
        }
    }
    memScoped {
        val status = alloc<IntVar>()
        while (waitpid(pid, status.ptr, 0) == -1) {
            if (errno != EINTR) return Err(ProcessError.WaitFailed)
        }
        val raw = status.value
        return if ((raw and 0x7F) == 0) {
            val exitCode = (raw shr 8) and 0xFF
            if (exitCode == 0) Ok(0) else Err(ProcessError.NonZeroExit(exitCode))
        } else {
            Err(ProcessError.SignalKilled)
        }
    }
}

// Double-fork detach. Ok(Unit) means the fork chain completed, not
// that execvp succeeded — callers must poll the socket to confirm.
@OptIn(ExperimentalForeignApi::class)
fun spawnDetached(args: List<String>, logPath: String? = null): Result<Unit, ProcessError> {
    if (args.isEmpty()) return Err(ProcessError.EmptyArgs)

    val pid1 = fork()
    if (pid1 < 0) return Err(ProcessError.ForkFailed)

    if (pid1 == 0) {
        if (setsid() < 0) {
            fputs("spawnDetached: setsid() failed, errno=$errno\n", stderr)
        }
        val pid2 = fork()
        if (pid2 < 0) _exit(127)
        if (pid2 > 0) _exit(0)

        val devNull = open("/dev/null", O_RDWR)
        val logFd = if (logPath != null) {
            val mode = S_IRUSR or S_IWUSR
            open(logPath, O_WRONLY or O_CREAT or O_APPEND, mode)
        } else {
            -1
        }
        val stdinFd = if (devNull >= 0) devNull else logFd
        val outFd = if (logFd >= 0) logFd else devNull
        if (stdinFd >= 0) {
            dup2(stdinFd, STDIN_FILENO)
        } else {
            platform.posix.close(STDIN_FILENO)
        }
        if (outFd >= 0) {
            dup2(outFd, STDOUT_FILENO)
            dup2(outFd, STDERR_FILENO)
        }
        if (devNull > STDERR_FILENO) platform.posix.close(devNull)
        if (logFd > STDERR_FILENO && logFd != devNull) platform.posix.close(logFd)

        memScoped {
            val argv = allocArray<CPointerVar<ByteVar>>(args.size + 1)
            for (i in args.indices) {
                argv[i] = args[i].cstr.ptr
            }
            argv[args.size] = null
            execvp(args[0], argv)
            _exit(127)
        }
    }

    memScoped {
        val status = alloc<IntVar>()
        while (waitpid(pid1, status.ptr, 0) == -1) {
            if (errno != EINTR) return Err(ProcessError.WaitFailed)
        }
    }
    return Ok(Unit)
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
