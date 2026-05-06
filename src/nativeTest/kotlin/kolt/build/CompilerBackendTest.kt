package kolt.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompilerBackendWireMismatchTest {

  @Test
  fun wireMismatchIsBackendUnavailable() {
    val variant: CompileError = CompileError.BackendUnavailable.WireMismatch("foo")
    assertIs<CompileError.BackendUnavailable>(variant)
  }

  @Test
  fun wireMismatchIsFallbackEligible() {
    assertTrue(isFallbackEligible(CompileError.BackendUnavailable.WireMismatch("foo")))
  }

  @Test
  fun formatCompileErrorRendersWireMismatchDetail() {
    val message = formatCompileError(CompileError.BackendUnavailable.WireMismatch("foo"), "compile")
    assertTrue(message.contains("wire mismatch"), "expected 'wire mismatch' in: $message")
    assertTrue(message.contains("foo"), "expected detail 'foo' in: $message")
  }

  @Test
  fun formatCompileErrorWireMismatchMatchesTemplate() {
    val message =
      formatCompileError(
        CompileError.BackendUnavailable.WireMismatch("malformed reply"),
        "compilation",
      )
    assertEquals("error: compilation wire mismatch: malformed reply", message)
  }
}
