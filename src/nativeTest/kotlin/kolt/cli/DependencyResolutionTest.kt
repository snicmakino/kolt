package kolt.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindOverlappingDependenciesTest {

    @Test
    fun noOverlapReturnsEmpty() {
        val main = mapOf("com.example:a" to "1.0")
        val test = mapOf("com.example:b" to "2.0")

        val result = findOverlappingDependencies(main, test)

        assertTrue(result.isEmpty())
    }

    @Test
    fun sameVersionOverlapIsExcluded() {
        val main = mapOf("com.example:a" to "1.0")
        val test = mapOf("com.example:a" to "1.0")

        val result = findOverlappingDependencies(main, test)

        assertTrue(result.isEmpty())
    }

    @Test
    fun differentVersionOverlapIsDetected() {
        val main = mapOf("com.example:a" to "1.0")
        val test = mapOf("com.example:a" to "2.0")

        val result = findOverlappingDependencies(main, test)

        assertEquals(1, result.size)
        assertEquals("com.example:a", result[0].groupArtifact)
        assertEquals("1.0", result[0].mainVersion)
        assertEquals("2.0", result[0].testVersion)
    }

    @Test
    fun multipleOverlapsDetected() {
        val main = mapOf(
            "com.example:a" to "1.0",
            "com.example:b" to "2.0",
            "com.example:c" to "3.0"
        )
        val test = mapOf(
            "com.example:a" to "1.1",
            "com.example:b" to "2.0",
            "com.example:c" to "3.1"
        )

        val result = findOverlappingDependencies(main, test)

        assertEquals(2, result.size)
        val keys = result.map { it.groupArtifact }.toSet()
        assertTrue("com.example:a" in keys)
        assertTrue("com.example:c" in keys)
    }

    @Test
    fun emptyMapsReturnEmpty() {
        val result = findOverlappingDependencies(emptyMap(), emptyMap())

        assertTrue(result.isEmpty())
    }
}
