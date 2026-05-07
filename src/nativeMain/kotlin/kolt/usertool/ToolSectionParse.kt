package kolt.usertool

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.resolve.Coordinate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Charset shared by group / artifact / version / classifier per design.md
// §Data Models. Maven coordinate grammar is otherwise opaque, so kolt only
// rejects shapes that would break URL/path construction or our own colon
// splitting; it does not enforce e.g. SemVer on the version segment.
private val COORD_SEGMENT_REGEX = Regex("""^[A-Za-z0-9._-]+$""")

// Alias grammar pinned in design.md §Data Models. Lowercase-letter prefix is
// load-bearing for ADR 0028 §3 freeze: relaxing or narrowing later breaks
// existing declarations, so the regex is captured at parse time and reused
// across the validation pipeline.
private val ALIAS_REGEX = Regex("""^[a-z][a-z0-9_-]{0,63}$""")

/**
 * Raw `[tools.<alias>]` entry as decoded by ktoml. `coords` is the only allowlisted field; the
 * other three are declared explicitly nullable so [parseToolSection] can reject orchestration
 * fields by name (R7.1, R7.2). Other unknown keys are silently dropped by ktoml's
 * `ignoreUnknownNames=true` and may be tightened later additively.
 */
@Serializable
data class RawToolEntry(
  val coords: String? = null,
  @SerialName("depends-on") val dependsOn: String? = null,
  val args: List<String>? = null,
  val main: String? = null,
)

/** Distinguishable failures surfaced by [parseToolSection]. */
sealed class ToolSectionParseError {
  data class ForbiddenField(val alias: String, val field: String) : ToolSectionParseError()

  data class InvalidAlias(val alias: String, val reason: String) : ToolSectionParseError()

  data class MalformedCoords(val alias: String, val coords: String, val reason: String) :
    ToolSectionParseError()

  data class DuplicateAlias(val alias: String) : ToolSectionParseError()

  data class MissingCoords(val alias: String) : ToolSectionParseError()
}

/**
 * Validate `[tools]` raw entries into a typed map. The validation order is:
 * 1. alias regex `^[a-z][a-z0-9_-]{0,63}$` (R1.3)
 * 2. orchestration field reject (`depends-on`, `args`, `main` — R7.1, R7.2)
 * 3. `coords` presence (R1.1)
 * 4. `coords` shape via [parseCoordsString] (R1.2, R1.4)
 *
 * `null` and empty input both yield an empty map; the `[tools]` section is fully optional.
 *
 * Duplicate aliases (R1.5) are reported by ktoml at TOML parse time before reaching this function,
 * so [ToolSectionParseError.DuplicateAlias] is provided for completeness but not produced here.
 */
fun parseToolSection(
  rawTools: Map<String, RawToolEntry>?
): Result<Map<String, ToolEntry>, ToolSectionParseError> {
  if (rawTools.isNullOrEmpty()) return Ok(emptyMap())
  val out = LinkedHashMap<String, ToolEntry>(rawTools.size)
  for ((rawAlias, rawEntry) in rawTools) {
    val alias = rawAlias.removeSurrounding("\"")
    if (!ALIAS_REGEX.matches(alias)) {
      return Err(
        ToolSectionParseError.InvalidAlias(
          alias,
          "alias must match ^[a-z][a-z0-9_-]{0,63}$ (lowercase letter prefix, " +
            "[a-z0-9_-] thereafter, 64 chars max)",
        )
      )
    }
    rawEntry.dependsOn?.let {
      return Err(ToolSectionParseError.ForbiddenField(alias, "depends-on"))
    }
    rawEntry.args?.let {
      return Err(ToolSectionParseError.ForbiddenField(alias, "args"))
    }
    rawEntry.main?.let {
      return Err(ToolSectionParseError.ForbiddenField(alias, "main"))
    }
    val coordsString = rawEntry.coords ?: return Err(ToolSectionParseError.MissingCoords(alias))
    val (coordinate, classifier) =
      parseCoordsString(coordsString).getOrElse {
        return Err(ToolSectionParseError.MalformedCoords(alias, coordsString, it))
      }
    out[alias] = ToolEntry(coordinate, classifier)
  }
  return Ok(out)
}

/**
 * Parse a `[tools]` `coords` value into `(Coordinate, classifier?)`.
 *
 * Accepts `group:artifact:version` and `group:artifact:version:classifier`. All four segments must
 * be non-empty and match `[A-Za-z0-9._-]+`. Any other shape returns `Err(reason)`; the caller
 * (`parseToolSection`) is expected to attach the offending alias to the message.
 */
fun parseCoordsString(s: String): Result<Pair<Coordinate, String?>, String> {
  if (s.isEmpty()) {
    return Err("coords must not be empty")
  }
  val parts = s.split(":")
  if (parts.size !in 3..4) {
    return Err(
      "coords '$s' must be of the form group:artifact:version[:classifier] " +
        "(found ${parts.size} colon-separated segments)"
    )
  }
  val group = parts[0]
  val artifact = parts[1]
  val version = parts[2]
  val classifier = if (parts.size == 4) parts[3] else null

  if (!COORD_SEGMENT_REGEX.matches(group)) {
    return Err("coords '$s': group '$group' must match [A-Za-z0-9._-]+ and be non-empty")
  }
  if (!COORD_SEGMENT_REGEX.matches(artifact)) {
    return Err("coords '$s': artifact '$artifact' must match [A-Za-z0-9._-]+ and be non-empty")
  }
  if (!COORD_SEGMENT_REGEX.matches(version)) {
    return Err("coords '$s': version '$version' must match [A-Za-z0-9._-]+ and be non-empty")
  }
  if (classifier != null && !COORD_SEGMENT_REGEX.matches(classifier)) {
    return Err("coords '$s': classifier '$classifier' must match [A-Za-z0-9._-]+ and be non-empty")
  }
  return Ok(Coordinate(group, artifact, version) to classifier)
}
