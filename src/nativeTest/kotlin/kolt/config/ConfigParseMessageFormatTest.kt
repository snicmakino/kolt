package kolt.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.exceptions.TomlDecodingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlinx.serialization.Serializable

// Pins the ktoml-core 0.7.1 exception-message format that
// `KtomlMessageParse.LINE_NO_REGEX` and the unknown-key regex rely on.
// A ktoml major bump that reshapes either prefix surfaces here as a RED
// test — that is the cue to revisit the regex / scope marker
// (`KTOML_ROOT_SCOPE`) before line-number / Did-you-mean rendering
// silently breaks in production.
class ConfigParseMessageFormatTest {

  @Serializable private data class TinyConfig(val name: String = "", val version: String = "")

  private val strictToml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))

  private fun decodeExpectingFailure(raw: String): String {
    try {
      strictToml.decodeFromString(TinyConfig.serializer(), raw)
      fail("expected TomlDecodingException for input: $raw")
    } catch (e: TomlDecodingException) {
      return assertNotNull(e.message, "ktoml exception had null message")
    }
  }

  @Test
  fun syntaxErrorMessageStartsWithLinePrefix() {
    // Unterminated string — ktoml flags the line.
    val message = decodeExpectingFailure("name = \"unterminated\nversion = \"0.1\"\n")
    val matched = LINE_NO_REGEX.find(message)
    assertNotNull(matched, "ktoml syntax error did not match `^Line N: ` prefix; actual: $message")
  }

  @Test
  fun unknownTopLevelKeyMessageMatchesUnknownKeyRegex() {
    val raw = "name = \"x\"\nversion = \"0.1\"\nkoltn = \"stray\"\n"
    val message = decodeExpectingFailure(raw)
    val regex = Regex("Unknown key received: <([^>]+)> in scope <([^>]*)>")
    val match = regex.find(message)
    assertNotNull(
      match,
      "ktoml unknown-top-level message did not match expected format; actual: $message",
    )
    val (key, scope) = match.destructured
    assertEquals("koltn", key, "full ktoml message: $message")
    assertEquals("rootNode", scope, "expected TomlFile.name; full ktoml message: $message")
  }

  @Test
  fun unknownNestedKeyMessageMatchesAndCarriesParentScope() {
    @Serializable data class Inner(val a: String = "")
    @Serializable data class Outer(val name: String = "", val nested: Inner = Inner())

    val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = false))
    val raw = "name = \"x\"\n[nested]\na = \"ok\"\nstray = \"unknown\"\n"
    val message =
      try {
        toml.decodeFromString(Outer.serializer(), raw)
        fail("expected TomlDecodingException")
      } catch (e: TomlDecodingException) {
        assertNotNull(e.message, "ktoml exception had null message")
      }
    val regex = Regex("Unknown key received: <([^>]+)> in scope <([^>]*)>")
    val match = regex.find(message)
    assertNotNull(
      match,
      "ktoml unknown-nested-key message did not match expected format; actual: $message",
    )
    val (key, scope) = match.destructured
    assertEquals("stray", key, "full ktoml message: $message")
    assertEquals("nested", scope, "full ktoml message: $message")
  }

  // Pins the ktoml 0.7.1 decode shape when a legacy flat-form `[repositories]`
  // body (`name = "url"`) is decoded into the production wire schema
  // (`Map<String, RawRepository>`). ktoml surfaces this as `UnknownNameException`
  // with key=`<central>` in scope `<rootNode>` — byte-identical to a real
  // root-scope typo. Substitution keyed on the exception alone would corrupt
  // legitimate typo handling, so the schema-migration error must be a hint
  // appended to the raw ktoml message, not a replacement of it. A ktoml major
  // bump that reshapes the message surfaces here as a RED test.
  @Serializable
  private data class RepositoryProbe(val repositories: Map<String, RawRepository> = emptyMap())

  @Test
  fun legacyFlatRepositoriesShapeSurfacesAsUnknownNameAtRootScope() {
    val raw = "[repositories]\ncentral = \"https://repo1.maven.org/maven2\"\n"
    val message = decodeRepositoryProbeExpectingFailure(raw)
    // The exception class is `UnknownNameException` (a `TomlDecodingException`
    // subclass), so the existing `decodeExpectingFailure`-style catch in
    // production code already traps it.
    val regex = Regex("Unknown key received: <([^>]+)> in scope <([^>]*)>")
    val match = regex.find(message)
    assertNotNull(
      match,
      "ktoml flat-form repositories message did not match the unknown-key regex; actual: $message",
    )
    val (key, scope) = match.destructured
    // The offending repository name `central` appears as the key, scope is
    // `rootNode` — indistinguishable from a real top-level typo. This is
    // why the migration path must be hint-append, not substitution.
    assertEquals("central", key, "full ktoml message: $message")
    assertEquals("rootNode", scope, "full ktoml message: $message")
    // No `Line N: ` prefix: `UnknownNameException` does not carry line info
    // (verified against ktoml v0.7.1 source: `UnknownNameException(key, parent)`
    // has no `lineNo` parameter). Pin the absence so a future ktoml bump that
    // starts attaching line info surfaces as a RED test too.
    assertEquals(
      null,
      LINE_NO_REGEX.find(message),
      "ktoml flat-form repositories error unexpectedly gained a `Line N: ` prefix; actual: $message",
    )
    // Pin the trailing hint paragraph so the full message shape is locked in;
    // the `KtomlMessageParse` `parseUnknownKey` regex deliberately ignores
    // everything after the `> ` so this trailing text does not affect
    // downstream parsing — but a future ktoml rewording would still RED here.
    val expectedSuffix =
      ". Switch the configuration option: 'TomlConfig.ignoreUnknownNames'" +
        " to true if you would like to skip unknown keys"
    assertEquals(
      true,
      message.endsWith(expectedSuffix),
      "expected ktoml message to end with the documented suffix; actual: $message",
    )
  }

  private fun decodeRepositoryProbeExpectingFailure(raw: String): String {
    try {
      strictToml.decodeFromString(RepositoryProbe.serializer(), raw)
      fail("expected TomlDecodingException for input: $raw")
    } catch (e: TomlDecodingException) {
      return assertNotNull(e.message, "ktoml exception had null message")
    }
  }

  // Overlay-shaped probe: pins ktoml's nested-scope unknown-key message format
  // for an input shaped like `kolt.local.toml` (an unknown sub-key under a
  // known section like `[run]`). The shape is already pinned by
  // `unknownNestedKeyMessageMatchesAndCarriesParentScope`, but task 3.2's
  // overlay decoder will route the same ktoml error through
  // `buildKtomlParseError` with `sourceFile = "kolt.local.toml"`; this case
  // records the message shape with a probe that matches the overlay schema
  // surface so a ktoml bump that reshapes the nested-scope variant surfaces
  // RED in both root-scope and overlay-scope paths.
  @Serializable private data class OverlayProbeRun(val sys_props: Map<String, String> = emptyMap())

  @Serializable
  private data class OverlayProbe(
    val run: OverlayProbeRun = OverlayProbeRun(),
    val name: String = "",
  )

  @Test
  fun unknownNestedKeyMessageOnOverlayShapedProbeCarriesParentScope() {
    val raw = "[run]\nstray = \"unknown\"\n"
    val message =
      try {
        strictToml.decodeFromString(OverlayProbe.serializer(), raw)
        fail("expected TomlDecodingException for input: $raw")
      } catch (e: TomlDecodingException) {
        assertNotNull(e.message, "ktoml exception had null message")
      }
    val regex = Regex("Unknown key received: <([^>]+)> in scope <([^>]*)>")
    val match = regex.find(message)
    assertNotNull(
      match,
      "ktoml unknown-nested-key (overlay-shaped) message did not match expected format; " +
        "actual: $message",
    )
    val (key, scope) = match.destructured
    assertEquals("stray", key, "full ktoml message: $message")
    assertEquals("run", scope, "full ktoml message: $message")
  }

  @Test
  fun buildKtomlParseErrorHeadlineDefaultsToKoltToml() {
    val err =
      buildKtomlParseError(
        rawMessage = "Line 3: Unknown key received: <foo> in scope <rootNode>",
        path = "kolt.toml",
        tomlString = "name = \"x\"\nversion = \"0.1\"\nfoo = \"stray\"\n",
      )
    assertEquals(
      true,
      err.message.startsWith("failed to parse kolt.toml: "),
      "expected default headline to be `failed to parse kolt.toml: ...`; actual: ${err.message}",
    )
  }

  @Test
  fun buildKtomlParseErrorHeadlineRespectsSourceFileOverride() {
    val err =
      buildKtomlParseError(
        rawMessage = "Line 1: Unknown key received: <foo> in scope <run>",
        path = "kolt.local.toml",
        tomlString = "[run]\nfoo = \"x\"\n",
        sourceFile = "kolt.local.toml",
      )
    assertEquals(
      true,
      err.message.startsWith("failed to parse kolt.local.toml: "),
      "expected overlay headline to be `failed to parse kolt.local.toml: ...`; " +
        "actual: ${err.message}",
    )
  }

  @Test
  fun buildKtomlParseErrorSkipsRepositoriesMigrationHintForOverlay() {
    // The overlay's `[repositories.<name>]` schema is sub-table only by design,
    // so the flat-form migration hint must not fire when sourceFile is the
    // overlay file. Conservative scoping protects the overlay diagnostic from
    // a misleading hint that points at a kolt.toml-only schema move.
    val flatRepositoriesInput = "[repositories]\ncentral = \"https://repo1.maven.org/maven2\"\n"
    val err =
      buildKtomlParseError(
        rawMessage = "Unknown key received: <central> in scope <rootNode>",
        path = "kolt.local.toml",
        tomlString = flatRepositoriesInput,
        sourceFile = "kolt.local.toml",
      )
    assertEquals(
      false,
      err.message.contains("repositories schema migrated to sub-table form"),
      "overlay headline must not include the kolt.toml migration hint; actual: ${err.message}",
    )
  }
}
