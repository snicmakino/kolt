package kolt.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun versionStringContainsKoltAndVersion() {
        val result = versionString()
        assertEquals("kolt $KOLT_VERSION", result)
    }

    @Test
    fun koltVersionIsValidSemver() {
        assertTrue(KOLT_VERSION.matches(Regex("""\d+\.\d+\.\d+""")))
    }
}
