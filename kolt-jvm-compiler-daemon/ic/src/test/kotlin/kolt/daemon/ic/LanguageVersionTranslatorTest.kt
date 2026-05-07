package kolt.daemon.ic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageVersionTranslatorTest {

  @Test
  fun `both null yields no args`() {
    assertTrue(LanguageVersionTranslator.translate(version = null, compiler = null).isEmpty())
  }

  @Test
  fun `null version yields no args`() {
    assertTrue(LanguageVersionTranslator.translate(version = null, compiler = "2.3.20").isEmpty())
  }

  @Test
  fun `null compiler yields no args`() {
    assertTrue(LanguageVersionTranslator.translate(version = "2.1.0", compiler = null).isEmpty())
  }

  @Test
  fun `compiler equal to version yields no args`() {
    assertTrue(
      LanguageVersionTranslator.translate(version = "2.3.20", compiler = "2.3.20").isEmpty()
    )
  }

  @Test
  fun `differing version and compiler emit language and api flags pinned to version surface`() {
    assertEquals(
      listOf("-language-version", "2.1", "-api-version", "2.1"),
      LanguageVersionTranslator.translate(version = "2.1.0", compiler = "2.3.20"),
    )
  }
}
