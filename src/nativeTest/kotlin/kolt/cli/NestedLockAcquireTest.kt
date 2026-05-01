package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.build.BUILD_DIR
import kolt.concurrency.ProjectLock
import kotlin.test.Test
import kotlin.test.fail

class NestedLockAcquireTest {

  // Regression for #303. When `kolt test` runs the kolt repo's own test
  // suite the parent process must release the project lock before
  // spawning the test child; otherwise every nested doRun / doBuild /
  // doTest from inside a test deadlocks against the parent's still-held
  // lock and exits at EXIT_LOCK_TIMEOUT. A 200ms timeout makes a
  // regression fail fast instead of the default 30s budget.
  @Test
  fun projectLockIsAvailableInsideKoltTestChild() {
    val handle =
      ProjectLock.acquire(BUILD_DIR, timeoutMs = 200L).getOrElse {
        fail(
          "lock acquire from `kolt test` child timed out — parent kolt-test " +
            "did not release the project lock before spawning the child (#303); err=$it"
        )
      }
    handle.close()
  }
}
