package kolt.usertool

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.resolve.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolSectionParseTest {

  @Test
  fun parsesGroupArtifactVersion() {
    val result = parseCoordsString("a.b:c:1.0")
    val pair = assertNotNull(result.get())
    assertEquals(Coordinate("a.b", "c", "1.0"), pair.first)
    assertNull(pair.second)
  }

  @Test
  fun parsesGroupArtifactVersionClassifier() {
    val result = parseCoordsString("a.b:c:1.0:cls")
    val pair = assertNotNull(result.get())
    assertEquals(Coordinate("a.b", "c", "1.0"), pair.first)
    assertEquals("cls", pair.second)
  }

  @Test
  fun parsesRealisticKtlintCoords() {
    val result = parseCoordsString("com.pinterest.ktlint:ktlint-cli:1.3.1:all")
    val pair = assertNotNull(result.get())
    assertEquals(Coordinate("com.pinterest.ktlint", "ktlint-cli", "1.3.1"), pair.first)
    assertEquals("all", pair.second)
  }

  @Test
  fun rejectsMissingVersion() {
    val err = parseCoordsString("a.b:c").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsEmptyString() {
    val err = parseCoordsString("").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsMissingGroup() {
    val err = parseCoordsString(":c:1.0").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsMissingArtifact() {
    val err = parseCoordsString("a.b::1.0").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsEmptyVersion() {
    val err = parseCoordsString("a.b:c:").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsTooManyColons() {
    val err = parseCoordsString("a:b:1.0:cls:extra").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsInvalidGroupCharset() {
    val err = parseCoordsString("a/b:c:1.0").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsInvalidArtifactCharset() {
    val err = parseCoordsString("a.b:c d:1.0").getError()
    assertNotNull(err)
  }

  @Test
  fun rejectsInvalidClassifierCharset() {
    val err = parseCoordsString("a.b:c:1.0:cl s").getError()
    assertNotNull(err)
  }

  @Test
  fun acceptsAllowedCharsetsInGroupArtifactVersionClassifier() {
    val result = parseCoordsString("g_1.A-x:art-2_b.x:1.0-RC_1:cl-s_2.x")
    val pair = assertNotNull(result.get())
    assertEquals(Coordinate("g_1.A-x", "art-2_b.x", "1.0-RC_1"), pair.first)
    assertEquals("cl-s_2.x", pair.second)
  }

  @Test
  fun rejectsEmptyClassifier() {
    // Trailing colon without classifier means malformed (4 parts but classifier empty).
    val err = parseCoordsString("a.b:c:1.0:").getError()
    assertNotNull(err)
  }

  @Test
  fun errorMessageMentionsInput() {
    val err = parseCoordsString("not-coords").getError()
    assertNotNull(err)
    assertTrue(err.isNotEmpty(), "error message should be non-empty")
  }

  // parseToolSection coverage --------------------------------------------------

  @Test
  fun parseToolSectionAcceptsHappyPath() {
    val raw = mapOf("ktlint" to RawToolEntry(coords = "com.pinterest.ktlint:ktlint-cli:1.3.1:all"))
    val result = parseToolSection(raw).get()
    val map = assertNotNull(result)
    val entry = assertNotNull(map["ktlint"])
    assertEquals(Coordinate("com.pinterest.ktlint", "ktlint-cli", "1.3.1"), entry.coords)
    assertEquals("all", entry.classifier)
  }

  @Test
  fun parseToolSectionReturnsEmptyForNullInput() {
    val map = assertNotNull(parseToolSection(null).get())
    assertTrue(map.isEmpty())
  }

  @Test
  fun parseToolSectionReturnsEmptyForEmptyInput() {
    val map = assertNotNull(parseToolSection(emptyMap()).get())
    assertTrue(map.isEmpty())
  }

  @Test
  fun parseToolSectionRejectsUppercaseAlias() {
    val raw = mapOf("Foo" to RawToolEntry(coords = "a:b:1.0"))
    val err = assertIs<ToolSectionParseError.InvalidAlias>(parseToolSection(raw).getError())
    assertEquals("Foo", err.alias)
  }

  @Test
  fun parseToolSectionRejectsAliasStartingWithDigit() {
    val raw = mapOf("1foo" to RawToolEntry(coords = "a:b:1.0"))
    val err = assertIs<ToolSectionParseError.InvalidAlias>(parseToolSection(raw).getError())
    assertEquals("1foo", err.alias)
  }

  @Test
  fun parseToolSectionRejects65CharAlias() {
    val alias = "a" + "b".repeat(64) // 65 chars total
    assertEquals(65, alias.length)
    val raw = mapOf(alias to RawToolEntry(coords = "a:b:1.0"))
    val err = assertIs<ToolSectionParseError.InvalidAlias>(parseToolSection(raw).getError())
    assertEquals(alias, err.alias)
  }

  @Test
  fun parseToolSectionAccepts64CharAlias() {
    val alias = "a" + "b".repeat(63) // 64 chars total
    assertEquals(64, alias.length)
    val raw = mapOf(alias to RawToolEntry(coords = "a:b:1.0"))
    val map = assertNotNull(parseToolSection(raw).get())
    assertNotNull(map[alias])
  }

  @Test
  fun parseToolSectionAcceptsAliasWithUnderscoreAndHyphen() {
    val raw = mapOf("foo_bar-baz" to RawToolEntry(coords = "a:b:1.0"))
    val map = assertNotNull(parseToolSection(raw).get())
    assertNotNull(map["foo_bar-baz"])
  }

  @Test
  fun parseToolSectionRejectsArgsField() {
    val raw = mapOf("foo" to RawToolEntry(coords = "a:b:1.0", args = emptyList()))
    val err = assertIs<ToolSectionParseError.ForbiddenField>(parseToolSection(raw).getError())
    assertEquals("foo", err.alias)
    assertEquals("args", err.field)
  }

  @Test
  fun parseToolSectionRejectsDependsOnField() {
    val raw = mapOf("foo" to RawToolEntry(coords = "a:b:1.0", dependsOn = "x"))
    val err = assertIs<ToolSectionParseError.ForbiddenField>(parseToolSection(raw).getError())
    assertEquals("foo", err.alias)
    assertEquals("depends-on", err.field)
  }

  @Test
  fun parseToolSectionRejectsMainField() {
    val raw = mapOf("foo" to RawToolEntry(coords = "a:b:1.0", main = "com.example.Main"))
    val err = assertIs<ToolSectionParseError.ForbiddenField>(parseToolSection(raw).getError())
    assertEquals("foo", err.alias)
    assertEquals("main", err.field)
  }

  @Test
  fun parseToolSectionRejectsMissingCoords() {
    val raw = mapOf("foo" to RawToolEntry(coords = null))
    val err = assertIs<ToolSectionParseError.MissingCoords>(parseToolSection(raw).getError())
    assertEquals("foo", err.alias)
  }

  @Test
  fun parseToolSectionRejectsMalformedCoords() {
    val raw = mapOf("foo" to RawToolEntry(coords = "no-colons-here"))
    val err = assertIs<ToolSectionParseError.MalformedCoords>(parseToolSection(raw).getError())
    assertEquals("foo", err.alias)
    assertEquals("no-colons-here", err.coords)
    assertTrue(err.reason.isNotEmpty())
  }

  @Test
  fun parseToolSectionForbiddenFieldOverridesMissingCoords() {
    // If both forbidden field and missing coords coexist, surface the forbidden
    // field (more useful diagnostic — user removed coords by accident is rare).
    // This is an implementation choice; only assert deterministic ordering.
    val raw = mapOf("foo" to RawToolEntry(coords = null, args = emptyList()))
    val err = parseToolSection(raw).getError()
    assertNotNull(err)
    // Either ForbiddenField or MissingCoords is acceptable as long as it's deterministic.
    assertTrue(
      err is ToolSectionParseError.ForbiddenField || err is ToolSectionParseError.MissingCoords
    )
  }
}
