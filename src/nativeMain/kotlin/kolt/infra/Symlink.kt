package kolt.infra

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.errno
import platform.posix.getpid
import platform.posix.rename
import platform.posix.symlink
import platform.posix.unlink

sealed interface SymlinkError {
  data class CreateFailed(val tmp: String, val errno: Int) : SymlinkError

  data class RenameFailed(val link: String, val errno: Int) : SymlinkError
}

// Stage the new link at a sibling tmp path, then `rename(2)` it over the
// target. `rename(2)` is the only step that mutates `linkPath`, and the
// kernel guarantees it atomically swaps an existing symlink — so a
// concurrent or subsequent `kolt` invocation always observes either the
// pre- or post-update binary, never a half-written link.
@OptIn(ExperimentalForeignApi::class)
fun replaceSymlinkAtomically(linkPath: String, newTarget: String): Result<Unit, SymlinkError> {
  val tmp = "$linkPath.tmp.${getpid()}"
  if (symlink(newTarget, tmp) != 0) {
    return Err(SymlinkError.CreateFailed(tmp = tmp, errno = errno))
  }
  if (rename(tmp, linkPath) != 0) {
    val e = errno
    unlink(tmp)
    return Err(SymlinkError.RenameFailed(link = linkPath, errno = e))
  }
  return Ok(Unit)
}
