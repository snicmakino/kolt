package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.testConfig
import kolt.build.NativeCompileError
import kolt.build.NativeCompileOutcome
import kolt.build.NativeCompilerBackend
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
import kotlin.test.assertIs
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
            target = "linuxX64",
            cinterop = listOf(libcurlEntry)
        )
        val paths = KoltPaths("/home/testuser")

        val cinteropCmd = cinteropCommand(
            entry = libcurlEntry,
            target = config.build.target,
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

        val cmd = cinteropCommand(entry, target = "linuxX64")
        val klibPath = cinteropOutputKlibPath(entry)

        assertEquals("${cmd.outputPath}.klib", klibPath)
    }

    @Test
    fun nativeConfigWithMultipleCinteropEntriesProducesAllKlibsInBuildCommands() {
        val libcurlEntry = CinteropConfig(name = "libcurl", def = "libcurl.def")
        val libsslEntry = CinteropConfig(name = "libssl", def = "libssl.def")
        val config = testConfig(
            target = "linuxX64",
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

    private class StubBackend(
        private val replies: List<Result<NativeCompileOutcome, NativeCompileError>>,
    ) : NativeCompilerBackend {
        var calls: Int = 0
            private set
        override fun compile(args: List<String>): Result<NativeCompileOutcome, NativeCompileError> {
            val reply = replies[calls.coerceAtMost(replies.size - 1)]
            calls++
            return reply
        }
    }

    @Test
    fun successOnFirstCallSkipsWipeAndRetry() {
        val backend = StubBackend(listOf(Ok(NativeCompileOutcome(stderr = ""))))
        var wipeCalls = 0

        val result = runNativeLinkWithIcFallback(
            backend = backend,
            args = listOf("-target", "linux_x64"),
            wipeCache = { wipeCalls++; true },
        )

        assertTrue(result.isOk)
        assertEquals(1, backend.calls)
        assertEquals(0, wipeCalls)
    }

    @Test
    fun compilationFailedTriggersWipeAndSingleRetrySucceeds() {
        val backend = StubBackend(
            listOf(
                Err(NativeCompileError.CompilationFailed(exitCode = 1, stderr = "stale cache")),
                Ok(NativeCompileOutcome(stderr = "")),
            ),
        )
        var wipeCalls = 0

        val result = runNativeLinkWithIcFallback(
            backend = backend,
            args = listOf("-target", "linux_x64"),
            wipeCache = { wipeCalls++; true },
        )

        assertTrue(result.isOk)
        assertEquals(2, backend.calls)
        assertEquals(1, wipeCalls)
    }

    @Test
    fun retryFailureSurfacesRetryErrorNotOriginal() {
        val backend = StubBackend(
            listOf(
                Err(NativeCompileError.CompilationFailed(exitCode = 1, stderr = "first")),
                Err(NativeCompileError.CompilationFailed(exitCode = 2, stderr = "second")),
            ),
        )

        val result = runNativeLinkWithIcFallback(
            backend = backend,
            args = listOf("-target", "linux_x64"),
            wipeCache = { true },
        )

        val err = assertIs<NativeCompileError.CompilationFailed>(result.getError())
        assertEquals(2, err.exitCode)
        assertEquals(2, backend.calls)
    }

    // BackendUnavailable / InternalMisuse / NoCommand never reach here as
    // the retry target, because they are either fallback-eligible (and
    // handled by FallbackNativeCompilerBackend upstream) or genuinely
    // unrecoverable. Only CompilationFailed can be cache-stale.
    @Test
    fun nonCompilationFailedErrorsSkipWipeAndRetry() {
        val errors: List<NativeCompileError> = listOf(
            NativeCompileError.BackendUnavailable.ForkFailed,
            NativeCompileError.BackendUnavailable.Other("subprocess popen failed"),
            NativeCompileError.NoCommand,
            NativeCompileError.InternalMisuse("socket path too long"),
        )
        for (err in errors) {
            val backend = StubBackend(listOf(Err(err)))
            var wipeCalls = 0

            val result = runNativeLinkWithIcFallback(
                backend = backend,
                args = listOf("-target", "linux_x64"),
                wipeCache = { wipeCalls++; true },
            )

            assertEquals(err, result.getError(), "error=$err")
            assertEquals(1, backend.calls, "error=$err")
            assertEquals(0, wipeCalls, "error=$err")
        }
    }

    @Test
    fun wipeFailureSkipsRetryAndReturnsOriginalError() {
        val backend = StubBackend(
            listOf(Err(NativeCompileError.CompilationFailed(exitCode = 1, stderr = "stale cache"))),
        )

        val result = runNativeLinkWithIcFallback(
            backend = backend,
            args = listOf("-target", "linux_x64"),
            wipeCache = { false },
        )

        val err = assertIs<NativeCompileError.CompilationFailed>(result.getError())
        assertEquals(1, err.exitCode)
        assertEquals(1, backend.calls)
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
