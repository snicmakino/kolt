package kolt.cli

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import kolt.resolve.compareVersions

enum class Severity {
  Major,
  Minor,
  Patch,
}

data class OutdatedRow(
  val groupArtifact: String,
  val current: String,
  val latest: String?,
  val severity: Severity?,
  val error: String?,
)

data class OutdatedReport(val main: List<OutdatedRow>, val test: List<OutdatedRow>)

// Returns null when `current >= latest`. Otherwise compares the leading
// numeric segments (major.minor.patch) and reports the most-significant
// position that differs. Non-numeric tail segments (`-RC1`, `-jre`) are
// ignored — the upstream fetcher already filters to stable versions, so
// here the qualifier is only a tiebreak the user doesn't see.
fun classifySeverity(current: String, latest: String): Severity? {
  if (compareVersions(current, latest) >= 0) return null
  val cur = leadingNumerics(current)
  val lat = leadingNumerics(latest)
  if (cur.getOrElse(0) { 0L } != lat.getOrElse(0) { 0L }) return Severity.Major
  if (cur.getOrElse(1) { 0L } != lat.getOrElse(1) { 0L }) return Severity.Minor
  return Severity.Patch
}

private fun leadingNumerics(version: String): List<Long> {
  val out = mutableListOf<Long>()
  for (token in version.split('.', '-')) {
    val n = token.toLongOrNull() ?: break
    out.add(n)
  }
  return out
}

fun computeOutdated(
  mainDeps: Map<String, String>,
  testDeps: Map<String, String>,
  fetchLatest: (group: String, artifact: String) -> Result<String, String>,
): OutdatedReport =
  OutdatedReport(
    main = computeSection(mainDeps, fetchLatest),
    test = computeSection(testDeps, fetchLatest),
  )

private fun computeSection(
  deps: Map<String, String>,
  fetchLatest: (group: String, artifact: String) -> Result<String, String>,
): List<OutdatedRow> {
  val rows = mutableListOf<OutdatedRow>()
  for ((groupArtifact, current) in deps) {
    val (group, artifact) = splitGroupArtifact(groupArtifact) ?: continue
    val latestResult = fetchLatest(group, artifact)
    val latest =
      latestResult.getOrElse { err ->
        rows.add(
          OutdatedRow(
            groupArtifact = groupArtifact,
            current = current,
            latest = null,
            severity = null,
            error = err,
          )
        )
        continue
      }
    val severity = classifySeverity(current, latest) ?: continue
    rows.add(
      OutdatedRow(
        groupArtifact = groupArtifact,
        current = current,
        latest = latest,
        severity = severity,
        error = null,
      )
    )
  }
  return rows.sortedBy { it.groupArtifact }
}

private fun splitGroupArtifact(coordinate: String): Pair<String, String>? {
  val sep = coordinate.indexOf(':')
  if (sep <= 0 || sep == coordinate.length - 1) return null
  return coordinate.substring(0, sep) to coordinate.substring(sep + 1)
}
