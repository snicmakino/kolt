package kolt.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ConfigErrorTest {
  @Test
  fun messageOnlyConstructionLeavesEnrichmentFieldsNull() {
    // Pin: legacy call sites (`ParseFailed("foo")`) still compile and the
    // optional fields default to null.
    val err = ConfigError.ParseFailed("project_dir must not be empty")
    assertEquals("project_dir must not be empty", err.message)
    assertNull(err.path)
    assertNull(err.lineNo)
    assertNull(err.keyPath)
    assertNull(err.suggestion)
  }

  @Test
  fun fullEnrichmentRoundTrip() {
    val err =
      ConfigError.ParseFailed(
        message = "unknown key 'koltn'",
        path = "/tmp/kolt.toml",
        lineNo = 7,
        keyPath = "koltn",
        suggestion = "kotlin",
      )
    assertEquals("/tmp/kolt.toml", err.path)
    assertEquals(7, err.lineNo)
    assertEquals("koltn", err.keyPath)
    assertEquals("kotlin", err.suggestion)
  }

  @Test
  fun equalityIsValueBased() {
    val a = ConfigError.ParseFailed("m", path = "/p", lineNo = 1)
    val b = ConfigError.ParseFailed("m", path = "/p", lineNo = 1)
    val c = ConfigError.ParseFailed("m", path = "/p", lineNo = 2)
    assertEquals(a, b)
    assertNotEquals(a, c)
  }

  @Test
  fun copyOnlyOverridesNamedFields() {
    val original = ConfigError.ParseFailed(message = "m", path = "/p", lineNo = 1, keyPath = "k")
    val updated = original.copy(suggestion = "kotlin")
    assertEquals("m", updated.message)
    assertEquals("/p", updated.path)
    assertEquals(1, updated.lineNo)
    assertEquals("k", updated.keyPath)
    assertEquals("kotlin", updated.suggestion)
  }
}
