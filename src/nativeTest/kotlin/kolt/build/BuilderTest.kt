package kolt.build

import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuilderTest {

    @Test
    fun checkCommandDoesNotIncludeRuntimeOrOutput() {
        val cmd = checkCommand(testConfig())

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "17"),
            cmd
        )
    }

    @Test
    fun checkCommandWithClasspath() {
        val cmd = checkCommand(testConfig(), classpath = "/cache/a.jar")

        assertEquals(
            listOf("kotlinc", "-cp", "/cache/a.jar", "src", "-jvm-target", "17"),
            cmd
        )
    }

    @Test
    fun checkCommandWithPluginArgs() {
        val pluginArgs = listOf("-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar")

        val cmd = checkCommand(testConfig(), pluginArgs = pluginArgs)

        assertEquals(
            listOf(
                "kotlinc", "src", "-jvm-target", "17",
                "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"
            ),
            cmd
        )
    }

    @Test
    fun checkCommandWithEmptyPluginArgs() {
        val cmd = checkCommand(testConfig(), pluginArgs = emptyList())

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "17"),
            cmd
        )
    }

    @Test
    fun checkCommandWithManagedKotlincPathUsesItAsCompiler() {
        val managedKotlincBin = "/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc"

        val cmd = checkCommand(testConfig(), kotlincPath = managedKotlincBin)

        assertEquals(
            listOf(managedKotlincBin, "src", "-jvm-target", "17"),
            cmd
        )
    }

    @Test
    fun checkCommandWithNullKotlincPathDefaultsToSystemKotlinc() {
        val cmd = checkCommand(testConfig(), kotlincPath = null)

        assertEquals("kotlinc", cmd.first())
    }

    @Test
    fun checkCommandWithManagedKotlincAndClasspath() {
        val managedKotlincBin = "/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc"

        val cmd = checkCommand(testConfig(), classpath = "/cache/a.jar", kotlincPath = managedKotlincBin)

        assertEquals(
            listOf(managedKotlincBin, "-cp", "/cache/a.jar", "src", "-jvm-target", "17"),
            cmd
        )
    }

    @Test
    fun jarCommandPackagesClassesDirToJar() {
        val cmd = jarCommand(testConfig())

        assertEquals(
            listOf("jar", "cf", "build/my-app.jar", "-C", "build/classes", "."),
            cmd.args
        )
        assertEquals("build/my-app.jar", cmd.outputPath)
    }

    @Test
    fun jarCommandOutputPathUsesProjectName() {
        val cmd = jarCommand(testConfig(name = "hello-world"))

        assertEquals("build/hello-world.jar", cmd.outputPath)
        assertEquals(
            listOf("jar", "cf", "build/hello-world.jar", "-C", "build/classes", "."),
            cmd.args
        )
    }

    @Test
    fun jarCommandSourceDirectoryIsAlwaysClassesDir() {
        val cmd1 = jarCommand(testConfig(name = "app-a"))
        val cmd2 = jarCommand(testConfig(name = "app-b"))

        assertEquals("build/classes", cmd1.args[4])
        assertEquals("build/classes", cmd2.args[4])
    }

    @Test
    fun jarCommandWithManagedJarPathUsesItAsJar() {
        val managedJarBin = "/home/user/.kolt/toolchains/jdk/21/bin/jar"

        val cmd = jarCommand(testConfig(), jarPath = managedJarBin)

        assertEquals(
            listOf(managedJarBin, "cf", "build/my-app.jar", "-C", "build/classes", "."),
            cmd.args
        )
    }

    @Test
    fun jarCommandWithNullJarPathDefaultsToSystemJar() {
        val cmd = jarCommand(testConfig(), jarPath = null)

        assertEquals("jar", cmd.args.first())
    }

    @Test
    fun nativeLibraryCommandProducesKlibDirectory() {
        val cmd = nativeLibraryCommand(testConfig(target = "native"))

        assertEquals(
            listOf("konanc", "-target", "linux_x64", "src", "-p", "library", "-nopack", "-o", "build/my-app-klib"),
            cmd.args
        )
        assertEquals("build/my-app-klib", cmd.outputPath)
    }

    @Test
    fun nativeLibraryCommandMultipleSources() {
        val cmd = nativeLibraryCommand(
            testConfig(sources = listOf("src", "generated"), target = "native")
        )

        assertEquals(
            listOf("konanc", "-target", "linux_x64", "src", "generated", "-p", "library", "-nopack", "-o", "build/my-app-klib"),
            cmd.args
        )
    }

    @Test
    fun nativeLibraryCommandWithProjectName() {
        val cmd = nativeLibraryCommand(testConfig(name = "hello", target = "native"))

        assertEquals("build/hello-klib", cmd.outputPath)
        assertEquals(
            listOf("konanc", "-target", "linux_x64", "src", "-p", "library", "-nopack", "-o", "build/hello-klib"),
            cmd.args
        )
    }

    @Test
    fun nativeLibraryCommandWithManagedKonancPath() {
        val managedKonanc = "/home/user/.kolt/toolchains/konanc/2.1.0/bin/konanc"
        val cmd = nativeLibraryCommand(testConfig(target = "native"), konancPath = managedKonanc)

        assertEquals(managedKonanc, cmd.args.first())
    }

    @Test
    fun nativeLibraryCommandWithPluginArgs() {
        val cmd = nativeLibraryCommand(
            testConfig(target = "native"),
            pluginArgs = listOf("-Xplugin=foo.jar")
        )

        assertEquals(
            listOf(
                "konanc", "-target", "linux_x64", "src",
                "-p", "library", "-nopack",
                "-o", "build/my-app-klib",
                "-Xplugin=foo.jar"
            ),
            cmd.args
        )
    }

    @Test
    fun nativeLibraryCommandWithMultipleKlibsRepeatsLFlag() {
        val cmd = nativeLibraryCommand(
            testConfig(target = "native"),
            klibs = listOf("/cache/a.klib", "/cache/b.klib", "/cache/c.klib")
        )

        assertEquals(
            listOf(
                "konanc", "-target", "linux_x64", "src",
                "-p", "library", "-nopack",
                "-l", "/cache/a.klib",
                "-l", "/cache/b.klib",
                "-l", "/cache/c.klib",
                "-o", "build/my-app-klib"
            ),
            cmd.args
        )
    }

    @Test
    fun nativeLibraryCommandEmptyKlibsOmitsLibraryFlag() {
        val cmd = nativeLibraryCommand(testConfig(target = "native"), klibs = emptyList())

        assertFalse(cmd.args.contains("-l"))
    }

    @Test
    fun nativeLinkCommandLinksKlibToProgramKexe() {
        val cmd = nativeLinkCommand(testConfig(target = "native"))

        assertEquals(
            listOf(
                "konanc",
                "-target", "linux_x64",
                "-p", "program",
                "-e", "com.example.main",
                "-Xinclude=build/my-app-klib",
                "-o", "build/my-app"
            ),
            cmd.args
        )
        assertEquals("build/my-app.kexe", cmd.outputPath)
    }

    @Test
    fun nativeLinkCommandWithProjectName() {
        val cmd = nativeLinkCommand(testConfig(name = "hello", target = "native"))

        assertEquals("build/hello.kexe", cmd.outputPath)
        assertEquals(
            listOf(
                "konanc",
                "-target", "linux_x64",
                "-p", "program",
                "-e", "com.example.main",
                "-Xinclude=build/hello-klib",
                "-o", "build/hello"
            ),
            cmd.args
        )
    }

    @Test
    fun nativeLinkCommandWithManagedKonancPath() {
        val managedKonanc = "/home/user/.kolt/toolchains/konanc/2.1.0/bin/konanc"
        val cmd = nativeLinkCommand(testConfig(target = "native"), konancPath = managedKonanc)

        assertEquals(managedKonanc, cmd.args.first())
    }

    @Test
    fun nativeLinkCommandWithMultipleKlibsRepeatsLFlag() {
        // -Xinclude only pulls the project klib's IR into the link unit; any
        // external references (e.g. kotlinx-serialization-core symbols touched
        // by plugin-generated code) are still unresolved and need the
        // transitive klibs on the library path at link time.
        val cmd = nativeLinkCommand(
            testConfig(target = "native"),
            klibs = listOf("/cache/a.klib", "/cache/b.klib")
        )

        assertEquals(
            listOf(
                "konanc",
                "-target", "linux_x64",
                "-p", "program",
                "-e", "com.example.main",
                "-l", "/cache/a.klib",
                "-l", "/cache/b.klib",
                "-Xinclude=build/my-app-klib",
                "-o", "build/my-app"
            ),
            cmd.args
        )
    }

    @Test
    fun nativeLinkCommandEmptyKlibsOmitsLibraryFlag() {
        val cmd = nativeLinkCommand(testConfig(target = "native"), klibs = emptyList())

        assertFalse(cmd.args.contains("-l"))
    }

    @Test
    fun nativeLinkCommandEmitsEntryPointFromConfigMain() {
        // config.main is already a Kotlin function FQN (validated by parseConfig),
        // so konanc -e consumes it verbatim. Without -e, konanc looks for `main`
        // in the root package and fails when the function lives in a named package.
        val cmd = nativeLinkCommand(testConfig(target = "native"))

        val eIndex = cmd.args.indexOf("-e")
        assertTrue(eIndex >= 0, "Expected -e in: ${cmd.args}")
        assertEquals("com.example.main", cmd.args[eIndex + 1])
    }

    @Test
    fun nativeLinkCommandEmitsRootPackageEntryPoint() {
        val base = testConfig(target = "native")
        val cmd = nativeLinkCommand(base.copy(build = base.build.copy(main = "main")))

        val eIndex = cmd.args.indexOf("-e")
        assertTrue(eIndex >= 0)
        assertEquals("main", cmd.args[eIndex + 1])
    }

    @Test
    fun nativeTestLibraryCommandIncludesMainAndTestSources() {
        val cmd = nativeTestLibraryCommand(testConfig(target = "native"))

        assertEquals(
            listOf(
                "konanc",
                "-target", "linux_x64",
                "src", "test",
                "-p", "library", "-nopack",
                "-o", "build/my-app-test-klib"
            ),
            cmd.args
        )
        assertEquals("build/my-app-test-klib", cmd.outputPath)
    }

    @Test
    fun nativeTestLibraryCommandMultipleSources() {
        val cmd = nativeTestLibraryCommand(
            testConfig(
                sources = listOf("src", "generated"),
                testSources = listOf("test", "integration-test"),
                target = "native"
            )
        )

        assertEquals(
            listOf(
                "konanc",
                "-target", "linux_x64",
                "src", "generated", "test", "integration-test",
                "-p", "library", "-nopack",
                "-o", "build/my-app-test-klib"
            ),
            cmd.args
        )
    }

    @Test
    fun nativeTestLibraryCommandWithPluginArgs() {
        val cmd = nativeTestLibraryCommand(
            testConfig(target = "native"),
            pluginArgs = listOf("-Xplugin=foo.jar", "-Xplugin=bar.jar")
        )

        assertEquals(
            listOf(
                "konanc",
                "-target", "linux_x64",
                "src", "test",
                "-p", "library", "-nopack",
                "-o", "build/my-app-test-klib",
                "-Xplugin=foo.jar", "-Xplugin=bar.jar"
            ),
            cmd.args
        )
    }

    @Test
    fun nativeTestLibraryCommandWithMultipleKlibsRepeatsLFlag() {
        val cmd = nativeTestLibraryCommand(
            testConfig(target = "native"),
            klibs = listOf("/cache/a.klib", "/cache/b.klib")
        )

        assertEquals(
            listOf(
                "konanc",
                "-target", "linux_x64",
                "src", "test",
                "-p", "library", "-nopack",
                "-l", "/cache/a.klib",
                "-l", "/cache/b.klib",
                "-o", "build/my-app-test-klib"
            ),
            cmd.args
        )
    }

    @Test
    fun nativeTestLibraryCommandWithManagedKonancPath() {
        val managedKonanc = "/home/user/.kolt/toolchains/konanc/2.1.0/bin/konanc"
        val cmd = nativeTestLibraryCommand(testConfig(target = "native"), konancPath = managedKonanc)

        assertEquals(managedKonanc, cmd.args.first())
    }

    @Test
    fun nativeTestLinkCommandLinksWithGeneratedRunner() {
        val cmd = nativeTestLinkCommand(testConfig(target = "native"))

        assertEquals(
            listOf(
                "konanc",
                "-target", "linux_x64",
                "-p", "program",
                "-generate-test-runner",
                "-Xinclude=build/my-app-test-klib",
                "-o", "build/my-app-test"
            ),
            cmd.args
        )
        assertEquals("build/my-app-test.kexe", cmd.outputPath)
    }

    @Test
    fun nativeTestLinkCommandDoesNotEmitEntryPointFlag() {
        val cmd = nativeTestLinkCommand(testConfig(target = "native"))

        assertFalse(cmd.args.contains("-e"))
    }

    @Test
    fun nativeTestLinkCommandWithProjectName() {
        val cmd = nativeTestLinkCommand(testConfig(name = "hello", target = "native"))

        assertEquals("build/hello-test.kexe", cmd.outputPath)
        assertEquals(
            listOf(
                "konanc",
                "-target", "linux_x64",
                "-p", "program",
                "-generate-test-runner",
                "-Xinclude=build/hello-test-klib",
                "-o", "build/hello-test"
            ),
            cmd.args
        )
    }

    @Test
    fun nativeTestLinkCommandWithMultipleKlibsRepeatsLFlag() {
        val cmd = nativeTestLinkCommand(
            testConfig(target = "native"),
            klibs = listOf("/cache/a.klib", "/cache/b.klib")
        )

        assertEquals(
            listOf(
                "konanc",
                "-target", "linux_x64",
                "-p", "program",
                "-generate-test-runner",
                "-l", "/cache/a.klib",
                "-l", "/cache/b.klib",
                "-Xinclude=build/my-app-test-klib",
                "-o", "build/my-app-test"
            ),
            cmd.args
        )
    }

    @Test
    fun nativeTestLinkCommandWithManagedKonancPath() {
        val managedKonanc = "/home/user/.kolt/toolchains/konanc/2.1.0/bin/konanc"
        val cmd = nativeTestLinkCommand(testConfig(target = "native"), konancPath = managedKonanc)

        assertEquals(managedKonanc, cmd.args.first())
    }

    @Test
    fun jarCommandWithManagedJarPathPreservesOutputPath() {
        val managedJarBin = "/home/user/.kolt/toolchains/jdk/21/bin/jar"

        val cmd = jarCommand(testConfig(name = "hello-world"), jarPath = managedJarBin)

        assertEquals("build/hello-world.jar", cmd.outputPath)
        assertEquals(
            listOf(managedJarBin, "cf", "build/hello-world.jar", "-C", "build/classes", "."),
            cmd.args
        )
    }
}
