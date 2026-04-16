package kolt.build

import kolt.config.CinteropConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CinteropTest {


    @Test
    fun cinteropCommandMinimalProducesDefAndOutput() {
        val entry = CinteropConfig(
            name = "libcurl",
            def = "src/nativeInterop/cinterop/libcurl.def"
        )

        val cmd = cinteropCommand(entry)

        assertEquals(
            listOf("cinterop", "-target", "linux_x64", "-def", "src/nativeInterop/cinterop/libcurl.def", "-o", "build/libcurl"),
            cmd.args
        )
    }

    @Test
    fun cinteropCommandOutputPathIsInBuildDir() {
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        val cmd = cinteropCommand(entry)

        assertEquals("build/libcurl", cmd.outputPath)
    }

    @Test
    fun cinteropCommandWithPackageNameEmitsPkgFlag() {
        val entry = CinteropConfig(
            name = "libcurl",
            def = "libcurl.def",
            packageName = "libcurl"
        )

        val cmd = cinteropCommand(entry)

        assertTrue(cmd.args.contains("-pkg"), "Expected -pkg flag in: ${cmd.args}")
        val pkgIndex = cmd.args.indexOf("-pkg")
        assertEquals("libcurl", cmd.args[pkgIndex + 1])
    }

    @Test
    fun cinteropCommandWithoutPackageNameOmitsPkgFlag() {
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def", packageName = null)

        val cmd = cinteropCommand(entry)

        assertFalse(cmd.args.contains("-pkg"), "Unexpected -pkg flag in: ${cmd.args}")
    }

    @Test
    fun cinteropCommandWithAllOptionsProducesFullArgs() {
        val entry = CinteropConfig(
            name = "libcurl",
            def = "src/nativeInterop/cinterop/libcurl.def",
            packageName = "libcurl"
        )

        val cmd = cinteropCommand(entry)

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
        val managedPath = "/home/user/.kolt/toolchains/konanc/2.1.0/bin/cinterop"
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        val cmd = cinteropCommand(entry, cinteropPath = managedPath)

        assertEquals(managedPath, cmd.args.first())
    }

    @Test
    fun cinteropCommandWithNullCinteropPathDefaultsToSystemCinterop() {
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        val cmd = cinteropCommand(entry, cinteropPath = null)

        assertEquals("cinterop", cmd.args.first())
    }

    @Test
    fun cinteropCommandWithCustomOutputDir() {
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        val cmd = cinteropCommand(entry, outputDir = "custom/build")

        val outputIndex = cmd.args.indexOf("-o")
        assertEquals("custom/build/libcurl", cmd.args[outputIndex + 1])
        assertEquals("custom/build/libcurl", cmd.outputPath)
    }

    @Test
    fun cinteropCommandOutputPathDoesNotIncludeKlibExtension() {
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        val cmd = cinteropCommand(entry)

        assertFalse(cmd.outputPath.endsWith(".klib.klib"), "outputPath must not double .klib: ${cmd.outputPath}")
        assertFalse(cmd.args.any { it.endsWith(".klib") }, "No .klib suffix expected in args: ${cmd.args}")
    }


    @Test
    fun cinteropOutputKlibPathReturnsExpectedPath() {
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")

        val path = cinteropOutputKlibPath(entry)

        assertEquals("build/libcurl.klib", path)
    }

    @Test
    fun cinteropOutputKlibPathWithCustomOutputDir() {
        val entry = CinteropConfig(name = "libssl", def = "libssl.def")

        val path = cinteropOutputKlibPath(entry, outputDir = "custom/build")

        assertEquals("custom/build/libssl.klib", path)
    }


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
        // klib format is not guaranteed compatible across Kotlin versions.
        val entry = CinteropConfig(name = "libcurl", def = "libcurl.def")
        assertFalse(cinteropStamp(entry, 1000L, "2.1.0") == cinteropStamp(entry, 1000L, "2.3.20"))
    }


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
        val curl = CinteropConfig(name = "libcurl", def = "libcurl.def")
        val ssl = CinteropConfig(name = "openssl", def = "openssl.def")

        val curlPath = cinteropOutputKlibPath(curl)
        val sslPath = cinteropOutputKlibPath(ssl)

        assertEquals("build/libcurl.klib", curlPath)
        assertEquals("build/openssl.klib", sslPath)
    }
}
