package kolt.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestBuildCacheTest {

    @Test
    fun upToDateWhenAllMtimesMatch() {
        val state = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            testSourcesNewestMtime = 2500L,
            testKexeMtime = 3000L
        )
        assertTrue(isTestBuildUpToDate(current = state, cached = state))
    }

    @Test
    fun notUpToDateWhenCachedIsNull() {
        val state = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            testSourcesNewestMtime = 2500L,
            testKexeMtime = 3000L
        )
        assertFalse(isTestBuildUpToDate(current = state, cached = null))
    }

    @Test
    fun notUpToDateWhenConfigMtimeChanged() {
        val cached = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            testSourcesNewestMtime = 2500L,
            testKexeMtime = 3000L
        )
        val current = cached.copy(configMtime = 1500L)
        assertFalse(isTestBuildUpToDate(current = current, cached = cached))
    }

    @Test
    fun notUpToDateWhenSourceMtimeChanged() {
        val cached = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            testSourcesNewestMtime = 2500L,
            testKexeMtime = 3000L
        )
        val current = cached.copy(sourcesNewestMtime = 2800L)
        assertFalse(isTestBuildUpToDate(current = current, cached = cached))
    }

    @Test
    fun notUpToDateWhenTestSourceMtimeChanged() {
        val cached = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            testSourcesNewestMtime = 2500L,
            testKexeMtime = 3000L
        )
        val current = cached.copy(testSourcesNewestMtime = 2900L)
        assertFalse(isTestBuildUpToDate(current = current, cached = cached))
    }

    @Test
    fun notUpToDateWhenTestKexeMtimeChanged() {
        val cached = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            testSourcesNewestMtime = 2500L,
            testKexeMtime = 3000L
        )
        val current = cached.copy(testKexeMtime = 3500L)
        assertFalse(isTestBuildUpToDate(current = current, cached = cached))
    }

    @Test
    fun notUpToDateWhenTestKexeMissing() {
        val cached = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            testSourcesNewestMtime = 2500L,
            testKexeMtime = 3000L
        )
        val current = cached.copy(testKexeMtime = null)
        assertFalse(isTestBuildUpToDate(current = current, cached = cached))
    }

    @Test
    fun notUpToDateWhenDefMtimeChanged() {
        val cached = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            testSourcesNewestMtime = 2500L,
            testKexeMtime = 3000L,
            defNewestMtime = 2700L
        )
        val current = cached.copy(defNewestMtime = 2800L)
        assertFalse(isTestBuildUpToDate(current = current, cached = cached))
    }

    // Cross-check: any input newer than the test kexe means the kexe is stale.
    @Test
    fun notUpToDateWhenSourcesNewerThanTestKexe() {
        val state = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 5000L,
            testSourcesNewestMtime = 2500L,
            testKexeMtime = 3000L
        )
        assertFalse(isTestBuildUpToDate(current = state, cached = state))
    }

    @Test
    fun notUpToDateWhenTestSourcesNewerThanTestKexe() {
        val state = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            testSourcesNewestMtime = 5000L,
            testKexeMtime = 3000L
        )
        assertFalse(isTestBuildUpToDate(current = state, cached = state))
    }

    @Test
    fun notUpToDateWhenDefNewerThanTestKexe() {
        val state = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            testSourcesNewestMtime = 2500L,
            testKexeMtime = 3000L,
            defNewestMtime = 4000L
        )
        assertFalse(isTestBuildUpToDate(current = state, cached = state))
    }

    @Test
    fun notUpToDateWhenTestKexeMtimeNullEvenIfStateMatches() {
        val state = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            testSourcesNewestMtime = 2500L,
            testKexeMtime = null
        )
        assertFalse(isTestBuildUpToDate(current = state, cached = state))
    }

    @Test
    fun upToDateWhenInputsNotNewerThanTestKexe() {
        val state = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            testSourcesNewestMtime = 2500L,
            testKexeMtime = 3000L,
            defNewestMtime = 2700L
        )
        assertTrue(isTestBuildUpToDate(current = state, cached = state))
    }

    @Test
    fun serializeAndDeserializeRoundTrip() {
        val state = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            testSourcesNewestMtime = 2500L,
            testKexeMtime = 3000L,
            defNewestMtime = 2700L
        )
        val json = serializeTestBuildState(state)
        val parsed = parseTestBuildState(json)
        assertEquals(state, parsed)
    }

    @Test
    fun serializeWithNullDefMtime() {
        val state = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            testSourcesNewestMtime = 2500L,
            testKexeMtime = 3000L,
            defNewestMtime = null
        )
        val json = serializeTestBuildState(state)
        val parsed = parseTestBuildState(json)
        assertEquals(state, parsed)
        assertNotNull(parsed)
        assertNull(parsed.defNewestMtime)
    }

    @Test
    fun serializationUsesSnakeCaseKeys() {
        val state = TestBuildState(
            configMtime = 1000L,
            sourcesNewestMtime = 2000L,
            testSourcesNewestMtime = 2500L,
            testKexeMtime = 3000L,
            defNewestMtime = 2700L
        )
        val json = serializeTestBuildState(state)
        assertTrue(json.contains("test_sources_newest_mtime"), "Expected 'test_sources_newest_mtime' in: $json")
        assertTrue(json.contains("test_kexe_mtime"), "Expected 'test_kexe_mtime' in: $json")
        assertTrue(json.contains("def_newest_mtime"), "Expected 'def_newest_mtime' in: $json")
    }

    @Test
    fun parseInvalidJsonReturnsNull() {
        assertNull(parseTestBuildState("not json"))
    }

    @Test
    fun parseEmptyStringReturnsNull() {
        assertNull(parseTestBuildState(""))
    }

    @Test
    fun testStateFilePathLivesUnderBuildDir() {
        assertTrue(
            TEST_BUILD_STATE_FILE.startsWith("$BUILD_DIR/"),
            "TEST_BUILD_STATE_FILE=$TEST_BUILD_STATE_FILE must live under BUILD_DIR=$BUILD_DIR"
        )
    }

    @Test
    fun testStateFilePathDiffersFromBuildStateFile() {
        // Separate from build state so test-cache invalidation does not
        // wipe the build-cache entry and vice versa (#59).
        assertFalse(
            TEST_BUILD_STATE_FILE == BUILD_STATE_FILE,
            "test state file must not collide with build state file"
        )
    }
}
