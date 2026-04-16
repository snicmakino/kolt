package kolt.cli

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.config.KoltPaths
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FormatToolchainListTest {

    @Test
    fun noToolchainsInstalledReturnsMessage() {
        val result = formatToolchainList(emptyList(), emptyList(), emptyList())

        assertEquals("no toolchains installed", result)
    }

    @Test
    fun onlyKotlincInstalled() {
        val result = formatToolchainList(listOf("2.1.0", "2.3.0"), emptyList(), emptyList())

        val expected = """
            kotlinc (Kotlin compiler):
              2.1.0
              2.3.0
        """.trimIndent()
        assertEquals(expected, result)
    }

    @Test
    fun onlyJdkInstalled() {
        val result = formatToolchainList(emptyList(), listOf("21"), emptyList())

        val expected = """
            jdk (Java Development Kit):
              21
        """.trimIndent()
        assertEquals(expected, result)
    }

    @Test
    fun bothKotlincAndJdkInstalled() {
        val result = formatToolchainList(listOf("2.1.0"), listOf("17", "21"), emptyList())

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
        val result = formatToolchainList(emptyList(), emptyList(), listOf("2.3.20"))

        val expected = """
            konanc (Kotlin/Native compiler):
              2.3.20
        """.trimIndent()
        assertEquals(expected, result)
    }

    @Test
    fun allThreeToolchainsInstalled() {
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
        val result = validateToolchainRemoveArgs(listOf("kotlinc", "2.1.0"))

        val args = assertNotNull(result.get())
        assertEquals("kotlinc", args.name)
        assertEquals("2.1.0", args.version)
    }

    @Test
    fun validJdkArgs() {
        val result = validateToolchainRemoveArgs(listOf("jdk", "21"))

        val args = assertNotNull(result.get())
        assertEquals("jdk", args.name)
        assertEquals("21", args.version)
    }

    @Test
    fun validKonancArgs() {
        val result = validateToolchainRemoveArgs(listOf("konanc", "2.3.20"))

        val args = assertNotNull(result.get())
        assertEquals("konanc", args.name)
        assertEquals("2.3.20", args.version)
    }

    @Test
    fun unknownToolchainNameReturnsError() {
        val result = validateToolchainRemoveArgs(listOf("foo", "1.0"))

        assertNull(result.get())
        assertEquals("error: unknown toolchain 'foo' (available: kotlinc, jdk, konanc)", result.getError())
    }

    @Test
    fun missingArgsReturnsError() {
        val result = validateToolchainRemoveArgs(listOf("kotlinc"))

        assertNull(result.get())
        assertEquals("usage: kolt toolchain remove <name> <version>", result.getError())
    }

    @Test
    fun emptyArgsReturnsError() {
        val result = validateToolchainRemoveArgs(emptyList())

        assertNull(result.get())
        assertEquals("usage: kolt toolchain remove <name> <version>", result.getError())
    }
}

class ResolveToolchainPathForRemoveTest {

    @Test
    fun kotlincInstalledReturnsKoltPathsPath() {
        val paths = KoltPaths("/tmp/kolt_tc_remove_kotlinc")
        val binDir = "${paths.toolchainsDir}/kotlinc/2.1.0/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/kotlinc", "#!/bin/sh")
        try {
            val result = resolveToolchainPathForRemove("kotlinc", "2.1.0", paths)

            assertEquals(paths.kotlincPath("2.1.0"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun jdkInstalledReturnsKoltPathsPath() {
        val paths = KoltPaths("/tmp/kolt_tc_remove_jdk")
        val binDir = "${paths.toolchainsDir}/jdk/21/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/java", "#!/bin/sh")
        try {
            val result = resolveToolchainPathForRemove("jdk", "21", paths)

            assertEquals(paths.jdkPath("21"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun konancInstalledReturnsKoltPathsPath() {
        val paths = KoltPaths("/tmp/kolt_tc_remove_konanc")
        val binDir = "${paths.toolchainsDir}/konanc/2.3.20/bin"
        ensureDirectoryRecursive(binDir)
        writeFileAsString("$binDir/konanc", "#!/bin/sh")
        try {
            val result = resolveToolchainPathForRemove("konanc", "2.3.20", paths)

            assertEquals(paths.konancPath("2.3.20"), result)
        } finally {
            removeDirectoryRecursive(paths.home + "/.kolt")
        }
    }

    @Test
    fun notInstalledReturnsNull() {
        val paths = KoltPaths("/tmp/kolt_tc_remove_not_installed")

        val result = resolveToolchainPathForRemove("kotlinc", "2.1.0", paths)

        assertNull(result)
    }
}
