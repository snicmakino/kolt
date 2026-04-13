package kolt.cli

import kolt.testConfig
import kolt.build.cinteropCommand
import kolt.build.cinteropOutputKlibPath
import kolt.build.nativeLibraryCommand
import kolt.build.nativeLinkCommand
import kolt.config.CinteropConfig
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

// Integration test: verifies the full cinterop data flow across Config → Builder → BuildCommands → KoltPaths.
// This covers the native build pipeline that a `target = "native"` project with libcurl cinterop would exercise.
class CinteropNativeBuildIntegrationTest {

    @Test
    fun nativeConfigWithCinteropPassesKlibToLibraryAndLinkCommands() {
        // Given: a native project config with a libcurl cinterop entry
        val libcurlEntry = CinteropConfig(
            name = "libcurl",
            def = "src/nativeInterop/cinterop/libcurl.def",
            packageName = "libcurl"
        )
        val config = testConfig(
            name = "myapp",
            target = "native",
            cinterop = listOf(libcurlEntry)
        )
        val paths = KoltPaths("/home/testuser")

        // When: cinterop command and klib path are derived from the config entry using KoltPaths
        val cinteropCmd = cinteropCommand(
            entry = libcurlEntry,
            cinteropPath = paths.cinteropBin(config.kotlin)
        )
        val klibPath = cinteropOutputKlibPath(libcurlEntry)

        // Then: the cinterop binary comes from the managed konanc distribution (KoltPaths → cinterop)
        assertEquals(paths.cinteropBin(config.kotlin), cinteropCmd.args.first())
        assertEquals("build/libcurl.klib", klibPath)

        // When: the cinterop klib is passed to native build commands (cinterop → nativeLibraryCommand)
        val libraryCmd = nativeLibraryCommand(config, klibs = listOf(klibPath))

        // Then: the klib appears as -l in the library command
        val libLIdx = libraryCmd.args.indexOf("-l")
        assertEquals("build/libcurl.klib", libraryCmd.args[libLIdx + 1])

        // When: the same klib is passed to the link command (cinterop → nativeLinkCommand)
        val linkCmd = nativeLinkCommand(config, klibs = listOf(klibPath))

        // Then: the klib appears as -l in the link command, and the output is the expected executable
        val linkLIdx = linkCmd.args.indexOf("-l")
        assertEquals("build/libcurl.klib", linkCmd.args[linkLIdx + 1])
        assertEquals("build/myapp.kexe", linkCmd.outputPath)
    }

    @Test
    fun cinteropOutputKlibPathIsConsistentWithCinteropCommandOutputBase() {
        // The cinterop tool appends .klib to the -o path automatically.
        // cinteropCommand.outputPath must not include .klib; cinteropOutputKlibPath adds it.
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        // When: both are computed for the same entry
        val cmd = cinteropCommand(entry)
        val klibPath = cinteropOutputKlibPath(entry)

        // Then: klibPath == cmd.outputPath + ".klib" (consistent across Build package functions)
        assertEquals("${cmd.outputPath}.klib", klibPath)
    }

    @Test
    fun nativeConfigWithMultipleCinteropEntriesProducesAllKlibsInBuildCommands() {
        // Given: a native config with two cinterop entries (libcurl + libssl)
        val libcurlEntry = CinteropConfig(name = "libcurl", def = "libcurl.def")
        val libsslEntry = CinteropConfig(name = "libssl", def = "libssl.def")
        val config = testConfig(
            target = "native",
            cinterop = listOf(libcurlEntry, libsslEntry)
        )

        // When: klib paths are computed for all entries (mirrors what runCinterop does)
        val klibPaths = config.cinterop.map { cinteropOutputKlibPath(it) }

        // Then: each entry yields its own klib path under build/
        assertEquals(listOf("build/libcurl.klib", "build/libssl.klib"), klibPaths)

        // When: native library command is built with all cinterop klibs
        val libraryCmd = nativeLibraryCommand(config, klibs = klibPaths)

        // Then: -l flag is repeated for each klib in order
        val lIndices = libraryCmd.args.indices.filter { libraryCmd.args[it] == "-l" }
        assertEquals(2, lIndices.size)
        assertEquals("build/libcurl.klib", libraryCmd.args[lIndices[0] + 1])
        assertEquals("build/libssl.klib", libraryCmd.args[lIndices[1] + 1])
    }
}
