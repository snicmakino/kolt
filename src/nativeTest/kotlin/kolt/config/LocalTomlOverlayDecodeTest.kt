package kolt.config

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalTomlOverlayDecodeTest {

  @Test
  fun rejectTomlSyntaxErrorAttributedToLocalToml() {
    // Unterminated string at the top level — the `Line N: ` prefix shape is
    // pinned by ConfigParseMessageFormatTest.syntaxErrorMessageStartsWithLinePrefix.
    val raw = "name = \"unterminated\nversion = \"0.1\"\n"
    val err = parseLocalOverlay(raw, path = KOLT_LOCAL_TOML).getError()
    val parseFailed = assertNotNull(err) as ConfigError.ParseFailed
    val rendered = renderConfigErrorAsLine(parseFailed)
    assertTrue(
      KOLT_LOCAL_TOML in rendered,
      "expected source file `$KOLT_LOCAL_TOML` named in error message; actual: $rendered",
    )
    assertNotNull(
      parseFailed.lineNo,
      "expected ktoml syntax error to carry a line number; actual: ${parseFailed.message}",
    )
  }

  @Test
  fun rejectRootScopeUnknownBuildSection() {
    val raw =
      """
        [build]
        target = "jvm"
      """
        .trimIndent()
    val err = parseLocalOverlay(raw, path = KOLT_LOCAL_TOML).getError()
    val parseFailed = assertNotNull(err) as ConfigError.ParseFailed
    val rendered = renderConfigErrorAsLine(parseFailed)
    assertTrue(
      "build" in rendered,
      "expected offending section `build` named in error message; actual: $rendered",
    )
    assertTrue(
      KOLT_LOCAL_TOML in rendered,
      "expected source file `$KOLT_LOCAL_TOML` named in error message; actual: $rendered",
    )
  }

  @Test
  fun rejectBareTopLevelScalar() {
    val raw =
      """
        name = "x"
      """
        .trimIndent()
    val err = parseLocalOverlay(raw, path = KOLT_LOCAL_TOML).getError()
    val parseFailed = assertNotNull(err) as ConfigError.ParseFailed
    val rendered = renderConfigErrorAsLine(parseFailed)
    assertTrue(
      "name" in rendered,
      "expected offending key `name` named in error message; actual: $rendered",
    )
    assertTrue(
      KOLT_LOCAL_TOML in rendered,
      "expected source file `$KOLT_LOCAL_TOML` named in error message; actual: $rendered",
    )
  }

  @Test
  fun rejectNestedScopeUnknownSubKeyUnderRun() {
    val raw =
      """
        [run.foo]
        x = "y"
      """
        .trimIndent()
    val err = parseLocalOverlay(raw, path = KOLT_LOCAL_TOML).getError()
    val parseFailed = assertNotNull(err) as ConfigError.ParseFailed
    val rendered = renderConfigErrorAsLine(parseFailed)
    assertTrue(
      "foo" in rendered,
      "expected offending sub-key `foo` named in error message; actual: $rendered",
    )
    assertTrue(
      "run" in rendered,
      "expected parent scope `run` named in error message; actual: $rendered",
    )
    assertTrue(
      KOLT_LOCAL_TOML in rendered,
      "expected source file `$KOLT_LOCAL_TOML` named in error message; actual: $rendered",
    )
  }

  @Test
  fun acceptMinimalValidOverlayWithTestSysProps() {
    val raw =
      """
        [test.sys_props]
        "x" = { literal = "y" }
      """
        .trimIndent()
    val overlay = assertNotNull(parseLocalOverlay(raw, path = KOLT_LOCAL_TOML).get())
    val test = assertNotNull(overlay.test, "expected [test] section to decode")
    assertEquals(1, test.sysProps.size)
    // ktoml's raw Map<String, V> decode preserves quotes around bare-quoted keys;
    // `Config.liftSysPropsMap` strips them downstream via `removeSurrounding("\"")`.
    val rawKey = test.sysProps.keys.single()
    assertEquals("y", test.sysProps[rawKey]?.literal)
  }
}
