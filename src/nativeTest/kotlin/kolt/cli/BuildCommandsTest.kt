package kolt.cli

import com.github.michaelbull.result.get
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnsureJdkBinsFromConfigTest {

    @Test
    fun jdkNullInConfigReturnsNullBins() {
        val config = testConfig(jdk = null)
        val paths = KoltPaths("/tmp/kolt_ensure_jdk_bins_null")

        val result = assertNotNull(ensureJdkBinsFromConfig(config, paths).get())

        assertNull(result.java)
        assertNull(result.jar)
    }

    @Test
    fun jdkSpecifiedAndInstalledReturnsBinPaths() {
        val config = testConfig(jdk = "21")
        val paths = KoltPaths("/tmp/kolt_ensure_jdk_bins_installed")
        val binDir = "${paths.toolchainsDir}/jdk/21/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/java", "#!/bin/sh")
        writeFileAsString("$binDir/jar", "#!/bin/sh")
        try {
            val result = assertNotNull(ensureJdkBinsFromConfig(config, paths).get())

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

class CinteropNativeBuildIntegrationTest {

    @Test
    fun nativeConfigWithCinteropPassesKlibToLibraryAndLinkCommands() {
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

        val cinteropCmd = cinteropCommand(
            entry = libcurlEntry,
            cinteropPath = paths.cinteropBin(config.kotlin)
        )
        val klibPath = cinteropOutputKlibPath(libcurlEntry)

        assertEquals(paths.cinteropBin(config.kotlin), cinteropCmd.args.first())
        assertEquals("build/libcurl.klib", klibPath)

        val libraryCmd = nativeLibraryCommand(config, klibs = listOf(klibPath))
        val libLIdx = libraryCmd.args.indexOf("-l")
        assertEquals("build/libcurl.klib", libraryCmd.args[libLIdx + 1])

        val linkCmd = nativeLinkCommand(config, klibs = listOf(klibPath))
        val linkLIdx = linkCmd.args.indexOf("-l")
        assertEquals("build/libcurl.klib", linkCmd.args[linkLIdx + 1])
        assertEquals("build/myapp.kexe", linkCmd.outputPath)
    }

    @Test
    fun cinteropOutputKlibPathIsConsistentWithCinteropCommandOutputBase() {
        // cinterop tool appends .klib to -o automatically; cinteropCommand.outputPath omits it.
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        val cmd = cinteropCommand(entry)
        val klibPath = cinteropOutputKlibPath(entry)

        assertEquals("${cmd.outputPath}.klib", klibPath)
    }

    @Test
    fun nativeConfigWithMultipleCinteropEntriesProducesAllKlibsInBuildCommands() {
        val libcurlEntry = CinteropConfig(name = "libcurl", def = "libcurl.def")
        val libsslEntry = CinteropConfig(name = "libssl", def = "libssl.def")
        val config = testConfig(
            target = "native",
            cinterop = listOf(libcurlEntry, libsslEntry)
        )

        val klibPaths = config.cinterop.map { cinteropOutputKlibPath(it) }
        assertEquals(listOf("build/libcurl.klib", "build/libssl.klib"), klibPaths)

        val libraryCmd = nativeLibraryCommand(config, klibs = klibPaths)
        val lIndices = libraryCmd.args.indices.filter { libraryCmd.args[it] == "-l" }
        assertEquals(2, lIndices.size)
        assertEquals("build/libcurl.klib", libraryCmd.args[lIndices[0] + 1])
        assertEquals("build/libssl.klib", libraryCmd.args[lIndices[1] + 1])
    }
}
