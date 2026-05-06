package kolt.build

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaleDaemonNoticeTest {

  @BeforeTest
  fun setUp() {
    StaleDaemonNotice.reset()
  }

  @AfterTest
  fun tearDown() {
    StaleDaemonNotice.reset()
  }

  private fun MutableList<String>.collector(): (String) -> Unit = { add(it) }

  @Test
  fun firstEmitReturnsTrueAndWritesOneLine() {
    val sink = mutableListOf<String>()
    val emitted = StaleDaemonNotice.emit("compiler daemon", "malformed reply", sink.collector())

    assertTrue(emitted, "first emit should return true")
    assertEquals(1, sink.size, "first emit should write exactly one line")
    val line = sink.single()
    assertTrue(
      line.contains("compiler daemon"),
      "expected message to contain label 'compiler daemon', got: $line",
    )
    assertTrue(
      line.contains("malformed reply"),
      "expected message to contain detail 'malformed reply', got: $line",
    )
    assertTrue(
      line.startsWith("warning: stale "),
      "expected message to start with 'warning: stale ', got: $line",
    )
    assertTrue(line.contains("recycling"), "expected message to mention recycling, got: $line")
  }

  @Test
  fun secondEmitReturnsFalseAndWritesNothing() {
    val sink = mutableListOf<String>()
    StaleDaemonNotice.emit("compiler daemon", "malformed reply", sink.collector())
    val sizeAfterFirst = sink.size

    val emittedAgain =
      StaleDaemonNotice.emit("native compiler daemon", "other detail", sink.collector())

    assertFalse(emittedAgain, "second emit should return false")
    assertEquals(sizeAfterFirst, sink.size, "second emit should not append to sink")
  }

  @Test
  fun resetRestoresFirstEmitBehaviour() {
    val sink = mutableListOf<String>()
    StaleDaemonNotice.emit("compiler daemon", "first detail", sink.collector())
    StaleDaemonNotice.emit("compiler daemon", "ignored detail", sink.collector())
    val sizeBeforeReset = sink.size

    StaleDaemonNotice.reset()
    val emittedAfterReset =
      StaleDaemonNotice.emit("native compiler daemon", "second detail", sink.collector())

    assertTrue(emittedAfterReset, "emit after reset should return true")
    assertEquals(sizeBeforeReset + 1, sink.size, "emit after reset should write one new line")
    val newLine = sink.last()
    assertTrue(
      newLine.contains("native compiler daemon"),
      "expected new line to contain new label, got: $newLine",
    )
    assertTrue(
      newLine.contains("second detail"),
      "expected new line to contain new detail, got: $newLine",
    )
  }
}
