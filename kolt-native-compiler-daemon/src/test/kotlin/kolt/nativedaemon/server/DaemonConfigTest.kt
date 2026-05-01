package kolt.nativedaemon.server

import kotlin.test.Test
import kotlin.test.assertEquals

// ADR 0024 §3 pins the native daemon's lifecycle defaults. These are
// deliberately shorter/smaller than the JVM daemon's defaults (30min /
// 1000 compiles / 1.5GB) because native builds are less frequent and
// stage 2 linking uses more heap per invocation.
class DaemonConfigTest {

  @Test
  fun `defaults match ADR 0024 section 3`() {
    val config = DaemonConfig()

    assertEquals(
      10 * 60 * 1000L,
      config.idleTimeoutMillis,
      "idle timeout: 10 minutes per ADR 0024 §3",
    )
    assertEquals(500, config.maxCompiles, "max compiles: 500 per ADR 0024 §3")
    assertEquals(
      2L * 1024 * 1024 * 1024,
      config.heapWatermarkBytes,
      "heap watermark: 2 GB per ADR 0024 §3",
    )
  }

  @Test
  fun `companion constants expose the defaults`() {
    assertEquals(10 * 60 * 1000L, DaemonConfig.DEFAULT_IDLE_TIMEOUT_MILLIS)
    assertEquals(500, DaemonConfig.DEFAULT_MAX_COMPILES)
    assertEquals(2L * 1024 * 1024 * 1024, DaemonConfig.DEFAULT_HEAP_WATERMARK_BYTES)
  }

  @Test
  fun `values can be overridden for tests`() {
    val config =
      DaemonConfig(
        idleTimeoutMillis = 1_000,
        maxCompiles = 3,
        heapWatermarkBytes = 128L * 1024 * 1024,
      )

    assertEquals(1_000L, config.idleTimeoutMillis)
    assertEquals(3, config.maxCompiles)
    assertEquals(128L * 1024 * 1024, config.heapWatermarkBytes)
  }
}
