package kolt.concurrency

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kolt.infra.eprintln
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.linux.LOCK_EX
import platform.linux.LOCK_NB
import platform.linux.LOCK_UN
import platform.linux.flock
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.S_IRGRP
import platform.posix.S_IROTH
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.errno
import platform.posix.open
import platform.posix.strerror
import platform.posix.usleep

class LockHandle internal constructor(private val fd: Int, private val path: String) :
  AutoCloseable {
  private var closed = false

  @OptIn(ExperimentalForeignApi::class)
  override fun close() {
    if (closed) return
    closed = true
    while (flock(fd, LOCK_UN) != 0) {
      if (errno != EINTR) break
    }
    while (close(fd) != 0) {
      if (errno != EINTR) break
    }
  }
}

sealed class LockError {
  data class TimedOut(val waitedMs: Long) : LockError()

  data class IoError(val errno: Int, val message: String) : LockError()
}

object ProjectLock {
  const val DEFAULT_TIMEOUT_MS: Long = 30_000L

  private const val LOCK_FILE_NAME = ".kolt-build.lock"
  private const val POLL_INTERVAL_MS: Long = 100L

  @OptIn(ExperimentalForeignApi::class)
  fun acquire(
    buildDir: String,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    onWait: () -> Unit = { defaultOnWait() },
  ): Result<LockHandle, LockError> {
    val path = "$buildDir/$LOCK_FILE_NAME"
    val mode = (S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH).toUInt()
    val fd = open(path, O_CREAT or O_RDWR, mode)
    if (fd < 0) {
      val e = errno
      return Err(LockError.IoError(e, strerrorMessage(e)))
    }

    if (tryFlock(fd)) {
      return Ok(LockHandle(fd, path))
    } else {
      val e = errno
      if (!isWouldBlock(e)) {
        closeFd(fd)
        return Err(LockError.IoError(e, strerrorMessage(e)))
      }
    }

    if (timeoutMs <= 0L) {
      closeFd(fd)
      return Err(LockError.TimedOut(0L))
    }

    onWait()

    var waited = 0L
    while (waited < timeoutMs) {
      val sleepMs = minOf(POLL_INTERVAL_MS, timeoutMs - waited)
      usleep((sleepMs * 1000L).toUInt())
      waited += sleepMs
      if (tryFlock(fd)) {
        return Ok(LockHandle(fd, path))
      }
      val e = errno
      if (!isWouldBlock(e)) {
        closeFd(fd)
        return Err(LockError.IoError(e, strerrorMessage(e)))
      }
    }
    closeFd(fd)
    return Err(LockError.TimedOut(waited))
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun tryFlock(fd: Int): Boolean {
    while (true) {
      val rc = flock(fd, LOCK_EX or LOCK_NB)
      if (rc == 0) return true
      if (errno == EINTR) continue
      return false
    }
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun closeFd(fd: Int) {
    while (close(fd) != 0) {
      if (errno != EINTR) break
    }
  }
}

// EAGAIN and EWOULDBLOCK are equal on Linux/glibc, but the standard allows
// them to differ — accept either to match flock(2) man page wording.
private fun isWouldBlock(e: Int): Boolean = e == EWOULDBLOCK || e == platform.posix.EAGAIN

@OptIn(ExperimentalForeignApi::class)
private fun strerrorMessage(e: Int): String = strerror(e)?.toKString() ?: "errno=$e"

private fun defaultOnWait() {
  eprintln("kolt: another kolt is running, waiting...")
}
