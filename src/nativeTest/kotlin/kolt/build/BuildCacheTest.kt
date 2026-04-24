package kolt.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildCacheTest {

  @Test
  fun upToDateWhenAllMtimesMatch() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = 500L,
      )
    assertTrue(isBuildUpToDate(current = state, cached = state))
  }

  @Test
  fun notUpToDateWhenCachedIsNull() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
      )
    assertFalse(isBuildUpToDate(current = state, cached = null))
  }

  @Test
  fun notUpToDateWhenConfigMtimeChanged() {
    val cached =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
      )
    val current = cached.copy(configMtime = 1500L)
    assertFalse(isBuildUpToDate(current = current, cached = cached))
  }

  @Test
  fun notUpToDateWhenSourceMtimeChanged() {
    val cached =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
      )
    val current = cached.copy(sourcesNewestMtime = 2500L)
    assertFalse(isBuildUpToDate(current = current, cached = cached))
  }

  @Test
  fun notUpToDateWhenClassesDirMtimeChanged() {
    val cached =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
      )
    val current = cached.copy(classesDirMtime = 3500L)
    assertFalse(isBuildUpToDate(current = current, cached = cached))
  }

  @Test
  fun notUpToDateWhenClassesDirMtimeIsNull() {
    val cached =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
      )
    val current = cached.copy(classesDirMtime = null)
    assertFalse(isBuildUpToDate(current = current, cached = cached))
  }

  @Test
  fun notUpToDateWhenLockfileMtimeChanged() {
    val cached =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = 500L,
      )
    val current = cached.copy(lockfileMtime = 600L)
    assertFalse(isBuildUpToDate(current = current, cached = cached))
  }

  @Test
  fun upToDateWhenBothLockfileMtimesNull() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
      )
    assertTrue(isBuildUpToDate(current = state, cached = state))
  }

  @Test
  fun serializeAndDeserializeRoundTrip() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = 500L,
        classpath = "/cache/a.jar:/cache/b.jar",
      )
    val json = serializeBuildState(state)
    val parsed = parseBuildState(json)
    assertEquals(state, parsed)
  }

  @Test
  fun serializeWithNullLockfileMtime() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
      )
    val json = serializeBuildState(state)
    val parsed = parseBuildState(json)
    assertEquals(state, parsed)
    assertNull(parsed!!.classpath)
  }

  @Test
  fun classpathPreservedInBuildState() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = 500L,
        classpath = "/cache/okhttp-jvm-5.3.2.jar:/cache/okio-jvm-3.16.4.jar",
      )
    val json = serializeBuildState(state)
    val parsed = parseBuildState(json)
    assertEquals("/cache/okhttp-jvm-5.3.2.jar:/cache/okio-jvm-3.16.4.jar", parsed!!.classpath)
  }

  // The state cache carries both the main classpath (for `kolt run` /
  // watch-run) and the main ∪ test classpath (for `kolt test`) so an
  // up-to-date return path can hand either back without re-resolving.
  @Test
  fun testClasspathPreservedInBuildState() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = 500L,
        classpath = "/cache/main.jar",
        testClasspath = "/cache/main.jar:/cache/junit-jupiter.jar",
      )
    val json = serializeBuildState(state)
    val parsed = parseBuildState(json)
    assertEquals(
      "/cache/main.jar:/cache/junit-jupiter.jar",
      parsed!!.testClasspath,
      "testClasspath must roundtrip",
    )
    assertEquals("/cache/main.jar", parsed.classpath, "main classpath still roundtrips")
  }

  @Test
  fun serializationUsesMainAndTestClasspathKeys() {
    val state =
      BuildState(
        configMtime = 1L,
        sourcesNewestMtime = 2L,
        classesDirMtime = 3L,
        lockfileMtime = null,
        classpath = "/cache/main.jar",
        testClasspath = "/cache/main.jar:/cache/junit.jar",
      )
    val json = serializeBuildState(state)
    assertTrue(json.contains("main_classpath"), "Expected 'main_classpath' in: $json")
    assertTrue(json.contains("test_classpath"), "Expected 'test_classpath' in: $json")
  }

  @Test
  fun legacyClasspathKeyIsInvalidatedOnParse() {
    // Older state files used the bare "classpath" key for the all-deps
    // classpath. Refusing that shape forces a rebuild so the new
    // main/test split is populated cleanly (pre-v1 clean break).
    val legacyJson =
      """{"config_mtime":1,"sources_newest_mtime":2,"classes_dir_mtime":3,"lockfile_mtime":null,"classpath":"/cache/all.jar"}"""
    assertNull(parseBuildState(legacyJson))
  }

  @Test
  fun serializationUsesClassesDirMtimeKey() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
      )
    val json = serializeBuildState(state)
    assertTrue(
      json.contains("classes_dir_mtime"),
      "Expected JSON key 'classes_dir_mtime' in: $json",
    )
    assertFalse(json.contains("output_mtime"), "Unexpected legacy key 'output_mtime' in: $json")
  }

  @Test
  fun oldFormatWithOutputMtimeKeyReturnsNull() {
    // Old format used output_mtime; parseBuildState must not accept it as classesDirMtime
    val oldJson =
      """{"config_mtime":1000,"sources_newest_mtime":2000,"output_mtime":3000,"lockfile_mtime":null}"""
    assertNull(parseBuildState(oldJson))
  }

  @Test
  fun parseInvalidJsonReturnsNull() {
    assertNull(parseBuildState("not json"))
  }

  @Test
  fun parseEmptyStringReturnsNull() {
    assertNull(parseBuildState(""))
  }

  @Test
  fun upToDateWhenResourcesMtimesMatch() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 4000L,
        lockfileMtime = null,
        resourcesNewestMtime = 3000L,
      )
    assertTrue(isBuildUpToDate(current = state, cached = state))
  }

  @Test
  fun notUpToDateWhenResourcesMtimeChanged() {
    val cached =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
        resourcesNewestMtime = 4000L,
      )
    val current = cached.copy(resourcesNewestMtime = 5000L)
    assertFalse(isBuildUpToDate(current = current, cached = cached))
  }

  @Test
  fun notUpToDateWhenResourcesMtimeChangedFromNullToValue() {
    val cached =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
        resourcesNewestMtime = null,
      )
    val current = cached.copy(resourcesNewestMtime = 4000L)
    assertFalse(isBuildUpToDate(current = current, cached = cached))
  }

  @Test
  fun upToDateWhenBothResourcesMtimesNull() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
        resourcesNewestMtime = null,
      )
    assertTrue(isBuildUpToDate(current = state, cached = state))
  }

  @Test
  fun serializeAndDeserializeRoundTripWithResourcesMtime() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = 500L,
        resourcesNewestMtime = 4000L,
      )
    val json = serializeBuildState(state)
    val parsed = parseBuildState(json)
    assertEquals(state, parsed)
  }

  @Test
  fun serializationUsesResourcesNewestMtimeKey() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
        resourcesNewestMtime = 4000L,
      )
    val json = serializeBuildState(state)
    assertTrue(
      json.contains("resources_newest_mtime"),
      "Expected JSON key 'resources_newest_mtime' in: $json",
    )
  }

  @Test
  fun resourcesNewestMtimeDefaultsToNullForOlderStateJson() {
    val oldJson =
      """{"config_mtime":1000,"sources_newest_mtime":2000,"classes_dir_mtime":3000,"lockfile_mtime":null}"""
    val parsed = parseBuildState(oldJson)
    assertNotNull(parsed)
    assertNull(parsed.resourcesNewestMtime)
  }

  @Test
  fun upToDateWhenDefMtimesMatch() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 5000L,
        lockfileMtime = null,
        defNewestMtime = 3000L,
      )
    assertTrue(isBuildUpToDate(current = state, cached = state))
  }

  @Test
  fun notUpToDateWhenDefMtimeChanged() {
    val cached =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
        defNewestMtime = 5000L,
      )
    val current = cached.copy(defNewestMtime = 6000L)
    assertFalse(isBuildUpToDate(current = current, cached = cached))
  }

  @Test
  fun notUpToDateWhenDefMtimeChangedFromNullToValue() {
    val cached =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
        defNewestMtime = null,
      )
    val current = cached.copy(defNewestMtime = 5000L)
    assertFalse(isBuildUpToDate(current = current, cached = cached))
  }

  @Test
  fun notUpToDateWhenDefMtimeChangedFromValueToNull() {
    val cached =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
        defNewestMtime = 5000L,
      )
    val current = cached.copy(defNewestMtime = null)
    assertFalse(isBuildUpToDate(current = current, cached = cached))
  }

  @Test
  fun upToDateWhenBothDefMtimesNull() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
        defNewestMtime = null,
      )
    assertTrue(isBuildUpToDate(current = state, cached = state))
  }

  @Test
  fun serializeAndDeserializeRoundTripWithDefMtime() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = 500L,
        defNewestMtime = 5000L,
      )
    val json = serializeBuildState(state)
    val parsed = parseBuildState(json)
    assertEquals(state, parsed)
  }

  @Test
  fun serializationUsesDefNewestMtimeKey() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
        defNewestMtime = 5000L,
      )
    val json = serializeBuildState(state)
    assertTrue(json.contains("def_newest_mtime"), "Expected JSON key 'def_newest_mtime' in: $json")
  }

  @Test
  fun defNewestMtimeDefaultsToNullForOlderStateJson() {
    val oldJson =
      """{"config_mtime":1000,"sources_newest_mtime":2000,"classes_dir_mtime":3000,"lockfile_mtime":null}"""
    val parsed = parseBuildState(oldJson)
    assertNotNull(parsed)
    assertNull(parsed.defNewestMtime)
  }

  // #50: defence against stale state file + stale artifact combo.
  // If any input mtime exceeds the artifact mtime, the artifact cannot
  // reflect current inputs regardless of state equality.
  @Test
  fun notUpToDateWhenSourcesNewerThanClassesDir() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 5000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
      )
    assertFalse(isBuildUpToDate(current = state, cached = state))
  }

  @Test
  fun notUpToDateWhenResourcesNewerThanClassesDir() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
        resourcesNewestMtime = 4000L,
      )
    assertFalse(isBuildUpToDate(current = state, cached = state))
  }

  @Test
  fun notUpToDateWhenDefNewerThanClassesDir() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
        defNewestMtime = 4000L,
      )
    assertFalse(isBuildUpToDate(current = state, cached = state))
  }

  @Test
  fun notUpToDateWhenClassesDirMtimeNullEvenIfStateMatches() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = null,
        lockfileMtime = null,
      )
    assertFalse(isBuildUpToDate(current = state, cached = state))
  }

  @Test
  fun upToDateWhenInputsNotNewerThanArtifact() {
    val state =
      BuildState(
        configMtime = 1000L,
        sourcesNewestMtime = 2000L,
        classesDirMtime = 3000L,
        lockfileMtime = null,
        resourcesNewestMtime = 2500L,
        defNewestMtime = 2700L,
      )
    assertTrue(isBuildUpToDate(current = state, cached = state))
  }
}
