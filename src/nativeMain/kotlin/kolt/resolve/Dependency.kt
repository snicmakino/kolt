package kolt.resolve

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

private fun buildMavenUrl(coord: Coordinate, baseUrl: String, extension: String): String {
    val groupPath = coord.group.replace('.', '/')
    return "$baseUrl/$groupPath/${coord.artifact}/${coord.version}/${coord.artifact}-${coord.version}.$extension"
}

private fun buildRelativePath(coord: Coordinate, extension: String): String {
    val groupPath = coord.group.replace('.', '/')
    return "$groupPath/${coord.artifact}/${coord.version}/${coord.artifact}-${coord.version}.$extension"
}

fun buildDownloadUrl(coord: Coordinate, baseUrl: String): String = buildMavenUrl(coord, baseUrl, "jar")

fun buildCachePath(coord: Coordinate): String = buildRelativePath(coord, "jar")

fun buildPomDownloadUrl(coord: Coordinate, baseUrl: String): String = buildMavenUrl(coord, baseUrl, "pom")

fun buildPomCachePath(coord: Coordinate): String = buildRelativePath(coord, "pom")

fun buildModuleDownloadUrl(coord: Coordinate, baseUrl: String): String = buildMavenUrl(coord, baseUrl, "module")

fun buildModuleCachePath(coord: Coordinate): String = buildRelativePath(coord, "module")

fun buildKlibDownloadUrl(coord: Coordinate, baseUrl: String): String = buildMavenUrl(coord, baseUrl, "klib")

fun buildKlibCachePath(coord: Coordinate): String = buildRelativePath(coord, "klib")

fun buildClasspath(paths: List<String>): String = paths.joinToString(":")
