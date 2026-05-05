@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import kolt.infra.eprintln
import kolt.infra.fileExists
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.cinterop.toKString
import platform.posix.getenv

const val FIXTURE_KOTLIN_VERSION = "2.3.20"

class MultiShapeDaemonTestCoverageIT {

  @Test
  fun gateAllowsTestToProceedOrSkipsCleanly() {
    if (!ensureGateOrSkip()) return
  }
}

private const val GATE_ENV = "KOLT_DAEMON_JAR"

// Mirrors JvmTestSysPropIT's `printOnceSkipNotice` pattern: keep a file-local
// flag so the skip notice prints exactly once per test JVM lifetime, even when
// multiple `@Test` methods in this class land on an unset gate.
private var skipNoticePrinted = false

private fun ensureGateOrSkip(): Boolean {
  val raw = getenv(GATE_ENV)?.toKString()
  if (raw.isNullOrEmpty()) {
    if (!skipNoticePrinted) {
      skipNoticePrinted = true
      eprintln(
        "MultiShapeDaemonTestCoverageIT: skipped (set $GATE_ENV to a daemon thin jar to enable)"
      )
    }
    return false
  }
  if (!fileExists(raw)) {
    fail("$GATE_ENV points to non-existent path: $raw")
  }
  return true
}
