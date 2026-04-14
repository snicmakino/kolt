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

// Spawn a child that keeps running after the parent exits. Used by
// DaemonCompilerBackend to start the warm compiler JVM: the kolt
// process that initiates the spawn is short-lived, but the daemon it
// creates must survive across builds.
//
// Double-fork idiom: fork → setsid → fork → exec. The intermediate
// child calls setsid() to become the leader of a new session with no
// controlling terminal, then forks a grandchild. The intermediate
// child immediately _exit(0), leaving the grandchild reparented to
// PID 1 (so our original parent never has to reap it) and detached
// from the session it inherited. [logPath], if non-null, receives the
// grandchild's stdout and stderr (append mode) — stdin is always
// redirected to /dev/null. The intermediate child is reaped
// synchronously by [executeCommand]'s sibling waitpid loop so the
// caller observes no zombie.
//
// This is separate from [executeCommand] because reusing fork+execvp
// from there would entangle the managed-subprocess path (parent waits
// for completion) with the detached-daemon path (parent must not
// wait).
@OptIn(ExperimentalForeignApi::class)
fun spawnDetached(args: List<String>, logPath: String? = null): Result<Unit, ProcessError> {
    if (args.isEmpty()) return Err(ProcessError.EmptyArgs)

    val pid1 = fork()
    if (pid1 < 0) return Err(ProcessError.ForkFailed)

    if (pid1 == 0) {
        // Intermediate child. setsid fails only if we are already a
        // session leader, which should not happen post-fork; ignore
        // the return value rather than abort, because even a shared
        // session is acceptable for the daemon (the daemon does not
        // read from a terminal anyway).
        setsid()
        val pid2 = fork()
        if (pid2 < 0) _exit(127)
        if (pid2 > 0) _exit(0)

        // Grandchild. Redirect fds 0/1/2 before execvp so the daemon
        // never inherits our terminal. If /dev/null is unavailable or
        // the log file cannot be opened, fall through without the
        // redirect — losing a log line is preferable to failing the
        // spawn entirely, and the user can diagnose via ps + strace.
        val devNull = open("/dev/null", O_RDWR)
        if (devNull >= 0) {
            dup2(devNull, STDIN_FILENO)
        }
        // open() is variadic in C (the mode argument is only consulted
        // when O_CREAT is set); K/N exposes the third slot as
        // `vararg Any?`, so we pass the mode as a UInt value and let
        // the vararg plumbing box it.
        val logFd = if (logPath != null) {
            val mode = S_IRUSR or S_IWUSR
            open(logPath, O_WRONLY or O_CREAT or O_APPEND, mode)
        } else {
            -1
        }
        val outFd = if (logFd >= 0) logFd else devNull
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

    // Parent: reap the intermediate child. The grandchild has already
    // been reparented to PID 1 by the time the intermediate exits, so
    // this wait completes as soon as _exit(0) runs above.
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
