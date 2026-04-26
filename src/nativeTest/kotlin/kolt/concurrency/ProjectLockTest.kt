@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.concurrency

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.fileExists
import kolt.infra.removeDirectoryRecursive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.ENOENT
import platform.posix.mkdtemp

class ProjectLockTest {

  private val createdDirs = mutableListOf<String>()

  @AfterTest
  fun cleanup() {
    for (dir in createdDirs) {
      if (fileExists(dir)) {
        removeDirectoryRecursive(dir)
      }
    }
    createdDirs.clear()
  }

  @Test
  fun firstAcquireReturnsOkImmediately() {
    val buildDir = createTempDir("kolt-lock-a-")
    val handle =
      ProjectLock.acquire(buildDir, timeoutMs = 1_000L).getOrElse {
        error("expected Ok but got Err: $it")
      }
    handle.close()
  }

  @Test
  fun secondAcquireWhilePeerHeldTimesOutAndCallsOnWaitOnce() {
    val buildDir = createTempDir("kolt-lock-b-")
    val first =
      ProjectLock.acquire(buildDir, timeoutMs = 1_000L).getOrElse {
        error("expected Ok on first acquire but got Err: $it")
      }
    try {
      var onWaitCalls = 0
      val err =
        ProjectLock.acquire(buildDir, timeoutMs = 200L, onWait = { onWaitCalls += 1 }).getError()
      assertNotNull(err, "second acquire must fail while peer holds the lock")
      assertTrue(err is LockError.TimedOut, "expected TimedOut, got $err")
      assertEquals(1, onWaitCalls, "onWait must fire exactly once when entering wait state")
    } finally {
      first.close()
    }
  }

  @Test
  fun thirdAcquireSucceedsAfterFirstHandleClosed() {
    val buildDir = createTempDir("kolt-lock-c-")
    val first =
      ProjectLock.acquire(buildDir, timeoutMs = 1_000L).getOrElse {
        error("expected Ok on first acquire but got Err: $it")
      }
    first.close()

    val third =
      ProjectLock.acquire(buildDir, timeoutMs = 1_000L).getOrElse {
        error("expected Ok on third acquire but got Err: $it")
      }
    third.close()
  }

  @Test
  fun acquireWithNonExistentBuildDirReturnsIoError() {
    val missing = "/tmp/kolt-lock-does-not-exist-xyz/${platform.posix.getpid()}/nope"
    val err = ProjectLock.acquire(missing, timeoutMs = 100L).getError()
    assertNotNull(err, "expected Err for non-existent buildDir")
    assertTrue(err is LockError.IoError, "expected IoError, got $err")
    assertEquals(ENOENT, err.errno)
  }

  @Test
  fun zeroTimeoutWithPeerHeldFailsImmediatelyWithoutOnWait() {
    val buildDir = createTempDir("kolt-lock-e-")
    val first =
      ProjectLock.acquire(buildDir, timeoutMs = 1_000L).getOrElse {
        error("expected Ok on first acquire but got Err: $it")
      }
    try {
      var onWaitCalls = 0
      val err =
        ProjectLock.acquire(buildDir, timeoutMs = 0L, onWait = { onWaitCalls += 1 }).getError()
      assertNotNull(err, "zero-timeout acquire must fail when peer holds")
      assertTrue(err is LockError.TimedOut, "expected TimedOut, got $err")
      assertEquals(0L, err.waitedMs)
      assertEquals(0, onWaitCalls, "onWait must NOT fire when timeoutMs is 0")
    } finally {
      first.close()
    }
  }

  private fun createTempDir(prefix: String): String {
    val template = "/tmp/${prefix}XXXXXX"
    val buf = template.encodeToByteArray().copyOf(template.length + 1)
    buf.usePinned { pinned ->
      val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
      val path = result.toKString()
      createdDirs.add(path)
      return path
    }
  }
}
