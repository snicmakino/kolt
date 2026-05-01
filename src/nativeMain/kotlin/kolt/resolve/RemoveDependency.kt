package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

sealed class RemoveArgsError {
  data object MissingCoordinate : RemoveArgsError()

  data class InvalidFormat(val input: String) : RemoveArgsError()
}

// Accepts both `group:artifact` and `group:artifact:version`; the version
// is discarded because kolt.toml stores exactly one version per coord, so
// remove always targets `group:artifact`.
fun parseRemoveArgs(args: List<String>): Result<String, RemoveArgsError> {
  if (args.isEmpty()) return Err(RemoveArgsError.MissingCoordinate)
  val input = args[0]
  val parts = input.split(":")
  if (parts.size !in 2..3 || parts.any { it.isEmpty() }) {
    return Err(RemoveArgsError.InvalidFormat(input))
  }
  return Ok("${parts[0]}:${parts[1]}")
}

data class RemovedEntry(val version: String, val isTest: Boolean)

data class RemoveResult(val newToml: String, val removed: List<RemovedEntry>)

private val SECTIONS = listOf("[dependencies]" to false, "[test-dependencies]" to true)

private val VERSION_PATTERN = Regex("""=\s*['"]([^'"]+)['"]""")

fun removeDependencyFromToml(toml: String, groupArtifact: String): RemoveResult {
  val lines = toml.lines().toMutableList()
  val removed = mutableListOf<RemovedEntry>()

  for ((header, isTest) in SECTIONS) {
    val sectionStart = lines.indexOfFirst { it.trim() == header }
    if (sectionStart < 0) continue

    // Loop without break on the match: if a hand-edited kolt.toml has the
    // same key twice in one section, ktoml would reject it on parse, but
    // the line-level edit here does not go through ktoml. Removing all
    // matches keeps the post-state self-consistent.
    var i = sectionStart + 1
    while (i < lines.size) {
      val trimmed = lines[i].trim()
      if (trimmed.startsWith("[")) break
      if (trimmed.startsWith("\"$groupArtifact\"") || trimmed.startsWith("'$groupArtifact'")) {
        val version = VERSION_PATTERN.find(lines[i])?.groupValues?.get(1) ?: ""
        removed.add(RemovedEntry(version, isTest))
        lines.removeAt(i)
      } else {
        i++
      }
    }
  }

  val joined = lines.joinToString("\n")
  val final = if (toml.endsWith("\n") && !joined.endsWith("\n")) "$joined\n" else joined
  return RemoveResult(final, removed)
}
