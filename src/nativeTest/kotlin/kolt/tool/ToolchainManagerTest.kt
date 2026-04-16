package kolt.tool

import com.github.michaelbull.result.get
import kolt.config.KoltPaths
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolchainManagerTest {

    @Test
    fun kotlincDownloadUrlHasVersionInTagAndFilename() {
        val url = kotlincDownloadUrl("2.1.0")

        assertEquals(
            "https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-compiler-2.1.0.zip",
            url
        )
    }

    @Test
    fun kotlincDownloadUrlDifferentVersion() {
        val url = kotlincDownloadUrl("2.3.20")

        assertEquals(
            "https://github.com/JetBrains/kotlin/releases/download/v2.3.20/kotlin-compiler-2.3.20.zip",
            url
        )
    }

    @Test
    fun kotlincSha256UrlIsZipUrlWithSha256Suffix() {
        val url = kotlincSha256Url("2.1.0")

        assertEquals(
            "https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-compiler-2.1.0.zip.sha256",
            url
        )
    }

    @Test
    fun kotlincSha256UrlDifferentVersion() {
        val url = kotlincSha256Url("2.3.20")

        assertEquals(
            "https://github.com/JetBrains/kotlin/releases/download/v2.3.20/kotlin-compiler-2.3.20.zip.sha256",
            url
        )
    }

    @Test
    fun resolveKotlincPathReturnsBinPathWhenManagedVersionInstalled() {
        val paths = KoltPaths("/tmp/kolt_tc_resolve_installed")
        val binDir = "${paths.toolchainsDir}/kotlinc/2.1.0/bin"
        val binPath = "$binDir/kotlinc"
        ensureDirectoryRecursive(binDir)
        writeFileAsString(binPath, "#!/bin/sh")
        try {
            val result = resolveKotlincPath("2.1.0", paths)

            assertEquals(paths.kotlincBin("2.1.0"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun resolveKotlincPathDelegatesPathConstructionToKoltPaths() {
        val paths = KoltPaths("/tmp/kolt_tc_resolve_delegation")
        val expectedBin = paths.kotlincBin("2.3.20")
        val binDir = expectedBin.substringBeforeLast("/")
        ensureDirectoryRecursive(binDir)
        writeFileAsString(expectedBin, "#!/bin/sh")
        try {
            val result = resolveKotlincPath("2.3.20", paths)

            assertEquals(expectedBin, result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun resolveKotlincPathReturnsNullWhenToolchainsDirAbsent() {
        val paths = KoltPaths("/tmp/kolt_tc_resolve_no_dir")

        val result = resolveKotlincPath("2.1.0", paths)

        assertNull(result)
    }

    @Test
    fun resolveKotlincPathReturnsNullWhenVersionDirExistsButBinMissing() {
        val paths = KoltPaths("/tmp/kolt_tc_resolve_no_bin")
        val versionDir = "${paths.toolchainsDir}/kotlinc/2.1.0"
        ensureDirectoryRecursive(versionDir)
        try {
            val result = resolveKotlincPath("2.1.0", paths)

            assertNull(result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun resolveKotlincPathReturnsNullForDifferentInstalledVersion() {
        val paths = KoltPaths("/tmp/kolt_tc_resolve_version_isolation")
        val binDir = "${paths.toolchainsDir}/kotlinc/2.3.0/bin"
        val binPath = "$binDir/kotlinc"
        ensureDirectoryRecursive(binDir)
        writeFileAsString(binPath, "#!/bin/sh")
        try {
            val result = resolveKotlincPath("2.1.0", paths)

            assertNull(result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun resolveKotlincPathReturnsBinPathForCorrectVersionAmongMultiple() {
        val paths = KoltPaths("/tmp/kolt_tc_resolve_multi_version")
        val bin210 = "${paths.toolchainsDir}/kotlinc/2.1.0/bin"
        val bin230 = "${paths.toolchainsDir}/kotlinc/2.3.0/bin"
        ensureDirectoryRecursive(bin210)
        ensureDirectoryRecursive(bin230)
        writeFileAsString("$bin210/kotlinc", "#!/bin/sh")
        writeFileAsString("$bin230/kotlinc", "#!/bin/sh")
        try {
            val result = resolveKotlincPath("2.1.0", paths)

            assertEquals(paths.kotlincBin("2.1.0"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun jdkDownloadUrlContainsMajorVersion() {
        val url = jdkDownloadUrl("21")

        assertEquals(
            "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse",
            url
        )
    }

    @Test
    fun jdkDownloadUrlDifferentVersion() {
        val url = jdkDownloadUrl("17")

        assertEquals(
            "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse",
            url
        )
    }

    @Test
    fun jdkMetadataUrlContainsMajorVersion() {
        val url = jdkMetadataUrl("21")

        assertEquals(
            "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse",
            url
        )
    }

    @Test
    fun jdkMetadataUrlDifferentVersion() {
        val url = jdkMetadataUrl("17")

        assertEquals(
            "https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse",
            url
        )
    }

    @Test
    fun parseJdkChecksumExtractsHashFromMetadataJson() {
        val json = """
            [{"binary":{"package":{"checksum":"ea3b9bd464d6dd253e9a7accf59f7ccd2a36e4aa69640b7251e3370caef896a4","name":"OpenJDK21U-jdk_x64_linux_hotspot_21.0.10_7.tar.gz","link":"https://example.com/jdk.tar.gz"}}}]
        """.trimIndent()

        val result = parseJdkChecksum(json)

        assertEquals("ea3b9bd464d6dd253e9a7accf59f7ccd2a36e4aa69640b7251e3370caef896a4", result)
    }

    @Test
    fun parseJdkChecksumReturnsNullOnInvalidJson() {
        val result = parseJdkChecksum("not json")

        assertNull(result)
    }

    @Test
    fun parseJdkChecksumReturnsNullOnEmptyArray() {
        val result = parseJdkChecksum("[]")

        assertNull(result)
    }

    @Test
    fun findSingleEntryReturnsTrimmedNameWhenOneEntry() {
        assertEquals("jdk-21.0.2+13", findSingleEntry("jdk-21.0.2+13\n"))
    }

    @Test
    fun findSingleEntryReturnsNullWhenMultipleEntries() {
        assertNull(findSingleEntry("jdk-21.0.2+13\nextra-dir\n"))
    }

    @Test
    fun findSingleEntryReturnsNullWhenEmpty() {
        assertNull(findSingleEntry(""))
    }

    @Test
    fun findSingleEntryReturnsNullWhenBlank() {
        assertNull(findSingleEntry("   \n  \n"))
    }

    @Test
    fun resolveJavaBinPathReturnsBinPathWhenManagedVersionInstalled() {
        val paths = KoltPaths("/tmp/kolt_tc_jdk_resolve_installed")
        val binDir = "${paths.toolchainsDir}/jdk/21/bin"
        val binPath = "$binDir/java"
        ensureDirectoryRecursive(binDir)
        writeFileAsString(binPath, "#!/bin/sh")
        try {
            val result = resolveJavaBinPath("21", paths)

            assertEquals(paths.javaBin("21"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun resolveJavaBinPathReturnsNullWhenToolchainsDirAbsent() {
        val paths = KoltPaths("/tmp/kolt_tc_jdk_resolve_no_dir")

        assertNull(resolveJavaBinPath("21", paths))
    }

    @Test
    fun resolveJavaBinPathReturnsNullWhenVersionDirExistsButBinMissing() {
        val paths = KoltPaths("/tmp/kolt_tc_jdk_resolve_no_bin")
        val versionDir = "${paths.toolchainsDir}/jdk/21"
        ensureDirectoryRecursive(versionDir)
        try {
            assertNull(resolveJavaBinPath("21", paths))
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun resolveJavaBinPathReturnsNullForDifferentInstalledVersion() {
        val paths = KoltPaths("/tmp/kolt_tc_jdk_resolve_version_isolation")
        val binDir = "${paths.toolchainsDir}/jdk/17/bin"
        val binPath = "$binDir/java"
        ensureDirectoryRecursive(binDir)
        writeFileAsString(binPath, "#!/bin/sh")
        try {
            assertNull(resolveJavaBinPath("21", paths))
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun resolveJavaBinPathDelegatesPathConstructionToKoltPaths() {
        val paths = KoltPaths("/tmp/kolt_tc_jdk_resolve_delegation")
        val expectedBin = paths.javaBin("21")
        val binDir = expectedBin.substringBeforeLast("/")
        ensureDirectoryRecursive(binDir)
        writeFileAsString(expectedBin, "#!/bin/sh")
        try {
            val result = resolveJavaBinPath("21", paths)

            assertEquals(expectedBin, result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun resolveJarBinPathReturnsBinPathWhenManagedVersionInstalled() {
        val paths = KoltPaths("/tmp/kolt_tc_jar_resolve_installed")
        val binDir = "${paths.toolchainsDir}/jdk/21/bin"
        val binPath = "$binDir/jar"
        ensureDirectoryRecursive(binDir)
        writeFileAsString(binPath, "#!/bin/sh")
        try {
            val result = resolveJarBinPath("21", paths)

            assertEquals(paths.jarBin("21"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun resolveJarBinPathReturnsNullWhenToolchainsDirAbsent() {
        val paths = KoltPaths("/tmp/kolt_tc_jar_resolve_no_dir")

        assertNull(resolveJarBinPath("21", paths))
    }

    @Test
    fun resolveJarBinPathReturnsNullWhenVersionDirExistsButBinMissing() {
        val paths = KoltPaths("/tmp/kolt_tc_jar_resolve_no_bin")
        val versionDir = "${paths.toolchainsDir}/jdk/21"
        ensureDirectoryRecursive(versionDir)
        try {
            assertNull(resolveJarBinPath("21", paths))
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun ensureKotlincBinReturnsPathWhenAlreadyInstalled() {
        val paths = KoltPaths("/tmp/kolt_tc_ensure_kotlinc_installed")
        val binDir = "${paths.toolchainsDir}/kotlinc/2.1.0/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/kotlinc", "#!/bin/sh")
        try {
            val result = ensureKotlincBin("2.1.0", paths)

            assertEquals(paths.kotlincBin("2.1.0"), result.get())
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

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

    @Test
    fun konancSha256UrlIsTarGzUrlWithSha256Suffix() {
        val url = konancSha256Url("2.1.0")

        assertEquals(
            "https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-native-prebuilt-linux-x86_64-2.1.0.tar.gz.sha256",
            url
        )
    }

    @Test
    fun resolveKonancPathReturnsBinPathWhenManagedVersionInstalled() {
        val paths = KoltPaths("/tmp/kolt_tc_konanc_resolve_installed")
        val binDir = "${paths.toolchainsDir}/konanc/2.1.0/bin"
        val binPath = "$binDir/konanc"
        ensureDirectoryRecursive(binDir)
        writeFileAsString(binPath, "#!/bin/sh")
        try {
            val result = resolveKonancPath("2.1.0", paths)

            assertEquals(paths.konancBin("2.1.0"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun resolveKonancPathReturnsNullWhenToolchainsDirAbsent() {
        val paths = KoltPaths("/tmp/kolt_tc_konanc_resolve_no_dir")

        val result = resolveKonancPath("2.1.0", paths)

        assertNull(result)
    }

    @Test
    fun resolveKonancPathReturnsNullWhenVersionDirExistsButBinMissing() {
        val paths = KoltPaths("/tmp/kolt_tc_konanc_resolve_no_bin")
        val versionDir = "${paths.toolchainsDir}/konanc/2.1.0"
        ensureDirectoryRecursive(versionDir)
        try {
            val result = resolveKonancPath("2.1.0", paths)

            assertNull(result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun resolveKonancPathReturnsNullForDifferentInstalledVersion() {
        val paths = KoltPaths("/tmp/kolt_tc_konanc_resolve_version_isolation")
        val binDir = "${paths.toolchainsDir}/konanc/2.3.0/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/konanc", "#!/bin/sh")
        try {
            val result = resolveKonancPath("2.1.0", paths)

            assertNull(result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun ensureKonancBinReturnsPathWhenAlreadyInstalled() {
        val paths = KoltPaths("/tmp/kolt_tc_ensure_konanc_installed")
        val binDir = "${paths.toolchainsDir}/konanc/2.1.0/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/konanc", "#!/bin/sh")
        try {
            val result = ensureKonancBin("2.1.0", paths)

            assertEquals(paths.konancBin("2.1.0"), result.get())
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun ensureJdkBinsReturnsPathsWhenAlreadyInstalled() {
        val paths = KoltPaths("/tmp/kolt_tc_ensure_jdk_installed")
        val binDir = "${paths.toolchainsDir}/jdk/21/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/java", "#!/bin/sh")
        writeFileAsString("$binDir/jar", "#!/bin/sh")
        try {
            val result = ensureJdkBins("21", paths)

            val bins = result.get()!!
            assertEquals(paths.javaBin("21"), bins.java)
            assertEquals(paths.jarBin("21"), bins.jar)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }
}
