package kolt.build

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Separate from BUILD_STATE_FILE so a test-cache invalidation does not
// wipe the build-cache entry and vice versa (#59).
internal const val TEST_BUILD_STATE_FILE = "$BUILD_DIR/.kolt-test-state.json"

@Serializable
data class TestBuildState(
    @SerialName("config_mtime") val configMtime: Long,
    @SerialName("sources_newest_mtime") val sourcesNewestMtime: Long,
    @SerialName("test_sources_newest_mtime") val testSourcesNewestMtime: Long,
    @SerialName("test_kexe_mtime") val testKexeMtime: Long?,
    @SerialName("def_newest_mtime") val defNewestMtime: Long? = null,
)

fun isTestBuildUpToDate(current: TestBuildState, cached: TestBuildState?): Boolean {
    if (cached == null) return false
    val stateMatches = current.configMtime == cached.configMtime &&
        current.sourcesNewestMtime == cached.sourcesNewestMtime &&
        current.testSourcesNewestMtime == cached.testSourcesNewestMtime &&
        current.testKexeMtime == cached.testKexeMtime &&
        current.defNewestMtime == cached.defNewestMtime
    if (!stateMatches) return false
    // Mirror of BuildCache cross-check (#50): no input mtime may exceed
    // the test kexe mtime, even when cached state matches.
    val artifact = current.testKexeMtime ?: return false
    if (current.sourcesNewestMtime > artifact) return false
    if (current.testSourcesNewestMtime > artifact) return false
    if ((current.defNewestMtime ?: 0L) > artifact) return false
    return true
}

private val json = Json { prettyPrint = true }

fun serializeTestBuildState(state: TestBuildState): String =
    json.encodeToString(state)

fun parseTestBuildState(input: String): TestBuildState? =
    try {
        json.decodeFromString<TestBuildState>(input)
    } catch (_: Exception) {
        null
    }
