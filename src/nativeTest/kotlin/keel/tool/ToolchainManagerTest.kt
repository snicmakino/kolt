package keel.tool

import keel.config.KeelPaths
import keel.infra.ensureDirectoryRecursive
import keel.infra.removeDirectoryRecursive
import keel.infra.writeFileAsString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolchainManagerTest {

    // --- kotlincDownloadUrl ---

    @Test
    fun kotlincDownloadUrlHasVersionInTagAndFilename() {
        // Given: version 2.1.0
        // When: building download URL
        val url = kotlincDownloadUrl("2.1.0")

        // Then: URL uses v-prefixed tag and includes version in filename
        assertEquals(
            "https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-compiler-2.1.0.zip",
            url
        )
    }

    @Test
    fun kotlincDownloadUrlDifferentVersion() {
        // Given: version 2.3.20
        // When: building download URL
        val url = kotlincDownloadUrl("2.3.20")

        // Then: URL has correct version in both tag and filename
        assertEquals(
            "https://github.com/JetBrains/kotlin/releases/download/v2.3.20/kotlin-compiler-2.3.20.zip",
            url
        )
    }

    // --- kotlincSha256Url ---

    @Test
    fun kotlincSha256UrlIsZipUrlWithSha256Suffix() {
        // Given: version 2.1.0
        // When: building SHA256 URL
        val url = kotlincSha256Url("2.1.0")

        // Then: URL points to the .sha256 sidecar file alongside the zip
        assertEquals(
            "https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-compiler-2.1.0.zip.sha256",
            url
        )
    }

    @Test
    fun kotlincSha256UrlDifferentVersion() {
        // Given: version 2.3.20
        val url = kotlincSha256Url("2.3.20")

        assertEquals(
            "https://github.com/JetBrains/kotlin/releases/download/v2.3.20/kotlin-compiler-2.3.20.zip.sha256",
            url
        )
    }

    // --- resolveKotlincPath ---

    @Test
    fun resolveKotlincPathReturnsBinPathWhenManagedVersionInstalled() {
        // Given: managed kotlinc bin exists at paths.kotlincBin(version)
        val paths = KeelPaths("/tmp/keel_tc_resolve_installed")
        val binDir = "${paths.toolchainsDir}/kotlinc/2.1.0/bin"
        val binPath = "$binDir/kotlinc"
        ensureDirectoryRecursive(binDir)
        writeFileAsString(binPath, "#!/bin/sh")
        try {
            // When: resolveKotlincPath is called with matching version
            val result = resolveKotlincPath("2.1.0", paths)

            // Then: returns exactly paths.kotlincBin(version)
            assertEquals(paths.kotlincBin("2.1.0"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    @Test
    fun resolveKotlincPathDelegatesPathConstructionToKeelPaths() {
        // Given: a paths object and a file at the expected location
        val paths = KeelPaths("/tmp/keel_tc_resolve_delegation")
        val expectedBin = paths.kotlincBin("2.3.20")
        val binDir = expectedBin.substringBeforeLast("/")
        ensureDirectoryRecursive(binDir)
        writeFileAsString(expectedBin, "#!/bin/sh")
        try {
            // When: resolveKotlincPath is called
            val result = resolveKotlincPath("2.3.20", paths)

            // Then: returned path equals paths.kotlincBin() — no independent path construction
            assertEquals(expectedBin, result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    @Test
    fun resolveKotlincPathReturnsNullWhenToolchainsDirAbsent() {
        // Given: toolchains directory does not exist at all
        val paths = KeelPaths("/tmp/keel_tc_resolve_no_dir")

        // When: resolveKotlincPath is called
        val result = resolveKotlincPath("2.1.0", paths)

        // Then: returns null (system kotlinc fallback)
        assertNull(result)
    }

    @Test
    fun resolveKotlincPathReturnsNullWhenVersionDirExistsButBinMissing() {
        // Given: version directory exists but bin/kotlinc is absent
        val paths = KeelPaths("/tmp/keel_tc_resolve_no_bin")
        val versionDir = "${paths.toolchainsDir}/kotlinc/2.1.0"
        ensureDirectoryRecursive(versionDir)
        try {
            // When: resolveKotlincPath is called
            val result = resolveKotlincPath("2.1.0", paths)

            // Then: returns null — partial installation is not usable
            assertNull(result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    @Test
    fun resolveKotlincPathReturnsNullForDifferentInstalledVersion() {
        // Given: only version 2.3.0 is installed, not 2.1.0
        val paths = KeelPaths("/tmp/keel_tc_resolve_version_isolation")
        val binDir = "${paths.toolchainsDir}/kotlinc/2.3.0/bin"
        val binPath = "$binDir/kotlinc"
        ensureDirectoryRecursive(binDir)
        writeFileAsString(binPath, "#!/bin/sh")
        try {
            // When: resolveKotlincPath is called for 2.1.0
            val result = resolveKotlincPath("2.1.0", paths)

            // Then: returns null — version isolation must be exact
            assertNull(result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    @Test
    fun resolveKotlincPathReturnsBinPathForCorrectVersionAmongMultiple() {
        // Given: both 2.1.0 and 2.3.0 are installed
        val paths = KeelPaths("/tmp/keel_tc_resolve_multi_version")
        val bin210 = "${paths.toolchainsDir}/kotlinc/2.1.0/bin"
        val bin230 = "${paths.toolchainsDir}/kotlinc/2.3.0/bin"
        ensureDirectoryRecursive(bin210)
        ensureDirectoryRecursive(bin230)
        writeFileAsString("$bin210/kotlinc", "#!/bin/sh")
        writeFileAsString("$bin230/kotlinc", "#!/bin/sh")
        try {
            // When: resolveKotlincPath is called for 2.1.0
            val result = resolveKotlincPath("2.1.0", paths)

            // Then: returns paths.kotlincBin("2.1.0"), not 2.3.0
            assertEquals(paths.kotlincBin("2.1.0"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }
}
