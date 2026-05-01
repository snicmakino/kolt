package kolt.resolve

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParseRemoveArgsTest {

  @Test
  fun acceptsTwoPartCoord() {
    val parsed = parseRemoveArgs(listOf("foo:bar")).getOrElse { error("unexpected: $it") }
    assertEquals("foo:bar", parsed)
  }

  @Test
  fun acceptsThreePartCoordAndDropsVersion() {
    val parsed = parseRemoveArgs(listOf("foo:bar:1.0")).getOrElse { error("unexpected: $it") }
    assertEquals("foo:bar", parsed)
  }

  @Test
  fun rejectsEmptyArgs() {
    assertIs<RemoveArgsError.MissingCoordinate>(parseRemoveArgs(emptyList()).getError())
  }

  @Test
  fun rejectsSinglePart() {
    val err = parseRemoveArgs(listOf("foo")).getError()
    assertEquals(RemoveArgsError.InvalidFormat("foo"), err)
  }

  @Test
  fun rejectsEmptyPart() {
    val err = parseRemoveArgs(listOf("foo::")).getError()
    assertEquals(RemoveArgsError.InvalidFormat("foo::"), err)
  }

  @Test
  fun rejectsFourPlusParts() {
    val err = parseRemoveArgs(listOf("a:b:c:d")).getError()
    assertEquals(RemoveArgsError.InvalidFormat("a:b:c:d"), err)
  }
}

class RemoveDependencyTest {

  @Test
  fun removesEntryFromMainSection() {
    val toml =
      """
        |[dependencies]
        |"foo:bar" = "1.0"
        |"baz:qux" = "2.0"
        |"""
        .trimMargin()

    val result = removeDependencyFromToml(toml, "foo:bar")

    assertEquals(listOf(RemovedEntry("1.0", isTest = false)), result.removed)
    assertFalse("foo:bar" in result.newToml, "removed key must not appear in output")
    assertTrue("baz:qux" in result.newToml, "sibling entry must be preserved")
  }

  @Test
  fun removesEntryFromTestSection() {
    val toml =
      """
        |[test-dependencies]
        |"io.kotest:kotest-runner" = "5.8.0"
        |"""
        .trimMargin()

    val result = removeDependencyFromToml(toml, "io.kotest:kotest-runner")

    assertEquals(listOf(RemovedEntry("5.8.0", isTest = true)), result.removed)
    assertFalse("kotest-runner" in result.newToml)
  }

  // The auto-search invariant: when the same coord lives in both
  // [dependencies] and [test-dependencies] (misconfig or intentional),
  // remove drops both rather than forcing the user to pick a section.
  @Test
  fun removesFromBothSectionsWhenPresentInBoth() {
    val toml =
      """
        |[dependencies]
        |"foo:bar" = "1.0"
        |
        |[test-dependencies]
        |"foo:bar" = "2.0"
        |"""
        .trimMargin()

    val result = removeDependencyFromToml(toml, "foo:bar")

    assertEquals(
      listOf(RemovedEntry("1.0", isTest = false), RemovedEntry("2.0", isTest = true)),
      result.removed,
    )
    assertFalse("foo:bar" in result.newToml)
  }

  @Test
  fun returnsEmptyRemovedListWhenCoordIsAbsent() {
    val toml =
      """
        |[dependencies]
        |"foo:bar" = "1.0"
        |"""
        .trimMargin()

    val result = removeDependencyFromToml(toml, "missing:lib")

    assertTrue(result.removed.isEmpty())
    assertEquals(toml, result.newToml)
  }

  // Boundary check: closing-quote in the search pattern prevents
  // `com.foo:bar` from matching `com.foo:bar-extras`.
  @Test
  fun doesNotMatchPrefixOfLongerCoord() {
    val toml =
      """
        |[dependencies]
        |"com.foo:bar" = "1.0"
        |"com.foo:bar-extras" = "2.0"
        |"""
        .trimMargin()

    val result = removeDependencyFromToml(toml, "com.foo:bar")

    assertEquals(1, result.removed.size)
    assertEquals("1.0", result.removed[0].version)
    assertTrue("com.foo:bar-extras" in result.newToml)
  }

  @Test
  fun handlesSingleQuotedKeys() {
    val toml =
      """
        |[dependencies]
        |'foo:bar' = '1.0'
        |"""
        .trimMargin()

    val result = removeDependencyFromToml(toml, "foo:bar")

    assertEquals(listOf(RemovedEntry("1.0", isTest = false)), result.removed)
    assertFalse("foo:bar" in result.newToml)
  }

  @Test
  fun missingDependenciesSectionReturnsEmpty() {
    val toml =
      """
        |name = "myapp"
        |[build]
        |target = "jvm"
        |"""
        .trimMargin()

    val result = removeDependencyFromToml(toml, "foo:bar")

    assertTrue(result.removed.isEmpty())
    assertEquals(toml, result.newToml)
  }

  // Line-level edit doesn't go through ktoml, so duplicate keys (which
  // ktoml would reject on parse) are removed exhaustively rather than
  // leaving a self-inconsistent post-state.
  @Test
  fun removesAllDuplicateOccurrencesInOneSection() {
    val toml =
      """
        |[dependencies]
        |"foo:bar" = "1.0"
        |"baz:qux" = "2.0"
        |"foo:bar" = "1.1"
        |"""
        .trimMargin()

    val result = removeDependencyFromToml(toml, "foo:bar")

    assertEquals(2, result.removed.size)
    assertEquals("1.0", result.removed[0].version)
    assertEquals("1.1", result.removed[1].version)
    assertFalse("foo:bar" in result.newToml)
    assertTrue("baz:qux" in result.newToml)
  }

  @Test
  fun emptySectionAtEndOfFileIsHandled() {
    val toml =
      """
        |name = "myapp"
        |
        |[dependencies]
        |"""
        .trimMargin()

    val result = removeDependencyFromToml(toml, "foo:bar")

    assertTrue(result.removed.isEmpty())
    assertEquals(toml, result.newToml)
  }

  @Test
  fun preservesUnrelatedSectionsAndTrailingNewline() {
    val toml =
      """
        |name = "myapp"
        |
        |[dependencies]
        |"foo:bar" = "1.0"
        |
        |[repositories]
        |central = "https://repo1.maven.org/maven2"
        |"""
        .trimMargin()

    val result = removeDependencyFromToml(toml, "foo:bar")

    assertEquals(1, result.removed.size)
    assertTrue("[repositories]" in result.newToml)
    assertTrue("central" in result.newToml)
    assertTrue(result.newToml.endsWith("\n"), "trailing newline must be preserved")
  }
}
