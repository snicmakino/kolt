@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import kolt.concurrency.ProjectLock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.posix.setenv
import platform.posix.unsetenv

// Sanity check that DependencyCommands carries its own private
// `parseLockTimeoutMs` env parser (tasks.md 3.2 explicitly disallows
// shared abstraction with BuildCommands). The full 7-case env-parse
// matrix is covered by BuildCommandsLockTimeoutEnvTest; here we only
// verify the symbol exists and the happy path agrees, so a future
// accidental re-import from BuildCommands would still leave this test
// passing while the 7-case suite continues to gate the parser shape.
class DependencyCommandsLockTimeoutEnvTest {

  private val envVar = "KOLT_LOCK_TIMEOUT_MS"

  @AfterTest
  fun cleanup() {
    unsetenv(envVar)
  }

  @Test
  fun unsetReturnsDefault() {
    unsetenv(envVar)
    assertEquals(ProjectLock.DEFAULT_TIMEOUT_MS, parseDependencyLockTimeoutMs())
  }

  @Test
  fun positiveValueIsAccepted() {
    setenv(envVar, "200", 1)
    assertEquals(200L, parseDependencyLockTimeoutMs())
  }
}
