package kolt.usertool

import kolt.cli.EXIT_CONFIG_ERROR
import kolt.cli.EXIT_DEPENDENCY_ERROR
import kolt.cli.EXIT_TOOL_ERROR
import kolt.infra.output.RenderedDiagnostic
import kolt.infra.output.Severity
import kolt.resolve.Coordinate

/**
 * Distinguishable failures surfaced by `ToolResolution.ensureTool`.
 *
 * `LockfileMismatch` carries both toml-side and lockfile-side coords + classifier so the formatted
 * stderr message can render the design-mandated `'<pinned>' differs from '<toml>'` shape verbatim.
 */
sealed class ToolResolutionError {
  data class ResolveFailed(val alias: String, val coords: Coordinate, val attempts: List<String>) :
    ToolResolutionError()

  data class IntegrityMismatch(
    val alias: String,
    val coords: Coordinate,
    val expected: String,
    val actual: String,
  ) : ToolResolutionError()

  data class LockfileMismatch(
    val alias: String,
    val tomlCoords: Coordinate,
    val tomlClassifier: String?,
    val lockedCoords: Coordinate,
    val lockedClassifier: String?,
  ) : ToolResolutionError()
}

/**
 * Distinguishable failures surfaced by `ToolLauncher.launch`.
 *
 * The three Launch variants are the only failure shapes that route through `EXIT_TOOL_ERROR=7`
 * (jar-launch failures); `JdkUnavailable` has no alias because it can also surface from the
 * pre-launch bootstrap-jdk path, before any specific tool has been selected.
 */
sealed class ToolLaunchError {
  data class NotRunnableJar(val alias: String, val jarPath: String, val reason: String) :
    ToolLaunchError()

  data class MainClassMissing(val alias: String, val jarPath: String) : ToolLaunchError()

  data class JdkUnavailable(val cause: String) : ToolLaunchError()
}

/**
 * Top-level error envelope for the `[tools]` pipeline. Variant choice maps cleanly to
 * `kolt.cli.ExitCode`:
 * - `Parse`, `UnknownAlias` → `EXIT_CONFIG_ERROR=2`
 * - `Resolve.*` → `EXIT_DEPENDENCY_ERROR=3`
 * - `Launch.*` → `EXIT_TOOL_ERROR=7` (the only three variants that do so — pinned by
 *   `ToolErrorTest.exactlyThreeVariantsRouteThroughExitToolError`)
 *
 * `render()` emits the variant-specific headline that R5.4 ("cause-distinguishable surface") relies
 * on: the headline identifies the cause, the exit code identifies kolt-level category.
 */
sealed class ToolError {
  abstract fun toExitCode(): Int

  abstract fun render(): RenderedDiagnostic

  data class Parse(val cause: ToolSectionParseError) : ToolError() {
    override fun toExitCode(): Int = EXIT_CONFIG_ERROR

    override fun render(): RenderedDiagnostic {
      val (alias, detail) =
        when (val c = cause) {
          is ToolSectionParseError.ForbiddenField ->
            c.alias to "forbidden field '${c.field}' is not allowed in [tools.${c.alias}]"
          is ToolSectionParseError.InvalidAlias -> c.alias to c.reason
          is ToolSectionParseError.MalformedCoords ->
            c.alias to "malformed coords '${c.coords}': ${c.reason}"
          is ToolSectionParseError.MissingCoords -> c.alias to "missing required field 'coords'"
          is ToolSectionParseError.DuplicateAlias -> c.alias to "duplicate alias"
        }
      return RenderedDiagnostic(Severity.Error, "tool '$alias': parse error: $detail")
    }
  }

  data class Resolve(val cause: ToolResolutionError) : ToolError() {
    override fun toExitCode(): Int = EXIT_DEPENDENCY_ERROR

    override fun render(): RenderedDiagnostic =
      when (val c = cause) {
        is ToolResolutionError.ResolveFailed -> {
          val tail =
            if (c.attempts.isEmpty()) "no repository attempts recorded"
            else "tried ${c.attempts.joinToString(", ")}"
          RenderedDiagnostic(Severity.Error, "tool '${c.alias}': resolve failed: $tail")
        }
        is ToolResolutionError.IntegrityMismatch ->
          RenderedDiagnostic(
            Severity.Error,
            "tool '${c.alias}': integrity mismatch: expected ${c.expected} got ${c.actual}",
          )
        is ToolResolutionError.LockfileMismatch -> {
          val pinned = formatCoordsWithClassifier(c.lockedCoords, c.lockedClassifier)
          val toml = formatCoordsWithClassifier(c.tomlCoords, c.tomlClassifier)
          RenderedDiagnostic(
            severity = Severity.Error,
            headline = "tool '${c.alias}': lockfile pin '$pinned' differs from kolt.toml '$toml'",
            hint = "Run `kolt update` to refresh tool pins.",
          )
        }
      }
  }

  data class Launch(val cause: ToolLaunchError) : ToolError() {
    override fun toExitCode(): Int = EXIT_TOOL_ERROR

    override fun render(): RenderedDiagnostic =
      when (val c = cause) {
        is ToolLaunchError.NotRunnableJar ->
          RenderedDiagnostic(
            Severity.Error,
            "tool '${c.alias}': not a runnable jar (${c.reason}) at ${c.jarPath}",
          )
        is ToolLaunchError.MainClassMissing ->
          RenderedDiagnostic(
            Severity.Error,
            "tool '${c.alias}': Main-Class missing in MANIFEST.MF of ${c.jarPath}",
          )
        is ToolLaunchError.JdkUnavailable ->
          RenderedDiagnostic(Severity.Error, "tool: JDK unavailable: ${c.cause}")
      }
  }

  data class UnknownAlias(val alias: String, val knownAliases: List<String>) : ToolError() {
    override fun toExitCode(): Int = EXIT_CONFIG_ERROR

    override fun render(): RenderedDiagnostic {
      val known =
        if (knownAliases.isEmpty()) "(none declared)" else knownAliases.sorted().joinToString(", ")
      return RenderedDiagnostic(
        Severity.Error,
        "tool '$alias': not declared in [tools]. Known aliases: $known",
      )
    }
  }
}

private fun formatCoordsWithClassifier(coords: Coordinate, classifier: String?): String {
  val base = "${coords.group}:${coords.artifact}:${coords.version}"
  return if (classifier == null) base else "$base:$classifier"
}
