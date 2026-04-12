package kolt.build

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val BUILD_STATE_FILE = "$BUILD_DIR/.kolt-state.json"

@Serializable
data class BuildState(
    @SerialName("config_mtime") val configMtime: Long,
    @SerialName("sources_newest_mtime") val sourcesNewestMtime: Long,
    @SerialName("classes_dir_mtime") val classesDirMtime: Long?,
    @SerialName("lockfile_mtime") val lockfileMtime: Long?,
    val classpath: String? = null,
    @SerialName("resources_newest_mtime") val resourcesNewestMtime: Long? = null
)

fun isBuildUpToDate(current: BuildState, cached: BuildState?): Boolean {
    if (cached == null) return false
    return current.configMtime == cached.configMtime &&
        current.sourcesNewestMtime == cached.sourcesNewestMtime &&
        current.classesDirMtime == cached.classesDirMtime &&
        current.lockfileMtime == cached.lockfileMtime &&
        current.resourcesNewestMtime == cached.resourcesNewestMtime
}

private val json = Json { prettyPrint = true }

fun serializeBuildState(state: BuildState): String =
    json.encodeToString(state)

fun parseBuildState(input: String): BuildState? =
    try {
        json.decodeFromString<BuildState>(input)
    } catch (_: Exception) {
        null
    }

