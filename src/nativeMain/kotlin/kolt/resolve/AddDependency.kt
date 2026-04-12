package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

data class AddArgs(
    val group: String,
    val artifact: String,
    val version: String?,
    val isTest: Boolean
)

sealed class AddArgsError {
    data object MissingCoordinate : AddArgsError()
    data class InvalidFormat(val input: String) : AddArgsError()
}

fun parseAddArgs(args: List<String>): Result<AddArgs, AddArgsError> {
    val isTest = args.contains("--test")
    val positional = args.filter { it != "--test" }

    if (positional.isEmpty()) return Err(AddArgsError.MissingCoordinate)

    val input = positional[0]
    val parts = input.split(":")

    return when (parts.size) {
        2 -> {
            if (parts[0].isEmpty() || parts[1].isEmpty()) return Err(AddArgsError.InvalidFormat(input))
            Ok(AddArgs(parts[0], parts[1], null, isTest))
        }
        3 -> {
            if (parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) return Err(AddArgsError.InvalidFormat(input))
            Ok(AddArgs(parts[0], parts[1], parts[2], isTest))
        }
        else -> Err(AddArgsError.InvalidFormat(input))
    }
}

data class AlreadyExists(val groupArtifact: String)

fun addDependencyToToml(
    toml: String,
    groupArtifact: String,
    version: String,
    isTest: Boolean
): Result<String, AlreadyExists> {
    val sectionHeader = if (isTest) "[test-dependencies]" else "[dependencies]"

    // Check if the dependency already exists in the target section
    if (containsDependency(toml, groupArtifact, sectionHeader)) {
        return Err(AlreadyExists(groupArtifact))
    }

    val newEntry = "\"$groupArtifact\" = \"$version\""
    val lines = toml.lines().toMutableList()
    val sectionIndex = lines.indexOfFirst { it.trim() == sectionHeader }

    if (sectionIndex >= 0) {
        // Insert before trailing blank lines of the section
        val insertIndex = findSectionContentEnd(lines, sectionIndex)
        lines.add(insertIndex, newEntry)
    } else {
        // Section doesn't exist — append it
        // Ensure a blank line separator before the new section
        if (lines.isNotEmpty() && lines.last().isNotEmpty()) {
            lines.add("")
        }
        lines.add(sectionHeader)
        lines.add(newEntry)
    }

    val result = lines.joinToString("\n")
    // Preserve trailing newline if input had one, or ensure one for new sections
    return Ok(if (result.endsWith("\n")) result else "$result\n")
}

private fun containsDependency(toml: String, groupArtifact: String, sectionHeader: String): Boolean {
    val lines = toml.lines()
    val sectionIndex = lines.indexOfFirst { it.trim() == sectionHeader }
    if (sectionIndex < 0) return false

    for (i in (sectionIndex + 1) until lines.size) {
        val trimmed = lines[i].trim()
        if (trimmed.startsWith("[")) break
        if (trimmed.startsWith("\"$groupArtifact\"") || trimmed.startsWith("'$groupArtifact'")) {
            return true
        }
    }
    return false
}

private fun findSectionContentEnd(lines: List<String>, sectionIndex: Int): Int {
    var lastContentIndex = sectionIndex
    for (i in (sectionIndex + 1) until lines.size) {
        val trimmed = lines[i].trim()
        if (trimmed.startsWith("[")) break
        if (trimmed.isNotEmpty()) lastContentIndex = i
    }
    return lastContentIndex + 1
}
