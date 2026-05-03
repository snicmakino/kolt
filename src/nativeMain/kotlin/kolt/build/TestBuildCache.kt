package kolt.build

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Separate from BUILD_STATE_FILE so a test-cache invalidation does not
// wipe the build-cache entry and vice versa (#59).
internal const val TEST_BUILD_STATE_FILE = "$BUILD_DIR/.kolt-test-state.json"

// `testArtifactMtime` is target-neutral by design: Native stores the test
// kexe file's mtime, JVM stores `newestMtimeAll(TEST_CLASSES_DIR)`. The
// cross-check below treats them identically — any input newer than the
// artifact means the artifact is stale.
//
// `lockfileMtime` mirrors BuildState's lockfile signal so a `kolt deps`
// update invalidates the test cache too. Without this, dependency
// changes that do not touch source files leave a stale test artifact
// silently re-used.
@Serializable
data class TestBuildState(
  @SerialName("config_mtime") val configMtime: Long,
  @SerialName("sources_newest_mtime") val sourcesNewestMtime: Long,
  @SerialName("test_sources_newest_mtime") val testSourcesNewestMtime: Long,
  @SerialName("test_artifact_mtime") val testArtifactMtime: Long?,
  @SerialName("def_newest_mtime") val defNewestMtime: Long? = null,
  @SerialName("lockfile_mtime") val lockfileMtime: Long? = null,
)

fun isTestBuildUpToDate(current: TestBuildState, cached: TestBuildState?): Boolean {
  if (cached == null) return false
  val stateMatches =
    current.configMtime == cached.configMtime &&
      current.sourcesNewestMtime == cached.sourcesNewestMtime &&
      current.testSourcesNewestMtime == cached.testSourcesNewestMtime &&
      current.testArtifactMtime == cached.testArtifactMtime &&
      current.defNewestMtime == cached.defNewestMtime &&
      current.lockfileMtime == cached.lockfileMtime
  if (!stateMatches) return false
  // Mirror of BuildCache cross-check (#50): no input mtime may exceed
  // the test artifact mtime, even when cached state matches.
  val artifact = current.testArtifactMtime ?: return false
  if (current.sourcesNewestMtime > artifact) return false
  if (current.testSourcesNewestMtime > artifact) return false
  if ((current.defNewestMtime ?: 0L) > artifact) return false
  if ((current.lockfileMtime ?: 0L) > artifact) return false
  return true
}

private val json = Json { prettyPrint = true }

fun serializeTestBuildState(state: TestBuildState): String = json.encodeToString(state)

fun parseTestBuildState(input: String): TestBuildState? =
  try {
    json.decodeFromString<TestBuildState>(input)
  } catch (_: Exception) {
    null
  }
