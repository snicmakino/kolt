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
            listOf("cinterop", "-def", "src/nativeInterop/cinterop/libcurl.def", "-o", "build/libcurl"),
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
    fun cinteropCommandWithSingleCompilerOptionEmitsOneCompilerOptionFlag() {
        // Given: entry with one compilerOptions element
        val entry = CinteropConfig(
            name = "libcurl",
            def = "libcurl.def",
            compilerOptions = listOf("-I/usr/include")
        )

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: a single -compiler-option flag is included with the value
        assertEquals(1, cmd.args.count { it == "-compiler-option" })
        val flagIndex = cmd.args.indexOf("-compiler-option")
        assertEquals("-I/usr/include", cmd.args[flagIndex + 1])
    }

    @Test
    fun cinteropCommandWithMultipleCompilerOptionsEmitsRepeatedCompilerOptionFlags() {
        // Given: entry with multiple compilerOptions elements
        val entry = CinteropConfig(
            name = "libcurl",
            def = "libcurl.def",
            compilerOptions = listOf("-I/usr/include", "-I/usr/include/x86_64-linux-gnu", "-DFOO=1")
        )

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: -compiler-option is repeated once per element, preserving order
        val pairs = cmd.args.zipWithNext().filter { it.first == "-compiler-option" }.map { it.second }
        assertEquals(
            listOf("-I/usr/include", "-I/usr/include/x86_64-linux-gnu", "-DFOO=1"),
            pairs
        )
    }

    @Test
    fun cinteropCommandWithEmptyCompilerOptionsOmitsFlag() {
        // Given: entry with empty compilerOptions list (the default)
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: no -compiler-option flag is emitted
        assertFalse(cmd.args.contains("-compiler-option"), "Unexpected -compiler-option flag in: ${cmd.args}")
    }

    @Test
    fun cinteropCommandWithSingleLinkerOptionEmitsOneLinkerOptionFlag() {
        // Given: entry with one linkerOptions element
        val entry = CinteropConfig(
            name = "libcurl",
            def = "libcurl.def",
            linkerOptions = listOf("-lcurl")
        )

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: a single -linker-option flag is included with the value
        assertEquals(1, cmd.args.count { it == "-linker-option" })
        val flagIndex = cmd.args.indexOf("-linker-option")
        assertEquals("-lcurl", cmd.args[flagIndex + 1])
    }

    @Test
    fun cinteropCommandWithMultipleLinkerOptionsEmitsRepeatedLinkerOptionFlags() {
        // Given: entry with multiple linkerOptions elements
        val entry = CinteropConfig(
            name = "libcurl",
            def = "libcurl.def",
            linkerOptions = listOf("-L/usr/lib/x86_64-linux-gnu", "-lcurl")
        )

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: -linker-option is repeated once per element, preserving order
        val pairs = cmd.args.zipWithNext().filter { it.first == "-linker-option" }.map { it.second }
        assertEquals(listOf("-L/usr/lib/x86_64-linux-gnu", "-lcurl"), pairs)
    }

    @Test
    fun cinteropCommandWithEmptyLinkerOptionsOmitsFlag() {
        // Given: entry with empty linkerOptions list (the default)
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: no -linker-option flag is emitted
        assertFalse(cmd.args.contains("-linker-option"), "Unexpected -linker-option flag in: ${cmd.args}")
    }

    @Test
    fun cinteropCommandWithAllOptionsProducesFullArgs() {
        // Given: entry with all optional fields set
        val entry = CinteropConfig(
            name = "libcurl",
            def = "src/nativeInterop/cinterop/libcurl.def",
            packageName = "libcurl",
            compilerOptions = listOf("-I/usr/include", "-DFOO=1"),
            linkerOptions = listOf("-L/usr/lib", "-lcurl")
        )

        // When: command is constructed
        val cmd = cinteropCommand(entry)

        // Then: full args in canonical order, with repeated singular flags
        assertEquals(
            listOf(
                "cinterop",
                "-def", "src/nativeInterop/cinterop/libcurl.def",
                "-o", "build/libcurl",
                "-pkg", "libcurl",
                "-compiler-option", "-I/usr/include",
                "-compiler-option", "-DFOO=1",
                "-linker-option", "-L/usr/lib",
                "-linker-option", "-lcurl"
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
            packageName = "libcurl",
            compilerOptions = listOf("-I/usr/include"),
            linkerOptions = listOf("-lcurl")
        )
        assertEquals(cinteropStamp(entry, 1000L), cinteropStamp(entry, 1000L))
    }

    @Test
    fun cinteropStampChangesWhenDefMtimeChanges() {
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")
        assertFalse(cinteropStamp(entry, 1000L) == cinteropStamp(entry, 1001L))
    }

    @Test
    fun cinteropStampChangesWhenNameChanges() {
        val a = CinteropConfig(name = "libcurl", def = "libcurl.def")
        val b = CinteropConfig(name = "libssl", def = "libcurl.def")
        assertFalse(cinteropStamp(a, 1000L) == cinteropStamp(b, 1000L))
    }

    @Test
    fun cinteropStampChangesWhenDefPathChanges() {
        val a = CinteropConfig(name = "libcurl", def = "a/libcurl.def")
        val b = CinteropConfig(name = "libcurl", def = "b/libcurl.def")
        assertFalse(cinteropStamp(a, 1000L) == cinteropStamp(b, 1000L))
    }

    @Test
    fun cinteropStampChangesWhenPackageChanges() {
        val a = CinteropConfig(name = "libcurl", def = "libcurl.def", packageName = null)
        val b = CinteropConfig(name = "libcurl", def = "libcurl.def", packageName = "libcurl")
        assertFalse(cinteropStamp(a, 1000L) == cinteropStamp(b, 1000L))
    }

    @Test
    fun cinteropStampChangesWhenCompilerOptionsChange() {
        // This is the subtle case #68 calls out: .def untouched but
        // compiler_options edited in kolt.toml — stamp MUST differ.
        val a = CinteropConfig(
            name = "libcurl", def = "libcurl.def",
            compilerOptions = listOf("-I/foo")
        )
        val b = CinteropConfig(
            name = "libcurl", def = "libcurl.def",
            compilerOptions = listOf("-I/bar")
        )
        assertFalse(cinteropStamp(a, 1000L) == cinteropStamp(b, 1000L))
    }

    @Test
    fun cinteropStampChangesWhenLinkerOptionsChange() {
        val a = CinteropConfig(
            name = "libcurl", def = "libcurl.def",
            linkerOptions = listOf("-lcurl")
        )
        val b = CinteropConfig(
            name = "libcurl", def = "libcurl.def",
            linkerOptions = listOf("-lcurl-gnutls")
        )
        assertFalse(cinteropStamp(a, 1000L) == cinteropStamp(b, 1000L))
    }

    @Test
    fun cinteropStampIsSensitiveToOptionOrder() {
        // Reordering compiler options can change clang's include search order
        // and therefore the resolved header set. Treat as a semantic change.
        val a = CinteropConfig(
            name = "libcurl", def = "libcurl.def",
            compilerOptions = listOf("-I/foo", "-I/bar")
        )
        val b = CinteropConfig(
            name = "libcurl", def = "libcurl.def",
            compilerOptions = listOf("-I/bar", "-I/foo")
        )
        assertFalse(cinteropStamp(a, 1000L) == cinteropStamp(b, 1000L))
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
