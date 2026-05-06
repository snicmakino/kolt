package kolt.build

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallbackReporterTest {

  @BeforeTest
  fun setUp() {
    StaleDaemonNotice.reset()
  }

  @AfterTest
  fun tearDown() {
    StaleDaemonNotice.reset()
  }

  private fun capture(err: CompileError): List<String> {
    val out = mutableListOf<String>()
    reportFallback(err) { out.add(it) }
    return out
  }

  @Test
  fun backendUnavailableOtherBecomesWarningWithDetail() {
    val messages = capture(CompileError.BackendUnavailable.Other("connect refused"))
    assertEquals(1, messages.size)
    assertTrue(messages.single().startsWith("warning: "))
    assertTrue(messages.single().contains("connect refused"))
  }

  @Test
  fun wireMismatchRoutesThroughStaleDaemonNotice() {
    val messages = capture(CompileError.BackendUnavailable.WireMismatch("malformed reply"))
    assertEquals(1, messages.size, "WireMismatch should produce exactly one stderr line")
    val line = messages.single()
    assertTrue(line.contains("stale"), "expected stale-daemon wording, got: $line")
    assertTrue(line.contains("compiler daemon"), "expected label 'compiler daemon', got: $line")
    assertTrue(line.contains("malformed reply"), "expected detail 'malformed reply', got: $line")
    assertFalse(
      line.contains("warning: compiler daemon unavailable"),
      "WireMismatch should not fall through to the generic warning, got: $line",
    )
  }

  @Test
  fun backendUnavailableOtherStillEmitsLegacyGenericWarning() {
    val messages = capture(CompileError.BackendUnavailable.Other("foo"))
    assertEquals(1, messages.size)
    val line = messages.single()
    assertEquals(
      "warning: compiler daemon unavailable (foo), falling back to subprocess compile",
      line,
    )
  }

  @Test
  fun backendUnavailableForkFailedBecomesGenericWarning() {
    val messages = capture(CompileError.BackendUnavailable.ForkFailed)
    assertEquals(1, messages.size)
    assertTrue(messages.single().startsWith("warning: "))
  }

  @Test
  fun internalMisuseBecomesErrorLog() {
    val messages = capture(CompileError.InternalMisuse("sockaddr_un path too long"))
    assertEquals(1, messages.size)
    val line = messages.single()
    assertTrue(line.startsWith("error: "), "expected 'error: ' prefix, got: $line")
    assertTrue(line.contains("sockaddr_un path too long"))
  }

  @Test
  fun compilationFailedIsNotReported() {
    val messages =
      capture(
        CompileError.CompilationFailed(exitCode = 1, stdout = "", stderr = "user code broken")
      )
    assertEquals(emptyList(), messages)
  }

  @Test
  fun noCommandIsNotReported() {
    val messages = capture(CompileError.NoCommand)
    assertEquals(emptyList(), messages)
  }
}
