package kolt.config

import kolt.infra.suggest.closestMatch

// ktoml encodes parse-position into the exception message text rather than
// exposing it via a public field — the relevant `ParseException`,
// `IllegalTypeException`, etc. are `internal` to the ktoml-core module.
// The pre-version-bump format is `"Line N: <detail>"` (note the trailing
// space). Bumping ktoml major can change this format; the
// `ConfigParseMessageFormatTest` fixture pins the regex against a synthetic
// broken kolt.toml so a format drift surfaces as a RED test.
internal val LINE_NO_REGEX = Regex("^Line (\\d+): ")

// Returns (lineNumber, restOfMessage). Falls back to (null, original) when
// the prefix is missing or malformed; (null, "") when the input itself is null.
internal fun extractKtomlLineNo(message: String?): Pair<Int?, String> {
  if (message == null) return null to ""
  val match = LINE_NO_REGEX.find(message) ?: return null to message
  val n = match.groupValues[1].toIntOrNull()
  val rest = message.substring(match.range.last + 1)
  return n to rest
}

// Top-level kolt.toml sections recognised today. R3.4 (narrowed) only
// suggests when the typo'd key is at top level — nested unknowns surface
// the key path without a suggestion. Updating this list when a new
// top-level section is added keeps the suggestion accurate; the drift
// guard test pins the contents against the RawKoltConfig field set.
internal val KNOWN_TOP_LEVEL_SECTIONS: List<String> =
  listOf(
      "build",
      "cinterop",
      "classpaths",
      "dependencies",
      "fmt",
      "kotlin",
      "repositories",
      "run",
      "test",
      "test-dependencies",
      "tools",
    )
    .sorted()

private val UNKNOWN_KEY_REGEX = Regex("^Unknown key received: <([^>]+)> in scope <([^>]*)>")

// ktoml-core 0.7.1 names the implicit root TOML node "rootNode" (see
// `com.akuleshov7.ktoml.tree.nodes.TomlFile.name`). UnknownNameException
// surfaces top-level typos with `scope <rootNode>`; nested scopes carry
// the parent section name (e.g. `<kotlin>`). The constant lives here so a
// future ktoml bump that renames the root surfaces as a single-edit fix
// and the empirical-pin test in ConfigParseMessageFormatTest catches it.
private const val KTOML_ROOT_SCOPE = "rootNode"

// Returns (offendingKey, suggestion). When the message does not match
// ktoml's UnknownNameException format, both are null. Nested-scope unknowns
// return the key but null suggestion (R3.4 narrowed scope).
internal fun parseUnknownKey(detail: String): Pair<String?, String?> {
  val match = UNKNOWN_KEY_REGEX.find(detail) ?: return null to null
  val key = match.groupValues[1]
  val scope = match.groupValues[2]
  if (scope != KTOML_ROOT_SCOPE) return key to null
  return key to closestMatch(key, KNOWN_TOP_LEVEL_SECTIONS)
}

// ktoml decodes a flat-form `[repositories]\n<name> = "<url>"` into the
// Map<String, RawRepository> schema by reporting <name> as an unknown key at
// rootNode scope — byte-identical to a real top-level typo. The exception
// alone is not deterministic enough to substitute the message, so we keep the
// raw ktoml error intact and append a migration hint paragraph keyed on the
// input containing `[repositories]` plus the offending key matching the name
// that would appear under that table. Conservative: skip the hint when the
// input has no `[repositories]` header so legitimate top-level typos like
// `koltn = "stray"` are not corrupted. The hint applies only to kolt.toml —
// the overlay schema (kolt.local.toml) is sub-table only by design and has
// no flat form to migrate from.
private val REPOSITORIES_HEADER_REGEX = Regex("(?m)^\\s*\\[repositories\\]\\s*$")

internal fun buildKtomlParseError(
  rawMessage: String?,
  path: String?,
  tomlString: String,
  sourceFile: String = "kolt.toml",
): ConfigError.ParseFailed {
  val (lineNo, detail) = extractKtomlLineNo(rawMessage)
  val (keyPath, suggestion) = parseUnknownKey(detail)
  val baseHeadline = "failed to parse $sourceFile: $detail"
  val migrationHint =
    if (
      sourceFile == "kolt.toml" &&
        keyPath != null &&
        REPOSITORIES_HEADER_REGEX.containsMatchIn(tomlString)
    ) {
      // Match `[repositories]` followed by either the offending key directly
      // on the next non-empty line OR after at most one preceding flat-form
      // entry (so a hint still fires for the second offending key in a
      // multi-entry flat block). A wider gap means the key is unlikely to
      // belong to this `[repositories]` table — keep the hint conservative.
      val nameRegex =
        Regex(
          "(?ms)^\\s*\\[repositories\\]\\s*\\R(?:[^\\[]*?\\R)?\\s*\"?" +
            Regex.escape(keyPath) +
            "\"?\\s*="
        )
      if (nameRegex.containsMatchIn(tomlString)) {
        "repositories schema migrated to sub-table form; " +
          "expected '[repositories.<name>] url = \"...\"', " +
          "got string value at '$keyPath'"
      } else null
    } else null
  val headline = if (migrationHint != null) "$baseHeadline -- $migrationHint" else baseHeadline
  return ConfigError.ParseFailed(
    message = headline,
    path = path,
    lineNo = lineNo,
    keyPath = keyPath,
    suggestion = if (migrationHint != null) null else suggestion,
  )
}
