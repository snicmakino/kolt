package keel.cli

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import keel.config.KeelPaths
import keel.infra.ensureDirectoryRecursive
import keel.infra.removeDirectoryRecursive
import keel.infra.writeFileAsString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FormatToolchainListTest {

    @Test
    fun noToolchainsInstalledReturnsMessage() {
        // Given: no toolchains installed
        val kotlincVersions = emptyList<String>()
        val jdkVersions = emptyList<String>()

        // When: formatting the list
        val result = formatToolchainList(kotlincVersions, jdkVersions, emptyList())

        // Then: returns "no toolchains installed" message
        assertEquals("no toolchains installed", result)
    }

    @Test
    fun onlyKotlincInstalled() {
        // Given: kotlinc versions installed
        val kotlincVersions = listOf("2.1.0", "2.3.0")
        val jdkVersions = emptyList<String>()

        // When: formatting the list
        val result = formatToolchainList(kotlincVersions, jdkVersions, emptyList())

        // Then: shows kotlinc section only with description
        val expected = """
            kotlinc (Kotlin compiler):
              2.1.0
              2.3.0
        """.trimIndent()
        assertEquals(expected, result)
    }

    @Test
    fun onlyJdkInstalled() {
        // Given: jdk versions installed
        val kotlincVersions = emptyList<String>()
        val jdkVersions = listOf("21")

        // When: formatting the list
        val result = formatToolchainList(kotlincVersions, jdkVersions, emptyList())

        // Then: shows jdk section only with description
        val expected = """
            jdk (Java Development Kit):
              21
        """.trimIndent()
        assertEquals(expected, result)
    }

    @Test
    fun bothKotlincAndJdkInstalled() {
        // Given: both kotlinc and jdk installed
        val kotlincVersions = listOf("2.1.0")
        val jdkVersions = listOf("17", "21")

        // When: formatting the list
        val result = formatToolchainList(kotlincVersions, jdkVersions, emptyList())

        // Then: shows both sections separated by blank line, with descriptions
        val expected = """
            kotlinc (Kotlin compiler):
              2.1.0

            jdk (Java Development Kit):
              17
              21
        """.trimIndent()
        assertEquals(expected, result)
    }

    @Test
    fun onlyKonancInstalled() {
        // Given: konanc versions installed
        val result = formatToolchainList(emptyList(), emptyList(), listOf("2.3.20"))

        val expected = """
            konanc (Kotlin/Native compiler):
              2.3.20
        """.trimIndent()
        assertEquals(expected, result)
    }

    @Test
    fun allThreeToolchainsInstalled() {
        // Given: kotlinc, jdk, konanc all installed
        val result = formatToolchainList(
            listOf("2.1.0"),
            listOf("21"),
            listOf("2.1.0")
        )

        val expected = """
            kotlinc (Kotlin compiler):
              2.1.0

            jdk (Java Development Kit):
              21

            konanc (Kotlin/Native compiler):
              2.1.0
        """.trimIndent()
        assertEquals(expected, result)
    }
}

class ValidateToolchainRemoveArgsTest {

    @Test
    fun validKotlincArgs() {
        // Given: valid kotlinc remove args
        val result = validateToolchainRemoveArgs(listOf("kotlinc", "2.1.0"))

        // Then: returns Ok with parsed args
        val args = assertNotNull(result.get())
        assertEquals("kotlinc", args.name)
        assertEquals("2.1.0", args.version)
    }

    @Test
    fun validJdkArgs() {
        // Given: valid jdk remove args
        val result = validateToolchainRemoveArgs(listOf("jdk", "21"))

        // Then: returns Ok with parsed args
        val args = assertNotNull(result.get())
        assertEquals("jdk", args.name)
        assertEquals("21", args.version)
    }

    @Test
    fun validKonancArgs() {
        // Given: valid konanc remove args
        val result = validateToolchainRemoveArgs(listOf("konanc", "2.3.20"))

        // Then: returns Ok with parsed args
        val args = assertNotNull(result.get())
        assertEquals("konanc", args.name)
        assertEquals("2.3.20", args.version)
    }

    @Test
    fun unknownToolchainNameReturnsError() {
        // Given: unknown toolchain name
        val result = validateToolchainRemoveArgs(listOf("foo", "1.0"))

        // Then: returns Err with message
        assertNull(result.get())
        assertEquals("error: unknown toolchain 'foo' (available: kotlinc, jdk, konanc)", result.getError())
    }

    @Test
    fun missingArgsReturnsError() {
        // Given: not enough args
        val result = validateToolchainRemoveArgs(listOf("kotlinc"))

        // Then: returns Err with usage message
        assertNull(result.get())
        assertEquals("usage: keel toolchain remove <name> <version>", result.getError())
    }

    @Test
    fun emptyArgsReturnsError() {
        // Given: no args
        val result = validateToolchainRemoveArgs(emptyList())

        // Then: returns Err with usage message
        assertNull(result.get())
        assertEquals("usage: keel toolchain remove <name> <version>", result.getError())
    }
}

class ResolveToolchainPathForRemoveTest {

    @Test
    fun kotlincInstalledReturnsKeelPathsPath() {
        // Given: kotlinc 2.1.0 is installed
        val paths = KeelPaths("/tmp/keel_tc_remove_kotlinc")
        val binDir = "${paths.toolchainsDir}/kotlinc/2.1.0/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/kotlinc", "#!/bin/sh")
        try {
            // When: resolving path for removal
            val result = resolveToolchainPathForRemove("kotlinc", "2.1.0", paths)

            // Then: returns the path from KeelPaths.kotlincPath()
            assertEquals(paths.kotlincPath("2.1.0"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    @Test
    fun jdkInstalledReturnsKeelPathsPath() {
        // Given: jdk 21 is installed
        val paths = KeelPaths("/tmp/keel_tc_remove_jdk")
        val binDir = "${paths.toolchainsDir}/jdk/21/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/java", "#!/bin/sh")
        try {
            // When: resolving path for removal
            val result = resolveToolchainPathForRemove("jdk", "21", paths)

            // Then: returns the path from KeelPaths.jdkPath()
            assertEquals(paths.jdkPath("21"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    @Test
    fun konancInstalledReturnsKeelPathsPath() {
        // Given: konanc 2.3.20 is installed
        val paths = KeelPaths("/tmp/keel_tc_remove_konanc")
        val binDir = "${paths.toolchainsDir}/konanc/2.3.20/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/konanc", "#!/bin/sh")
        try {
            val result = resolveToolchainPathForRemove("konanc", "2.3.20", paths)

            assertEquals(paths.konancPath("2.3.20"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.keel")
        }
    }

    @Test
    fun notInstalledReturnsNull() {
        // Given: kotlinc 2.1.0 is not installed
        val paths = KeelPaths("/tmp/keel_tc_remove_not_installed")

        // When: resolving path for removal
        val result = resolveToolchainPathForRemove("kotlinc", "2.1.0", paths)

        // Then: returns null
        assertNull(result)
    }
}
