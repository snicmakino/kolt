package kolt.usertool

import kolt.cli.EXIT_CONFIG_ERROR
import kolt.cli.EXIT_DEPENDENCY_ERROR
import kolt.cli.EXIT_TOOL_ERROR
import kolt.resolve.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolErrorTest {

  // Parse variants — every ToolSectionParseError shape lifts through ToolError.Parse
  // and surfaces with EXIT_CONFIG_ERROR (R5.4 cause-distinguishable surface — the prefix
  // identifies the cause, the exit code identifies the kolt-level category).

  @Test
  fun parseInvalidAliasMapsToConfigExitCodeAndPrefix() {
    val err =
      ToolError.Parse(ToolSectionParseError.InvalidAlias(alias = "Foo", reason = "uppercase"))
    assertEquals(EXIT_CONFIG_ERROR, err.toExitCode())
    assertTrue(
      err.render().headline.startsWith("tool 'Foo': parse error:"),
      "got: ${err.render().headline}",
    )
  }

  @Test
  fun parseForbiddenFieldMapsToConfigExitCodeAndPrefix() {
    val err =
      ToolError.Parse(ToolSectionParseError.ForbiddenField(alias = "ktlint", field = "depends-on"))
    assertEquals(EXIT_CONFIG_ERROR, err.toExitCode())
    assertTrue(err.render().headline.startsWith("tool 'ktlint': parse error:"))
    assertTrue(err.render().headline.contains("depends-on"))
  }

  @Test
  fun parseMalformedCoordsMapsToConfigExitCodeAndPrefix() {
    val err =
      ToolError.Parse(
        ToolSectionParseError.MalformedCoords(alias = "ktlint", coords = "bogus", reason = "shape")
      )
    assertEquals(EXIT_CONFIG_ERROR, err.toExitCode())
    assertTrue(err.render().headline.startsWith("tool 'ktlint': parse error:"))
  }

  @Test
  fun parseMissingCoordsMapsToConfigExitCodeAndPrefix() {
    val err = ToolError.Parse(ToolSectionParseError.MissingCoords(alias = "ktlint"))
    assertEquals(EXIT_CONFIG_ERROR, err.toExitCode())
    assertTrue(err.render().headline.startsWith("tool 'ktlint': parse error:"))
  }

  @Test
  fun parseDuplicateAliasMapsToConfigExitCodeAndPrefix() {
    val err = ToolError.Parse(ToolSectionParseError.DuplicateAlias(alias = "ktlint"))
    assertEquals(EXIT_CONFIG_ERROR, err.toExitCode())
    assertTrue(err.render().headline.startsWith("tool 'ktlint': parse error:"))
  }

  // Resolve variants — surface as EXIT_DEPENDENCY_ERROR (network/integrity/lock semantics).

  @Test
  fun resolveResolveFailedMapsToDependencyExitCodeAndPrefix() {
    val coords = Coordinate("g", "a", "1.0")
    val err =
      ToolError.Resolve(
        ToolResolutionError.ResolveFailed(
          alias = "ktlint",
          coords = coords,
          attempts = listOf("https://repo1/", "https://repo2/"),
        )
      )
    assertEquals(EXIT_DEPENDENCY_ERROR, err.toExitCode())
    assertTrue(err.render().headline.startsWith("tool 'ktlint': resolve failed:"))
  }

  @Test
  fun resolveIntegrityMismatchMapsToDependencyExitCodeAndPrefix() {
    val err =
      ToolError.Resolve(
        ToolResolutionError.IntegrityMismatch(
          alias = "ktlint",
          coords = Coordinate("g", "a", "1.0"),
          expected = "abc",
          actual = "def",
        )
      )
    assertEquals(EXIT_DEPENDENCY_ERROR, err.toExitCode())
    assertTrue(err.render().headline.startsWith("tool 'ktlint': integrity mismatch:"))
    assertTrue(err.render().headline.contains("abc"))
    assertTrue(err.render().headline.contains("def"))
  }

  // LockfileMismatch's stderr message is design-mandated — the headline pins
  // the differs-from text and the hint slot carries the recovery command so
  // the writer can render them as `error: ...` then `note: ...`.
  @Test
  fun resolveLockfileMismatchUsesDesignMandatedMessage() {
    val err =
      ToolError.Resolve(
        ToolResolutionError.LockfileMismatch(
          alias = "ktlint",
          tomlCoords = Coordinate("g", "a", "1.1"),
          tomlClassifier = null,
          lockedCoords = Coordinate("g", "a", "1.0"),
          lockedClassifier = null,
        )
      )
    assertEquals(EXIT_DEPENDENCY_ERROR, err.toExitCode())
    val diag = err.render()
    assertEquals(
      "tool 'ktlint': lockfile pin 'g:a:1.0' differs from kolt.toml 'g:a:1.1'",
      diag.headline,
    )
    assertEquals("Run `kolt update` to refresh tool pins.", diag.hint)
  }

  @Test
  fun resolveLockfileMismatchWithClassifierIncludesClassifierInCoords() {
    val err =
      ToolError.Resolve(
        ToolResolutionError.LockfileMismatch(
          alias = "ktlint",
          tomlCoords = Coordinate("g", "a", "1.1"),
          tomlClassifier = "all",
          lockedCoords = Coordinate("g", "a", "1.0"),
          lockedClassifier = "all",
        )
      )
    val diag = err.render()
    assertEquals(
      "tool 'ktlint': lockfile pin 'g:a:1.0:all' differs from kolt.toml 'g:a:1.1:all'",
      diag.headline,
    )
    assertEquals("Run `kolt update` to refresh tool pins.", diag.hint)
  }

  // Launch variants — the only three that route through EXIT_TOOL_ERROR=7.

  @Test
  fun launchNotRunnableJarMapsToToolExitCodeAndPrefix() {
    val err =
      ToolError.Launch(
        ToolLaunchError.NotRunnableJar(
          alias = "ktlint",
          jarPath = "/x/y.jar",
          reason = "zip magic mismatch",
        )
      )
    assertEquals(EXIT_TOOL_ERROR, err.toExitCode())
    assertTrue(err.render().headline.startsWith("tool 'ktlint': not a runnable jar"))
  }

  @Test
  fun launchMainClassMissingMapsToToolExitCodeAndPrefix() {
    val err =
      ToolError.Launch(ToolLaunchError.MainClassMissing(alias = "ktlint", jarPath = "/x/y.jar"))
    assertEquals(EXIT_TOOL_ERROR, err.toExitCode())
    assertTrue(err.render().headline.startsWith("tool 'ktlint': Main-Class missing"))
  }

  @Test
  fun launchJdkUnavailableMapsToToolExitCodeAndPrefix() {
    val err = ToolError.Launch(ToolLaunchError.JdkUnavailable(cause = "install failed"))
    assertEquals(EXIT_TOOL_ERROR, err.toExitCode())
    assertTrue(err.render().headline.startsWith("tool: JDK unavailable:"))
  }

  // UnknownAlias is config-shaped — exit 2 — and surfaces known aliases.
  @Test
  fun unknownAliasMapsToConfigExitCodeAndListsKnownAliases() {
    val err = ToolError.UnknownAlias(alias = "ktl", knownAliases = listOf("ktlint", "detekt"))
    assertEquals(EXIT_CONFIG_ERROR, err.toExitCode())
    val msg = err.render().headline
    assertTrue(msg.startsWith("tool 'ktl': not declared in [tools]."))
    assertTrue(msg.contains("ktlint"))
    assertTrue(msg.contains("detekt"))
  }

  // Pin: exactly three ToolError variants must route through EXIT_TOOL_ERROR=7.
  // Adding a fourth would break ADR 0028 §3 freeze of jar-launch failure surface.
  @Test
  fun exactlyThreeVariantsRouteThroughExitToolError() {
    val toolExitErrors: List<ToolError> =
      listOf(
        ToolError.Launch(ToolLaunchError.NotRunnableJar("a", "/j", "r")),
        ToolError.Launch(ToolLaunchError.MainClassMissing("a", "/j")),
        ToolError.Launch(ToolLaunchError.JdkUnavailable("c")),
      )
    assertEquals(3, toolExitErrors.count { it.toExitCode() == EXIT_TOOL_ERROR })
    // Sanity: every other constructor we exercise lands elsewhere.
    val nonToolExit: List<ToolError> =
      listOf(
        ToolError.Parse(ToolSectionParseError.MissingCoords("a")),
        ToolError.Resolve(
          ToolResolutionError.ResolveFailed("a", Coordinate("g", "a", "1.0"), emptyList())
        ),
        ToolError.Resolve(
          ToolResolutionError.IntegrityMismatch("a", Coordinate("g", "a", "1.0"), "x", "y")
        ),
        ToolError.Resolve(
          ToolResolutionError.LockfileMismatch(
            "a",
            Coordinate("g", "a", "1.1"),
            null,
            Coordinate("g", "a", "1.0"),
            null,
          )
        ),
        ToolError.UnknownAlias("a", emptyList()),
      )
    assertTrue(
      nonToolExit.none { it.toExitCode() == EXIT_TOOL_ERROR },
      "non-launch variants must not route through EXIT_TOOL_ERROR",
    )
  }
}
