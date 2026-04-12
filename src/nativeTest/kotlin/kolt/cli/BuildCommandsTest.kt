package kolt.cli

import kolt.testConfig
import kolt.config.KoltPaths
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kolt.tool.JdkBins
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnsureJdkBinsFromConfigTest {

    @Test
    fun jdkNullInConfigReturnsNullBins() {
        // Given: config has no jdk field
        val config = testConfig(jdk = null)
        val paths = KoltPaths("/tmp/kolt_ensure_jdk_bins_null")

        // When: ensureJdkBinsFromConfig is called
        val result = ensureJdkBinsFromConfig(config, paths)

        // Then: both java and jar are null — no managed JDK
        assertNull(result.java)
        assertNull(result.jar)
    }

    @Test
    fun jdkSpecifiedAndInstalledReturnsBinPaths() {
        // Given: config specifies jdk 21 and binaries exist at the managed location
        val config = testConfig(jdk = "21")
        val paths = KoltPaths("/tmp/kolt_ensure_jdk_bins_installed")
        val binDir = "${paths.toolchainsDir}/jdk/21/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/java", "#!/bin/sh")
        writeFileAsString("$binDir/jar", "#!/bin/sh")
        try {
            // When: ensureJdkBinsFromConfig is called
            val result = ensureJdkBinsFromConfig(config, paths)

            // Then: returns managed java and jar paths
            assertEquals(paths.javaBin("21"), result.java)
            assertEquals(paths.jarBin("21"), result.jar)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }
}

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
