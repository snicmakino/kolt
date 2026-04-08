package keel

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result

data class InvalidCoordinate(val input: String)

data class Coordinate(
    val group: String,
    val artifact: String,
    val version: String
)

fun parseCoordinate(groupArtifact: String, version: String): Result<Coordinate, InvalidCoordinate> {
    val parts = groupArtifact.split(":")
    if (parts.size != 2) {
        return Err(InvalidCoordinate(groupArtifact))
    }
    val (group, artifact) = parts
    if (group.isEmpty() || artifact.isEmpty()) {
        return Err(InvalidCoordinate(groupArtifact))
    }
    return Ok(Coordinate(group, artifact, version))
}

/**
 * Builds a Maven Central download URL.
 * Dots in group are converted to slashes: `org.example:lib:1.0` → `https://repo1.maven.org/maven2/org/example/lib/1.0/lib-1.0.jar`
 */
fun buildDownloadUrl(coord: Coordinate): String {
    val groupPath = coord.group.replace('.', '/')
    return "https://repo1.maven.org/maven2/$groupPath/${coord.artifact}/${coord.version}/${coord.artifact}-${coord.version}.jar"
}

/**
 * Builds a relative cache path under `~/.keel/cache/`.
 * Example: `org.example:lib:1.0` → `org/example/lib/1.0/lib-1.0.jar`
 */
fun buildCachePath(coord: Coordinate): String {
    val groupPath = coord.group.replace('.', '/')
    return "$groupPath/${coord.artifact}/${coord.version}/${coord.artifact}-${coord.version}.jar"
}

fun buildClasspath(paths: List<String>): String = paths.joinToString(":")
