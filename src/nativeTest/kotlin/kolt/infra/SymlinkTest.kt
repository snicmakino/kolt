package kolt.infra

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.PATH_MAX
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.getpid
import platform.posix.lstat
import platform.posix.readlink
import platform.posix.stat
import platform.posix.symlink

@OptIn(ExperimentalForeignApi::class)
private fun isSymlink(path: String): Boolean = memScoped {
  val st = alloc<stat>()
  if (lstat(path, st.ptr) != 0) return@memScoped false
  (st.st_mode.toInt() and S_IFMT) == S_IFLNK
}

@OptIn(ExperimentalForeignApi::class)
private fun isRegular(path: String): Boolean = memScoped {
  val st = alloc<stat>()
  if (lstat(path, st.ptr) != 0) return@memScoped false
  (st.st_mode.toInt() and S_IFMT) == S_IFREG
}

@OptIn(ExperimentalForeignApi::class)
private fun symlinkTarget(path: String): String = memScoped {
  val buf = allocArray<ByteVar>(PATH_MAX)
  val n = readlink(path, buf, (PATH_MAX - 1).convert())
  if (n < 0) fail("readlink failed for $path")
  buf[n] = 0
  buf.toKString()
}

class SymlinkTest {

  @Test
  fun createsNewSymlinkWhenLinkPathAbsent() {
    val dir = "/tmp/kolt_symlink_absent_${getpid()}"
    ensureDirectoryRecursive(dir).getOrElse { fail("mkdir failed") }
    try {
      val link = "$dir/kolt"
      val target = "$dir/share/v1/bin/kolt"

      replaceSymlinkAtomically(link, target).getOrElse { fail("expected Ok, got $it") }

      assertTrue(isSymlink(link), "expected $link to be a symlink")
      assertEquals(target, symlinkTarget(link))
    } finally {
      removeDirectoryRecursive(dir)
    }
  }

  @Test
  fun atomicallyReplacesExistingSymlink() {
    val dir = "/tmp/kolt_symlink_replace_${getpid()}"
    ensureDirectoryRecursive(dir).getOrElse { fail("mkdir failed") }
    try {
      val link = "$dir/kolt"
      val oldTarget = "$dir/share/v1/bin/kolt"
      val newTarget = "$dir/share/v2/bin/kolt"
      if (symlink(oldTarget, link) != 0) fail("setup symlink failed")
      assertEquals(oldTarget, symlinkTarget(link))

      replaceSymlinkAtomically(link, newTarget).getOrElse { fail("expected Ok, got $it") }

      assertTrue(isSymlink(link), "expected $link to still be a symlink after replace")
      assertEquals(newTarget, symlinkTarget(link))
    } finally {
      removeDirectoryRecursive(dir)
    }
  }

  @Test
  fun succeedsWhenNewTargetDoesNotExistOnDisk() {
    val dir = "/tmp/kolt_symlink_dangling_${getpid()}"
    ensureDirectoryRecursive(dir).getOrElse { fail("mkdir failed") }
    try {
      val link = "$dir/kolt"
      val missingTarget = "$dir/does/not/exist/kolt"
      assertTrue(!fileExists(missingTarget), "precondition: target must not exist")

      replaceSymlinkAtomically(link, missingTarget).getOrElse {
        fail("dangling symlink must be allowed, got $it")
      }

      assertTrue(isSymlink(link), "expected a (dangling) symlink at $link")
      assertEquals(missingTarget, symlinkTarget(link))
    } finally {
      removeDirectoryRecursive(dir)
    }
  }

  @Test
  fun overwritesRegularFileAtLinkPath() {
    // Not reached in production (installer layout always has a symlink at the
    // link path), but rename(2) overwrites a regular file with the new symlink.
    // Pinned so a future switch to a non-overwriting primitive is caught.
    val dir = "/tmp/kolt_symlink_regfile_${getpid()}"
    ensureDirectoryRecursive(dir).getOrElse { fail("mkdir failed") }
    try {
      val link = "$dir/kolt"
      val target = "$dir/share/v1/bin/kolt"
      writeFileAsString(link, "i am a regular file").getOrElse { fail("setup write failed") }
      assertTrue(isRegular(link), "precondition: link path is a regular file")

      replaceSymlinkAtomically(link, target).getOrElse { fail("expected Ok, got $it") }

      assertTrue(isSymlink(link), "rename(2) should have replaced the regular file with a symlink")
      assertEquals(target, symlinkTarget(link))
    } finally {
      removeDirectoryRecursive(dir)
    }
  }

  @Test
  fun reportsCreateFailedWhenTmpDirectoryAbsent() {
    // Parent directory of linkPath does not exist, so symlink(2) for the tmp
    // path fails — distinct regression from the rename-failure branch.
    val link = "/tmp/kolt_symlink_nodir_${getpid()}/sub/kolt"
    val err = replaceSymlinkAtomically(link, "/some/target").getError()
    assertTrue(err is SymlinkError.CreateFailed, "expected CreateFailed, got $err")
  }
}
