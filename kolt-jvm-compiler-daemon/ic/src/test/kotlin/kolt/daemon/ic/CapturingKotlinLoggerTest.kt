@file:OptIn(ExperimentalBuildToolsApi::class)

package kolt.daemon.ic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi

// Pins the shape of error message capture. The B-2b implementation kept
// only `throwable.message`, which threw away the stack frame a human
// needs to locate BTA-internal failures in a bug report. B-2c retains
// one frame of trace plus `throwable.toString()` so the class name is
// preserved even when `message` is null.
class CapturingKotlinLoggerTest {

  private class RecordingSink : IcMetricsSink {
    val events: MutableList<Pair<String, Long>> = mutableListOf()

    override fun record(name: String, value: Long) {
      events.add(name to value)
    }
  }

  @Test
  fun errorWithoutThrowablePreservesMessage() {
    val logger = CapturingKotlinLogger(RecordingSink())
    logger.error("kotlinc: type mismatch", null)
    assertEquals(listOf("kotlinc: type mismatch"), logger.errorMessages())
  }

  @Test
  fun errorWithThrowableIncludesClassNameAndFirstFrame() {
    val logger = CapturingKotlinLogger(RecordingSink())
    val boom = RuntimeException("boom")
    logger.error("bta failed", boom)

    val captured = logger.errorMessages().single()
    assertTrue(captured.startsWith("bta failed: "), "header: $captured")
    assertTrue("java.lang.RuntimeException" in captured, "throwable class name retained: $captured")
    assertTrue("boom" in captured, "throwable message retained: $captured")
    assertTrue("\n\tat " in captured, "first stack frame retained: $captured")
  }

  @Test
  fun errorWithThrowableWhoseMessageIsNullStillPreservesClassName() {
    val logger = CapturingKotlinLogger(RecordingSink())
    val boom = IllegalStateException(null as String?)
    logger.error("bta failed", boom)

    val captured = logger.errorMessages().single()
    assertTrue(
      "java.lang.IllegalStateException" in captured,
      "class name survives null message: $captured",
    )
  }

  @Test
  fun errorIncrementsMetricCounter() {
    val sink = RecordingSink()
    val logger = CapturingKotlinLogger(sink)
    logger.error("x", null)
    logger.error("y", null)
    assertEquals(2, sink.events.count { it.first == BtaIncrementalCompiler.METRIC_LOG_ERROR })
  }

  @Test
  fun warnInfoLifecycleAreNotRetainedInErrorList() {
    val logger = CapturingKotlinLogger(RecordingSink())
    logger.warn("w", null)
    logger.info("i")
    logger.lifecycle("l")
    assertTrue(logger.errorMessages().isEmpty())
  }
}
