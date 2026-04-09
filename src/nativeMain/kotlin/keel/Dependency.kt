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

const val MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2"

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

private fun buildMavenUrl(coord: Coordinate, extension: String): String {
    val groupPath = coord.group.replace('.', '/')
    return "$MAVEN_CENTRAL_BASE/$groupPath/${coord.artifact}/${coord.version}/${coord.artifact}-${coord.version}.$extension"
}

private fun buildRelativePath(coord: Coordinate, extension: String): String {
    val groupPath = coord.group.replace('.', '/')
    return "$groupPath/${coord.artifact}/${coord.version}/${coord.artifact}-${coord.version}.$extension"
}

fun buildDownloadUrl(coord: Coordinate): String = buildMavenUrl(coord, "jar")

fun buildCachePath(coord: Coordinate): String = buildRelativePath(coord, "jar")

fun buildPomDownloadUrl(coord: Coordinate): String = buildMavenUrl(coord, "pom")

fun buildPomCachePath(coord: Coordinate): String = buildRelativePath(coord, "pom")

fun buildModuleDownloadUrl(coord: Coordinate): String = buildMavenUrl(coord, "module")

fun buildModuleCachePath(coord: Coordinate): String = buildRelativePath(coord, "module")

fun buildClasspath(paths: List<String>): String = paths.joinToString(":")
