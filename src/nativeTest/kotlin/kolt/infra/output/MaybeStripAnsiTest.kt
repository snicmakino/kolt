package kolt.infra.output

import kotlin.test.Test
import kotlin.test.assertEquals

private const val ESC = ""

class MaybeStripAnsiTest {
  @Test
  fun stripsAnsiWhenPolicyIsNever() {
    assertEquals("error: foo\n", maybeStripAnsi("error: $ESC[31mfoo$ESC[0m\n", ColorPolicy.Never))
  }

  @Test
  fun preservesAnsiWhenPolicyIsAlways() {
    val body = "error: $ESC[31mfoo$ESC[0m\n"
    assertEquals(body, maybeStripAnsi(body, ColorPolicy.Always))
  }

  @Test
  fun stripsMultipleSequencesWhenPolicyIsNever() {
    assertEquals(
      "head body tail",
      maybeStripAnsi(
        "$ESC[31mhead$ESC[0m $ESC[33mbody$ESC[0m $ESC[36mtail$ESC[0m",
        ColorPolicy.Never,
      ),
    )
  }

  @Test
  fun leavesPlainTextUnchangedUnderEitherPolicy() {
    val plain = "no escape here"
    assertEquals(plain, maybeStripAnsi(plain, ColorPolicy.Never))
    assertEquals(plain, maybeStripAnsi(plain, ColorPolicy.Always))
  }

  @Test
  fun autoPolicyStripsWhenStderrIsNotTty() {
    val body = "error: $ESC[31mfoo$ESC[0m\n"
    val policy = ColorPolicy.Auto(isStderrTty = false, isStdoutTty = false)
    assertEquals("error: foo\n", maybeStripAnsi(body, policy))
  }

  @Test
  fun autoPolicyPreservesWhenStderrIsTty() {
    val body = "error: $ESC[31mfoo$ESC[0m\n"
    val policy = ColorPolicy.Auto(isStderrTty = true, isStdoutTty = false)
    assertEquals(body, maybeStripAnsi(body, policy))
  }
}
