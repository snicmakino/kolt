package kolt.build

import kolt.config.CinteropConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CinteropTest {

    // --- cinteropCommand ---

    @Test
    fun cinteropCommandMinimalProducesDefAndOutput() {
        // Given: a minimal cinterop entry with only name and def
        val entry = CinteropConfig(
            name = "libcurl",
            def = "src/nativeInterop/cinterop/libcurl.def"
        )

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: args contain cinterop binary, -def, and -o flags
        assertEquals(
            listOf("cinterop", "-target", "linux_x64", "-def", "src/nativeInterop/cinterop/libcurl.def", "-o", "build/libcurl"),
            cmd.args
        )
    }

    @Test
    fun cinteropCommandOutputPathIsInBuildDir() {
        // Given: an entry named "libcurl"
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: -o points to build/<name> (cinterop appends .klib itself)
        assertEquals("build/libcurl", cmd.outputPath)
    }

    @Test
    fun cinteropCommandWithPackageNameEmitsPkgFlag() {
        // Given: entry with packageName set
        val entry = CinteropConfig(
            name = "libcurl",
            def = "libcurl.def",
            packageName = "libcurl"
        )

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: -pkg flag is included
        assertTrue(cmd.args.contains("-pkg"), "Expected -pkg flag in: ${cmd.args}")
        val pkgIndex = cmd.args.indexOf("-pkg")
        assertEquals("libcurl", cmd.args[pkgIndex + 1])
    }

    @Test
    fun cinteropCommandWithoutPackageNameOmitsPkgFlag() {
        // Given: entry with no packageName
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def", packageName = null)

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: -pkg flag is absent
        assertFalse(cmd.args.contains("-pkg"), "Unexpected -pkg flag in: ${cmd.args}")
    }

    @Test
    fun cinteropCommandWithAllOptionsProducesFullArgs() {
        // Given: entry with all optional fields set
        val entry = CinteropConfig(
            name = "libcurl",
            def = "src/nativeInterop/cinterop/libcurl.def",
            packageName = "libcurl"
        )

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: full args in canonical order
        assertEquals(
            listOf(
                "cinterop",
                "-target", "linux_x64",
                "-def", "src/nativeInterop/cinterop/libcurl.def",
                "-o", "build/libcurl",
                "-pkg", "libcurl"
            ),
            cmd.args
        )
    }

    @Test
    fun cinteropCommandWithCustomCinteropPath() {
        // Given: a managed cinterop binary path
        val managedPath = "/home/user/.kolt/toolchains/konanc/2.1.0/bin/cinterop"
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        // When: command is constructed with custom cinteropPath
        val cmd = cinteropCommand(entry, cinteropPath = managedPath)

        // Then: managed path is used as the first arg, not system "cinterop"
        assertEquals(managedPath, cmd.args.first())
    }

    @Test
    fun cinteropCommandWithNullCinteropPathDefaultsToSystemCinterop() {
        // Given: no managed path (null)
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        // When: command is constructed with explicit null cinteropPath
        val cmd = cinteropCommand(entry, cinteropPath = null)

        // Then: falls back to system "cinterop"
        assertEquals("cinterop", cmd.args.first())
    }

    @Test
    fun cinteropCommandWithCustomOutputDir() {
        // Given: a custom output directory
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        // When: command is constructed with custom outputDir
        val cmd = cinteropCommand(entry, outputDir = "custom/build")

        // Then: -o uses the custom output dir
        val outputIndex = cmd.args.indexOf("-o")
        assertEquals("custom/build/libcurl", cmd.args[outputIndex + 1])
        assertEquals("custom/build/libcurl", cmd.outputPath)
    }

    @Test
    fun cinteropCommandOutputPathDoesNotIncludeKlibExtension() {
        // cinterop tool appends .klib itself; we must not double-append it
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        val cmd = cinteropCommand(entry)

        assertFalse(cmd.outputPath.endsWith(".klib.klib"), "outputPath must not double .klib: ${cmd.outputPath}")
        assertFalse(cmd.args.any { it.endsWith(".klib") }, "No .klib suffix expected in args: ${cmd.args}")
    }

    // --- cinteropOutputKlibPath ---

    @Test
    fun cinteropOutputKlibPathReturnsExpectedPath() {
        // Given: an entry named "libcurl"
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        // When: klib path is computed
        val path = cinteropOutputKlibPath(entry)

        // Then: path is build/<name>.klib
        assertEquals("build/libcurl.klib", path)
    }

    @Test
    fun cinteropOutputKlibPathWithCustomOutputDir() {
        // Given: a custom output directory
        val entry = CinteropConfig(name = "libssl", def = "libssl.def")

        // When: klib path is computed with custom dir
        val path = cinteropOutputKlibPath(entry, outputDir = "custom/build")

        // Then: path uses custom dir
        assertEquals("custom/build/libssl.klib", path)
    }

    // --- cinteropStamp ---

    @Test
    fun cinteropStampIsStableForIdenticalInputs() {
        val entry = CinteropConfig(
            name = "libcurl",
            def = "libcurl.def",
            packageName = "libcurl"
        )
        assertEquals(cinteropStamp(entry, 1000L, "2.1.0"), cinteropStamp(entry, 1000L, "2.1.0"))
    }

    @Test
    fun cinteropStampChangesWhenDefMtimeChanges() {
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")
        assertFalse(cinteropStamp(entry, 1000L, "2.1.0") == cinteropStamp(entry, 1001L, "2.1.0"))
    }

    @Test
    fun cinteropStampChangesWhenNameChanges() {
        val a = CinteropConfig(name = "libcurl", def = "libcurl.def")
        val b = CinteropConfig(name = "libssl", def = "libcurl.def")
        assertFalse(cinteropStamp(a, 1000L, "2.1.0") == cinteropStamp(b, 1000L, "2.1.0"))
    }

    @Test
    fun cinteropStampChangesWhenDefPathChanges() {
        val a = CinteropConfig(name = "libcurl", def = "a/libcurl.def")
        val b = CinteropConfig(name = "libcurl", def = "b/libcurl.def")
        assertFalse(cinteropStamp(a, 1000L, "2.1.0") == cinteropStamp(b, 1000L, "2.1.0"))
    }

    @Test
    fun cinteropStampChangesWhenPackageChanges() {
        val a = CinteropConfig(name = "libcurl", def = "libcurl.def", packageName = null)
        val b = CinteropConfig(name = "libcurl", def = "libcurl.def", packageName = "libcurl")
        assertFalse(cinteropStamp(a, 1000L, "2.1.0") == cinteropStamp(b, 1000L, "2.1.0"))
    }

    @Test
    fun cinteropStampChangesWhenKotlinVersionChanges() {
        // Bumping `kotlin = "..."` in kolt.toml switches the cinterop/konanc
        // toolchain. Kotlin/Native klib format is not guaranteed compatible
        // across versions, so a cached klib from a previous Kotlin must not
        // be reused.
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")
        assertFalse(cinteropStamp(entry, 1000L, "2.1.0") == cinteropStamp(entry, 1000L, "2.3.20"))
    }

    // --- cinteropStampPath ---

    @Test
    fun cinteropStampPathIsKlibPathPlusStamp() {
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")
        assertEquals("build/libcurl.klib.stamp", cinteropStampPath(entry))
        assertEquals("build/libcurl.klib", cinteropOutputKlibPath(entry))
    }

    @Test
    fun cinteropStampPathHonorsCustomOutputDir() {
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")
        assertEquals("custom/build/libcurl.klib.stamp", cinteropStampPath(entry, "custom/build"))
    }

    @Test
    fun cinteropOutputKlibPathUsesEntryName() {
        // Given: two entries with different names
        val curl = CinteropConfig(name = "libcurl", def = "libcurl.def")
        val ssl = CinteropConfig(name = "openssl", def = "openssl.def")

        // When: klib paths are computed
        val curlPath = cinteropOutputKlibPath(curl)
        val sslPath = cinteropOutputKlibPath(ssl)

        // Then: paths differ by name
        assertEquals("build/libcurl.klib", curlPath)
        assertEquals("build/openssl.klib", sslPath)
    }
}
