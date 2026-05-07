package kolt.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val COL_GAP = "  "
private const val ARROW = "→" // U+2192 RIGHTWARDS ARROW
private const val MISSING_LATEST = "?"
private const val ROW_INDENT = "  "

fun formatOutdatedText(report: OutdatedReport): String {
  if (report.main.isEmpty() && report.test.isEmpty()) {
    return "All dependencies up to date."
  }
  val sections = mutableListOf<String>()
  if (report.main.isNotEmpty()) sections.add(renderSection("[dependencies]", report.main))
  if (report.test.isNotEmpty()) sections.add(renderSection("[test-dependencies]", report.test))
  return sections.joinToString("\n\n")
}

private fun renderSection(header: String, rows: List<OutdatedRow>): String {
  val w1 = rows.maxOf { it.groupArtifact.length }
  val w2 = rows.maxOf { it.current.length }
  val w3 = rows.maxOf { (it.latest ?: MISSING_LATEST).length }
  val lines = mutableListOf(header)
  for (row in rows) {
    val line = buildString {
      append(ROW_INDENT)
      append(row.groupArtifact.padEnd(w1))
      append(COL_GAP)
      append(row.current.padEnd(w2))
      append(COL_GAP)
      append(ARROW)
      append(COL_GAP)
      append((row.latest ?: MISSING_LATEST).padEnd(w3))
      append(COL_GAP)
      append(rowSuffix(row))
    }
    lines.add(line.trimEnd())
  }
  return lines.joinToString("\n")
}

// Severity tag is loud only for `Major`; Minor/Patch stay silent so the
// output reads like cargo/npm's "outdated" defaults rather than a rainbow.
// Errors always render — the user otherwise has no signal that a row was
// skipped.
private fun rowSuffix(row: OutdatedRow): String =
  when {
    row.error != null -> "(error: ${oneLineError(row.error)})"
    row.severity == Severity.Major -> "(major)"
    else -> ""
  }

// `OutdatedRow.error` is meant to be a one-line summary, but a future
// upstream might leak `\n` (e.g. wrapping a stack trace) or a leading
// `error: ` prefix. Either would corrupt the column-aligned text output
// — the row would wrap and `(error: error: ...)` would read awkwardly.
// Normalise defensively here so the formatter contract holds regardless
// of caller hygiene.
private fun oneLineError(error: String): String =
  error.removePrefix("error: ").replace(Regex("\\s+"), " ").trim()

@Serializable
private data class JsonRow(
  val group: String,
  val name: String,
  val current: String,
  val latest: String? = null,
  val severity: String? = null,
  val error: String? = null,
)

@Serializable
private data class JsonReport(val dependencies: List<JsonRow>, val testDependencies: List<JsonRow>)

private val outdatedJson = Json {
  prettyPrint = true
  prettyPrintIndent = "  "
  encodeDefaults = false
  explicitNulls = false
}

fun formatOutdatedJson(report: OutdatedReport): String {
  val dto =
    JsonReport(
      dependencies = report.main.map { it.toJsonRow() },
      testDependencies = report.test.map { it.toJsonRow() },
    )
  return outdatedJson.encodeToString(JsonReport.serializer(), dto)
}

private fun OutdatedRow.toJsonRow(): JsonRow {
  val sep = groupArtifact.indexOf(':')
  val group = if (sep > 0) groupArtifact.substring(0, sep) else groupArtifact
  val name = if (sep > 0) groupArtifact.substring(sep + 1) else ""
  return JsonRow(
    group = group,
    name = name,
    current = current,
    latest = latest,
    severity = severity?.name?.lowercase(),
    error = error,
  )
}
