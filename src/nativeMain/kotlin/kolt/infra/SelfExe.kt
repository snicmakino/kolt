package kolt.infra

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.PATH_MAX
import platform.posix.errno
import platform.posix.readlink
import platform.posix.strerror

data class SelfExeError(val errno: Int, val message: String)

// Resolves the absolute path of the running kolt binary via the Linux
// /proc/self/exe magic symlink. Used by DaemonCompilerBackend's jar
// fallback chain to locate the sibling libexec/ directory in installed
// layouts and the sibling build/libs/ jar in dev layouts.
//
// PATH_MAX is the accepted upper bound for a Linux filesystem path.
// readlink does not write a trailing NUL, so we reserve one byte and
// terminate the buffer explicitly before decoding.
@OptIn(ExperimentalForeignApi::class)
fun readSelfExe(): Result<String, SelfExeError> {
    return memScoped {
        val bufSize = PATH_MAX
        val buf = allocArray<ByteVar>(bufSize)
        val n = readlink("/proc/self/exe", buf, (bufSize - 1).convert())
        if (n < 0) {
            val e = errno
            return@memScoped Err(
                SelfExeError(
                    errno = e,
                    message = strerror(e)?.toKString() ?: "errno=$e",
                ),
            )
        }
        buf[n] = 0
        Ok(buf.toKString())
    }
}
