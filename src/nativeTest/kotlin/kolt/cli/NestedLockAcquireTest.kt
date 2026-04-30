@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.build.BUILD_DIR
import kolt.concurrency.ProjectLock
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.cinterop.toKString
import platform.posix.getenv

class NestedLockAcquireTest {

  // Regression for #303. When `kolt test` runs the kolt repo's own test
  // suite the parent process must release the project lock before
  // spawning the test child; otherwise every nested doRun / doBuild /
  // doTest from inside a test deadlocks against the parent's still-held
  // lock and exits at EXIT_LOCK_TIMEOUT. A 200ms timeout makes a
  // regression fail fast instead of the default 30s budget.
  //
  // Gated behind `KOLT_NESTED_LOCK_TEST=1`. CI bootstrap kolt
  // (`KOLT_BOOTSTRAP_TAG=v0.16.3`) holds the lock through child
  // lifetime — the very bug this test asserts is fixed — so the test
  // would unavoidably fail under the old bootstrap. After
  // `KOLT_BOOTSTRAP_TAG` advances past a release that includes #303,
  // set the env in `unit-tests.yml` and remove this gate in a
  // follow-up.
  @Test
  fun projectLockIsAvailableInsideKoltTestChild() {
    val gate = getenv("KOLT_NESTED_LOCK_TEST")?.toKString()
    if (gate != "1") return

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
