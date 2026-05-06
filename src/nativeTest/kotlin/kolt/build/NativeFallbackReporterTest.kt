package kolt.build

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeFallbackReporterTest {

  @BeforeTest
  fun setUp() {
    StaleDaemonNotice.reset()
  }

  @AfterTest
  fun tearDown() {
    StaleDaemonNotice.reset()
  }

  private fun capture(err: NativeCompileError): List<String> {
    val messages = mutableListOf<String>()
    reportNativeFallback(err) { messages += it }
    return messages
  }

  @Test
  fun backendUnavailableVariantsEmitGenericWarning() {
    for (err in
      listOf(
        NativeCompileError.BackendUnavailable.ForkFailed,
        NativeCompileError.BackendUnavailable.WaitFailed,
        NativeCompileError.BackendUnavailable.SignalKilled,
        NativeCompileError.BackendUnavailable.PopenFailed,
      )) {
      val out = capture(err).single()
      assertTrue(
        out.startsWith("warning:") && out.contains("native compiler daemon unavailable"),
        "unexpected message for $err: $out",
      )
    }
  }

  @Test
  fun backendUnavailableOtherIncludesDetail() {
    val out = capture(NativeCompileError.BackendUnavailable.Other("connect refused")).single()
    assertTrue(out.contains("connect refused"), "expected detail in warning: $out")
  }

  @Test
  fun wireMismatchRoutesThroughStaleDaemonNotice() {
    val messages = capture(NativeCompileError.BackendUnavailable.WireMismatch("malformed reply"))
    assertEquals(1, messages.size, "WireMismatch should produce exactly one stderr line")
    val line = messages.single()
    assertTrue(line.contains("stale"), "expected stale-daemon wording, got: $line")
    assertTrue(
      line.contains("native compiler daemon"),
      "expected label 'native compiler daemon', got: $line",
    )
    assertTrue(line.contains("malformed reply"), "expected detail 'malformed reply', got: $line")
    assertFalse(
      line.contains("warning: native compiler daemon unavailable"),
      "WireMismatch should not fall through to the generic warning, got: $line",
    )
  }

  @Test
  fun backendUnavailableOtherStillEmitsLegacyGenericWarning() {
    val messages = capture(NativeCompileError.BackendUnavailable.Other("foo"))
    assertEquals(1, messages.size)
    val line = messages.single()
    assertEquals(
      "warning: native compiler daemon unavailable (foo), falling back to subprocess compile",
      line,
    )
  }

  @Test
  fun internalMisuseEscalatesToError() {
    val out = capture(NativeCompileError.InternalMisuse("socket path too long")).single()
    assertTrue(out.startsWith("error:"), "expected error-level, got: $out")
    assertTrue(out.contains("socket path too long"))
  }

  @Test
  fun compilationFailedAndNoCommandAreSilent() {
    // Real compile errors pass through without a fallback notice — the
    // caller has already seen the konanc diagnostics. NoCommand is
    // non-eligible and also silent.
    assertEquals(
      emptyList(),
      capture(NativeCompileError.CompilationFailed(exitCode = 1, stderr = "error: ...")),
    )
    assertEquals(emptyList(), capture(NativeCompileError.NoCommand))
  }
}
