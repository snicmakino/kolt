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
  // The `main_classpath` rename is load-bearing: older state files
  // stored the all-deps classpath under `classpath`. Keeping that
  // SerialName would let those files round-trip into a BuildResult
  // that hands an all-deps classpath to `kolt run` (which expects
  // main-only). Strict decode failure forces a rebuild instead.
  @SerialName("main_classpath") val classpath: String? = null,
  @SerialName("test_classpath") val testClasspath: String? = null,
  @SerialName("resources_newest_mtime") val resourcesNewestMtime: Long? = null,
  @SerialName("def_newest_mtime") val defNewestMtime: Long? = null,
)

fun isBuildUpToDate(current: BuildState, cached: BuildState?): Boolean {
  if (cached == null) return false
  val stateMatches =
    current.configMtime == cached.configMtime &&
      current.sourcesNewestMtime == cached.sourcesNewestMtime &&
      current.classesDirMtime == cached.classesDirMtime &&
      current.lockfileMtime == cached.lockfileMtime &&
      current.resourcesNewestMtime == cached.resourcesNewestMtime &&
      current.defNewestMtime == cached.defNewestMtime
  if (!stateMatches) return false
  // Input mtimes must not exceed artifact mtime, even when state
  // matches. Guards against a stale artifact paired with equally
  // stale state (#50).
  val artifact = current.classesDirMtime ?: return false
  if (current.sourcesNewestMtime > artifact) return false
  if ((current.resourcesNewestMtime ?: 0L) > artifact) return false
  if ((current.defNewestMtime ?: 0L) > artifact) return false
  return true
}

private val json = Json { prettyPrint = true }

fun serializeBuildState(state: BuildState): String = json.encodeToString(state)

fun parseBuildState(input: String): BuildState? =
  try {
    json.decodeFromString<BuildState>(input)
  } catch (_: Exception) {
    null
  }
