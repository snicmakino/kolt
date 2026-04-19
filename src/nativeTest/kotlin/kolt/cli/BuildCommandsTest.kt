package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.testConfig
import kolt.build.cinteropCommand
import kolt.build.cinteropOutputKlibPath
import kolt.build.nativeLibraryCommand
import kolt.build.nativeLinkCommand
import kolt.config.CinteropConfig
import kolt.config.KoltPaths
import kolt.infra.ProcessError
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kolt.tool.JdkBins
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

class FilterExistingDirsTest {

    @Test
    fun returnsAllPathsWhenAllExist() {
        val warnings = mutableListOf<String>()
        val result = filterExistingDirs(
            paths = listOf("a", "b"),
            kind = "test source",
            exists = { true },
            warn = { warnings.add(it) }
        )

        assertEquals(listOf("a", "b"), result)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun dropsMissingPathsAndWarnsForEach() {
        val warnings = mutableListOf<String>()
        val existing = setOf("a", "c")
        val result = filterExistingDirs(
            paths = listOf("a", "b", "c", "d"),
            kind = "test source",
            exists = { it in existing },
            warn = { warnings.add(it) }
        )

        assertEquals(listOf("a", "c"), result)
        assertEquals(
            listOf(
                "warning: test source directory \"b\" does not exist, skipping",
                "warning: test source directory \"d\" does not exist, skipping"
            ),
            warnings
        )
    }

    @Test
    fun returnsEmptyAndWarnsForEachWhenAllMissing() {
        val warnings = mutableListOf<String>()
        val result = filterExistingDirs(
            paths = listOf("a", "b"),
            kind = "resource",
            exists = { false },
            warn = { warnings.add(it) }
        )

        assertTrue(result.isEmpty())
        assertEquals(
            listOf(
                "warning: resource directory \"a\" does not exist, skipping",
                "warning: resource directory \"b\" does not exist, skipping"
            ),
            warnings
        )
    }

    @Test
    fun preservesOriginalOrder() {
        val warnings = mutableListOf<String>()
        val existing = setOf("b", "d")
        val result = filterExistingDirs(
            paths = listOf("a", "b", "c", "d"),
            kind = "test resource",
            exists = { it in existing },
            warn = { warnings.add(it) }
        )

        assertEquals(listOf("b", "d"), result)
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
            cinteropPath = paths.cinteropBin(config.kotlin.effectiveCompiler)
        )
        val klibPath = cinteropOutputKlibPath(libcurlEntry)

        assertEquals(paths.cinteropBin(config.kotlin.effectiveCompiler), cinteropCmd.args.first())
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

class RunNativeLinkWithIcFallbackTest {

    @Test
    fun successOnFirstCallSkipsWipeAndRetry() {
        var executeCalls = 0
        var wipeCalls = 0

        val result = runNativeLinkWithIcFallback(
            args = listOf("konanc"),
            execute = { executeCalls++; Ok(0) },
            wipeCache = { wipeCalls++; true }
        )

        assertTrue(result.isOk)
        assertEquals(0, result.get())
        assertEquals(1, executeCalls)
        assertEquals(0, wipeCalls)
    }

    @Test
    fun nonZeroExitTriggersWipeAndSingleRetrySucceeds() {
        var executeCalls = 0
        var wipeCalls = 0

        val result = runNativeLinkWithIcFallback(
            args = listOf("konanc"),
            execute = {
                executeCalls++
                if (executeCalls == 1) Err(ProcessError.NonZeroExit(1)) else Ok(0)
            },
            wipeCache = { wipeCalls++; true }
        )

        assertEquals(0, result.get() ?: -1)
        assertEquals(2, executeCalls)
        assertEquals(1, wipeCalls)
    }

    @Test
    fun retryFailureSurfacesRetryErrorNotOriginal() {
        var executeCalls = 0
        var wipeCalls = 0

        val result = runNativeLinkWithIcFallback(
            args = listOf("konanc"),
            execute = {
                executeCalls++
                Err(ProcessError.NonZeroExit(if (executeCalls == 1) 1 else 2))
            },
            wipeCache = { wipeCalls++; true }
        )

        assertFalse(result.isOk)
        val err = result.getError()
        assertTrue(err is ProcessError.NonZeroExit && err.exitCode == 2)
        assertEquals(2, executeCalls)
        assertEquals(1, wipeCalls)
    }

    // Fork/wait/signal failures mean konanc never ran. Retry cannot help
    // (the cache had no chance to become corrupt), so surface the error.
    @Test
    fun nonExitProcessErrorsSkipWipeAndRetry() {
        val nonExitErrors = listOf(
            ProcessError.ForkFailed,
            ProcessError.WaitFailed,
            ProcessError.SignalKilled,
            ProcessError.EmptyArgs,
        )
        for (err in nonExitErrors) {
            var executeCalls = 0
            var wipeCalls = 0

            val result = runNativeLinkWithIcFallback(
                args = listOf("konanc"),
                execute = { executeCalls++; Err(err) },
                wipeCache = { wipeCalls++; true }
            )

            assertEquals(err, result.getError(), "error=$err")
            assertEquals(1, executeCalls, "error=$err")
            assertEquals(0, wipeCalls, "error=$err")
        }
    }

    // If the wipe fails, the retry would hit the same stale cache and
    // produce an identical error — skip it and surface the first error.
    @Test
    fun wipeFailureSkipsRetryAndReturnsOriginalError() {
        var executeCalls = 0
        var wipeCalls = 0

        val result = runNativeLinkWithIcFallback(
            args = listOf("konanc"),
            execute = { executeCalls++; Err(ProcessError.NonZeroExit(1)) },
            wipeCache = { wipeCalls++; false }
        )

        assertFalse(result.isOk)
        val err = result.getError()
        assertTrue(err is ProcessError.NonZeroExit && err.exitCode == 1)
        assertEquals(1, executeCalls)
        assertEquals(1, wipeCalls)
    }
}

class NativeIcCacheLocationTest {

    // `doClean` removes BUILD_DIR wholesale, so the IC cache is wiped
    // transitively. A refactor that moves .ic-cache outside BUILD_DIR
    // would silently break the "wiped by kolt clean" contract.
    @Test
    fun icCacheLivesUnderBuildDir() {
        assertTrue(
            kolt.build.NATIVE_IC_CACHE_DIR.startsWith("${kolt.build.BUILD_DIR}/"),
            "NATIVE_IC_CACHE_DIR=${kolt.build.NATIVE_IC_CACHE_DIR} must live under BUILD_DIR=${kolt.build.BUILD_DIR}"
        )
    }
}
