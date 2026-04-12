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

    // --- jdkDownloadUrl ---

    @Test
    fun jdkDownloadUrlContainsMajorVersion() {
        // Given: major version 21
        // When: building JDK download URL
        val url = jdkDownloadUrl("21")

        // Then: URL contains the major version
        assertEquals(
            "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse",
            url
        )
    }

    @Test
    fun jdkDownloadUrlDifferentVersion() {
        // Given: major version 17
        val url = jdkDownloadUrl("17")

        assertEquals(
            "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse",
            url
        )
    }

    // --- jdkMetadataUrl ---

    @Test
    fun jdkMetadataUrlContainsMajorVersion() {
        // Given: major version 21
        // When: building JDK metadata URL
        val url = jdkMetadataUrl("21")

        // Then: URL points to Adoptium assets API with correct filters
        assertEquals(
            "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse",
            url
        )
    }

    @Test
    fun jdkMetadataUrlDifferentVersion() {
        // Given: major version 17
        val url = jdkMetadataUrl("17")

        assertEquals(
            "https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse",
            url
        )
    }

    // --- parseJdkChecksum ---

    @Test
    fun parseJdkChecksumExtractsHashFromMetadataJson() {
        // Given: Adoptium metadata API JSON response
        val json = """
            [{"binary":{"package":{"checksum":"ea3b9bd464d6dd253e9a7accf59f7ccd2a36e4aa69640b7251e3370caef896a4","name":"OpenJDK21U-jdk_x64_linux_hotspot_21.0.10_7.tar.gz","link":"https://example.com/jdk.tar.gz"}}}]
        """.trimIndent()

        // When: parsing the checksum
        val result = parseJdkChecksum(json)

        // Then: returns the checksum string
        assertEquals("ea3b9bd464d6dd253e9a7accf59f7ccd2a36e4aa69640b7251e3370caef896a4", result)
    }

    @Test
    fun parseJdkChecksumReturnsNullOnInvalidJson() {
        // Given: invalid JSON
        val json = "not json"

        // When: parsing the checksum
        val result = parseJdkChecksum(json)

        // Then: returns null
        assertNull(result)
    }

    @Test
    fun parseJdkChecksumReturnsNullOnEmptyArray() {
        // Given: empty JSON array
        val json = "[]"

        // When: parsing the checksum
        val result = parseJdkChecksum(json)

        // Then: returns null
        assertNull(result)
    }

    // --- findSingleEntry ---

    @Test
    fun findSingleEntryReturnsTrimmedNameWhenOneEntry() {
        // Given: ls output with a single directory name
        val lsOutput = "jdk-21.0.2+13\n"

        // When: finding the single entry
        val result = findSingleEntry(lsOutput)

        // Then: returns the trimmed directory name
        assertEquals("jdk-21.0.2+13", result)
    }

    @Test
    fun findSingleEntryReturnsNullWhenMultipleEntries() {
        // Given: ls output with multiple entries
        val lsOutput = "jdk-21.0.2+13\nextra-dir\n"

        // When: finding the single entry
        val result = findSingleEntry(lsOutput)

        // Then: returns null — ambiguous
        assertNull(result)
    }

    @Test
    fun findSingleEntryReturnsNullWhenEmpty() {
        // Given: empty ls output
        val lsOutput = ""

        // When: finding the single entry
        val result = findSingleEntry(lsOutput)

        // Then: returns null
        assertNull(result)
    }

    @Test
    fun findSingleEntryReturnsNullWhenBlank() {
        // Given: whitespace-only ls output
        val lsOutput = "   \n  \n"

        // When: finding the single entry
        val result = findSingleEntry(lsOutput)

        // Then: returns null
        assertNull(result)
    }

    // --- resolveJavaBinPath ---

    @Test
    fun resolveJavaBinPathReturnsBinPathWhenManagedVersionInstalled() {
        // Given: managed java bin exists at paths.javaBin(version)
        val paths = KeelPaths("/tmp/keel_tc_jdk_resolve_installed")
        val binDir = "${paths.toolchainsDir}/jdk/21/bin"
        val binPath = "$binDir/java"
        ensureDirectoryRecursive(binDir)
        writeFileAsString(binPath, "#!/bin/sh")
        try {
            // When: resolveJavaBinPath is called with matching version
            val result = resolveJavaBinPath("21", paths)

            // Then: returns exactly paths.javaBin(version)
            assertEquals(paths.javaBin("21"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    @Test
    fun resolveJavaBinPathReturnsNullWhenToolchainsDirAbsent() {
        // Given: toolchains directory does not exist at all
        val paths = KeelPaths("/tmp/keel_tc_jdk_resolve_no_dir")

        // When: resolveJavaBinPath is called
        val result = resolveJavaBinPath("21", paths)

        // Then: returns null (system java fallback)
        assertNull(result)
    }

    @Test
    fun resolveJavaBinPathReturnsNullWhenVersionDirExistsButBinMissing() {
        // Given: version directory exists but bin/java is absent
        val paths = KeelPaths("/tmp/keel_tc_jdk_resolve_no_bin")
        val versionDir = "${paths.toolchainsDir}/jdk/21"
        ensureDirectoryRecursive(versionDir)
        try {
            // When: resolveJavaBinPath is called
            val result = resolveJavaBinPath("21", paths)

            // Then: returns null — partial installation is not usable
            assertNull(result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    @Test
    fun resolveJavaBinPathReturnsNullForDifferentInstalledVersion() {
        // Given: only version 17 is installed, not 21
        val paths = KeelPaths("/tmp/keel_tc_jdk_resolve_version_isolation")
        val binDir = "${paths.toolchainsDir}/jdk/17/bin"
        val binPath = "$binDir/java"
        ensureDirectoryRecursive(binDir)
        writeFileAsString(binPath, "#!/bin/sh")
        try {
            // When: resolveJavaBinPath is called for 21
            val result = resolveJavaBinPath("21", paths)

            // Then: returns null — version isolation must be exact
            assertNull(result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    @Test
    fun resolveJavaBinPathDelegatesPathConstructionToKeelPaths() {
        // Given: a paths object and a file at the expected location
        val paths = KeelPaths("/tmp/keel_tc_jdk_resolve_delegation")
        val expectedBin = paths.javaBin("21")
        val binDir = expectedBin.substringBeforeLast("/")
        ensureDirectoryRecursive(binDir)
        writeFileAsString(expectedBin, "#!/bin/sh")
        try {
            // When: resolveJavaBinPath is called
            val result = resolveJavaBinPath("21", paths)

            // Then: returned path equals paths.javaBin() — no independent path construction
            assertEquals(expectedBin, result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    // --- resolveJarBinPath ---

    @Test
    fun resolveJarBinPathReturnsBinPathWhenManagedVersionInstalled() {
        // Given: managed jar bin exists at paths.jarBin(version)
        val paths = KeelPaths("/tmp/keel_tc_jar_resolve_installed")
        val binDir = "${paths.toolchainsDir}/jdk/21/bin"
        val binPath = "$binDir/jar"
        ensureDirectoryRecursive(binDir)
        writeFileAsString(binPath, "#!/bin/sh")
        try {
            // When: resolveJarBinPath is called with matching version
            val result = resolveJarBinPath("21", paths)

            // Then: returns exactly paths.jarBin(version)
            assertEquals(paths.jarBin("21"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    @Test
    fun resolveJarBinPathReturnsNullWhenToolchainsDirAbsent() {
        // Given: toolchains directory does not exist at all
        val paths = KeelPaths("/tmp/keel_tc_jar_resolve_no_dir")

        // When: resolveJarBinPath is called
        val result = resolveJarBinPath("21", paths)

        // Then: returns null (system jar fallback)
        assertNull(result)
    }

    @Test
    fun resolveJarBinPathReturnsNullWhenVersionDirExistsButBinMissing() {
        // Given: version directory exists but bin/jar is absent
        val paths = KeelPaths("/tmp/keel_tc_jar_resolve_no_bin")
        val versionDir = "${paths.toolchainsDir}/jdk/21"
        ensureDirectoryRecursive(versionDir)
        try {
            // When: resolveJarBinPath is called
            val result = resolveJarBinPath("21", paths)

            // Then: returns null — partial installation is not usable
            assertNull(result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    // --- ensureKotlincBin ---

    @Test
    fun ensureKotlincBinReturnsPathWhenAlreadyInstalled() {
        // Given: managed kotlinc 2.1.0 is already installed
        val paths = KeelPaths("/tmp/keel_tc_ensure_kotlinc_installed")
        val binDir = "${paths.toolchainsDir}/kotlinc/2.1.0/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/kotlinc", "#!/bin/sh")
        try {
            // When: ensureKotlincBin is called
            val result = ensureKotlincBin("2.1.0", paths, 1)

            // Then: returns the managed bin path without triggering download
            assertEquals(paths.kotlincBin("2.1.0"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    // --- konancDownloadUrl ---

    @Test
    fun konancDownloadUrlHasVersionInTagAndFilename() {
        val url = konancDownloadUrl("2.1.0")

        assertEquals(
            "https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-native-prebuilt-linux-x86_64-2.1.0.tar.gz",
            url
        )
    }

    @Test
    fun konancDownloadUrlDifferentVersion() {
        val url = konancDownloadUrl("2.3.20")

        assertEquals(
            "https://github.com/JetBrains/kotlin/releases/download/v2.3.20/kotlin-native-prebuilt-linux-x86_64-2.3.20.tar.gz",
            url
        )
    }

    // --- konancSha256Url ---

    @Test
    fun konancSha256UrlIsTarGzUrlWithSha256Suffix() {
        val url = konancSha256Url("2.1.0")

        assertEquals(
            "https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-native-prebuilt-linux-x86_64-2.1.0.tar.gz.sha256",
            url
        )
    }

    // --- resolveKonancPath ---

    @Test
    fun resolveKonancPathReturnsBinPathWhenManagedVersionInstalled() {
        val paths = KeelPaths("/tmp/keel_tc_konanc_resolve_installed")
        val binDir = "${paths.toolchainsDir}/konanc/2.1.0/bin"
        val binPath = "$binDir/konanc"
        ensureDirectoryRecursive(binDir)
        writeFileAsString(binPath, "#!/bin/sh")
        try {
            val result = resolveKonancPath("2.1.0", paths)

            assertEquals(paths.konancBin("2.1.0"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    @Test
    fun resolveKonancPathReturnsNullWhenToolchainsDirAbsent() {
        val paths = KeelPaths("/tmp/keel_tc_konanc_resolve_no_dir")

        val result = resolveKonancPath("2.1.0", paths)

        assertNull(result)
    }

    @Test
    fun resolveKonancPathReturnsNullWhenVersionDirExistsButBinMissing() {
        val paths = KeelPaths("/tmp/keel_tc_konanc_resolve_no_bin")
        val versionDir = "${paths.toolchainsDir}/konanc/2.1.0"
        ensureDirectoryRecursive(versionDir)
        try {
            val result = resolveKonancPath("2.1.0", paths)

            assertNull(result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    @Test
    fun resolveKonancPathReturnsNullForDifferentInstalledVersion() {
        val paths = KeelPaths("/tmp/keel_tc_konanc_resolve_version_isolation")
        val binDir = "${paths.toolchainsDir}/konanc/2.3.0/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/konanc", "#!/bin/sh")
        try {
            val result = resolveKonancPath("2.1.0", paths)

            assertNull(result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    // --- ensureKonancBin ---

    @Test
    fun ensureKonancBinReturnsPathWhenAlreadyInstalled() {
        val paths = KeelPaths("/tmp/keel_tc_ensure_konanc_installed")
        val binDir = "${paths.toolchainsDir}/konanc/2.1.0/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/konanc", "#!/bin/sh")
        try {
            val result = ensureKonancBin("2.1.0", paths, 1)

            assertEquals(paths.konancBin("2.1.0"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    // --- ensureJdkBins ---

    @Test
    fun ensureJdkBinsReturnsPathsWhenAlreadyInstalled() {
        // Given: managed jdk 21 is already installed
        val paths = KeelPaths("/tmp/keel_tc_ensure_jdk_installed")
        val binDir = "${paths.toolchainsDir}/jdk/21/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/java", "#!/bin/sh")
        writeFileAsString("$binDir/jar", "#!/bin/sh")
        try {
            // When: ensureJdkBins is called
            val result = ensureJdkBins("21", paths, 1)

            // Then: returns managed java and jar paths
            assertEquals(paths.javaBin("21"), result.java)
            assertEquals(paths.jarBin("21"), result.jar)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }
}
