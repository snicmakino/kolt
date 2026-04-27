@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import kolt.concurrency.ProjectLock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.posix.setenv
import platform.posix.unsetenv

class BuildCommandsLockTimeoutEnvTest {

  private val envVar = "KOLT_LOCK_TIMEOUT_MS"

  @AfterTest
  fun cleanup() {
    unsetenv(envVar)
  }

  @Test
  fun unsetReturnsDefault() {
    unsetenv(envVar)
    assertEquals(ProjectLock.DEFAULT_TIMEOUT_MS, parseLockTimeoutMs())
  }

  @Test
  fun emptyStringReturnsDefault() {
    setenv(envVar, "", 1)
    assertEquals(ProjectLock.DEFAULT_TIMEOUT_MS, parseLockTimeoutMs())
  }

  @Test
  fun nonNumericReturnsDefault() {
    setenv(envVar, "abc", 1)
    assertEquals(ProjectLock.DEFAULT_TIMEOUT_MS, parseLockTimeoutMs())
  }

  @Test
  fun negativeReturnsDefault() {
    setenv(envVar, "-5", 1)
    assertEquals(ProjectLock.DEFAULT_TIMEOUT_MS, parseLockTimeoutMs())
  }

  @Test
  fun zeroIsAccepted() {
    setenv(envVar, "0", 1)
    assertEquals(0L, parseLockTimeoutMs())
  }

  @Test
  fun positiveValueIsAccepted() {
    setenv(envVar, "200", 1)
    assertEquals(200L, parseLockTimeoutMs())
  }

  @Test
  fun defaultMagnitudeReturnsThirtySeconds() {
    setenv(envVar, "30000", 1)
    assertEquals(30_000L, parseLockTimeoutMs())
  }
}
