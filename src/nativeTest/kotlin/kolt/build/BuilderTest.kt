package kolt.build

import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuilderTest {

    // --- buildCommand ---

    @Test
    fun buildCommandOutputsToClassesDir() {
        val cmd = buildCommand(testConfig())

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "17", "-d", "build/classes"),
            cmd.args
        )
        assertEquals("build/classes", cmd.outputPath)
    }

    @Test
    fun buildCommandDoesNotIncludeRuntime() {
        val cmd = buildCommand(testConfig())

        assertFalse(cmd.args.contains("-include-runtime"))
    }

    @Test
    fun buildCommandMultipleSources() {
        val cmd = buildCommand(testConfig(sources = listOf("src", "generated")))

        assertEquals(
            listOf("kotlinc", "src", "generated", "-jvm-target", "17", "-d", "build/classes"),
            cmd.args
        )
    }

    @Test
    fun buildCommandWithClasspath() {
        val cmd = buildCommand(testConfig(), classpath = "/cache/a.jar:/cache/b.jar")

        assertEquals(
            listOf("kotlinc", "-cp", "/cache/a.jar:/cache/b.jar", "src", "-jvm-target", "17", "-d", "build/classes"),
            cmd.args
        )
    }

    @Test
    fun buildCommandDifferentJvmTarget() {
        val cmd = buildCommand(testConfig(jvmTarget = "21"))

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "21", "-d", "build/classes"),
            cmd.args
        )
    }

    @Test
    fun buildCommandOutputPathIsClassesDir() {
        // outputPath is always build/classes regardless of project name
        val cmd = buildCommand(testConfig(name = "hello-world"))
        assertEquals("build/classes", cmd.outputPath)
    }

    @Test
    fun buildCommandEmptyClasspathIsIgnored() {
        val cmd = buildCommand(testConfig(), classpath = "")

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "17", "-d", "build/classes"),
            cmd.args
        )
    }

    @Test
    fun buildCommandEmptySources() {
        val cmd = buildCommand(testConfig(sources = emptyList()))

        assertEquals(
            listOf("kotlinc", "-jvm-target", "17", "-d", "build/classes"),
            cmd.args
        )
    }

    @Test
    fun buildCommandWithPluginArgs() {
        val pluginArgs = listOf("-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar")

        val cmd = buildCommand(testConfig(), pluginArgs = pluginArgs)

        assertEquals(
            listOf(
                "kotlinc", "src", "-jvm-target", "17",
                "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
                "-d", "build/classes"
            ),
            cmd.args
        )
    }

    @Test
    fun buildCommandWithMultiplePluginArgs() {
        val pluginArgs = listOf(
            "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
            "-Xplugin=/usr/local/kotlinc/lib/allopen-compiler-plugin.jar"
        )

        val cmd = buildCommand(testConfig(), pluginArgs = pluginArgs)

        assertEquals(
            listOf(
                "kotlinc", "src", "-jvm-target", "17",
                "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
                "-Xplugin=/usr/local/kotlinc/lib/allopen-compiler-plugin.jar",
                "-d", "build/classes"
            ),
            cmd.args
        )
    }

    @Test
    fun buildCommandWithEmptyPluginArgs() {
        val cmd = buildCommand(testConfig(), pluginArgs = emptyList())

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "17", "-d", "build/classes"),
            cmd.args
        )
    }

    @Test
    fun buildCommandWithClasspathAndPluginArgs() {
        val pluginArgs = listOf("-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar")

        val cmd = buildCommand(testConfig(), classpath = "/cache/a.jar", pluginArgs = pluginArgs)

        assertEquals(
            listOf(
                "kotlinc", "-cp", "/cache/a.jar", "src", "-jvm-target", "17",
                "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
                "-d", "build/classes"
            ),
            cmd.args
        )
    }

    // --- checkCommand ---

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
    fun buildCommandWithManagedKotlincPathUsesItAsCompiler() {
        // Given: a managed kotlinc binary path
        val managedKotlincBin = "/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc"

        // When: buildCommand is called with that path
        val cmd = buildCommand(testConfig(), kotlincPath = managedKotlincBin)

        // Then: the managed path is used as the first arg, not the system "kotlinc"
        assertEquals(
            listOf(managedKotlincBin, "src", "-jvm-target", "17", "-d", "build/classes"),
            cmd.args
        )
    }

    @Test
    fun buildCommandWithNullKotlincPathDefaultsToSystemKotlinc() {
        // Given: no managed toolchain (null)
        // When: buildCommand is called with explicit null kotlincPath
        val cmd = buildCommand(testConfig(), kotlincPath = null)

        // Then: falls back to system "kotlinc"
        assertEquals("kotlinc", cmd.args.first())
    }

    @Test
    fun buildCommandWithManagedKotlincAndClasspath() {
        // Given: managed kotlinc + classpath
        val managedKotlincBin = "/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc"

        // When: buildCommand is called with both
        val cmd = buildCommand(testConfig(), classpath = "/cache/a.jar", kotlincPath = managedKotlincBin)

        // Then: managed path is first, classpath follows
        assertEquals(
            listOf(managedKotlincBin, "-cp", "/cache/a.jar", "src", "-jvm-target", "17", "-d", "build/classes"),
            cmd.args
        )
    }

    // --- checkCommand (kotlincPath) ---

    @Test
    fun checkCommandWithManagedKotlincPathUsesItAsCompiler() {
        // Given: a managed kotlinc binary path
        val managedKotlincBin = "/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc"

        // When: checkCommand is called with that path
        val cmd = checkCommand(testConfig(), kotlincPath = managedKotlincBin)

        // Then: the managed path is first in args
        assertEquals(
            listOf(managedKotlincBin, "src", "-jvm-target", "17"),
            cmd
        )
    }

    @Test
    fun checkCommandWithNullKotlincPathDefaultsToSystemKotlinc() {
        // Given: no managed toolchain (null)
        val cmd = checkCommand(testConfig(), kotlincPath = null)

        // Then: falls back to system "kotlinc"
        assertEquals("kotlinc", cmd.first())
    }

    @Test
    fun checkCommandWithManagedKotlincAndClasspath() {
        // Given: managed kotlinc + classpath
        val managedKotlincBin = "/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc"

        val cmd = checkCommand(testConfig(), classpath = "/cache/a.jar", kotlincPath = managedKotlincBin)

        assertEquals(
            listOf(managedKotlincBin, "-cp", "/cache/a.jar", "src", "-jvm-target", "17"),
            cmd
        )
    }

    // --- jarCommand ---

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
        // jar source is always build/classes regardless of project name
        val cmd1 = jarCommand(testConfig(name = "app-a"))
        val cmd2 = jarCommand(testConfig(name = "app-b"))

        assertEquals("build/classes", cmd1.args[4])
        assertEquals("build/classes", cmd2.args[4])
    }

    @Test
    fun jarCommandWithManagedJarPathUsesItAsJar() {
        // Given: a managed jar binary path
        val managedJarBin = "/home/user/.kolt/toolchains/jdk/21/bin/jar"

        // When: jarCommand is called with that path
        val cmd = jarCommand(testConfig(), jarPath = managedJarBin)

        // Then: managed path is used as the first arg, not system "jar"
        assertEquals(
            listOf(managedJarBin, "cf", "build/my-app.jar", "-C", "build/classes", "."),
            cmd.args
        )
    }

    @Test
    fun jarCommandWithNullJarPathDefaultsToSystemJar() {
        // Given: no managed JDK (null)
        // When: jarCommand is called with explicit null jarPath
        val cmd = jarCommand(testConfig(), jarPath = null)

        // Then: falls back to system "jar"
        assertEquals("jar", cmd.args.first())
    }

    // --- nativeLibraryCommand (Stage 1: sources -> klib) ---

    @Test
    fun nativeLibraryCommandProducesKlibDirectory() {
        val cmd = nativeLibraryCommand(testConfig(target = "native"))

        assertEquals(
            listOf("konanc", "src", "-p", "library", "-nopack", "-o", "build/my-app-klib"),
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
            listOf("konanc", "src", "generated", "-p", "library", "-nopack", "-o", "build/my-app-klib"),
            cmd.args
        )
    }

    @Test
    fun nativeLibraryCommandWithProjectName() {
        val cmd = nativeLibraryCommand(testConfig(name = "hello", target = "native"))

        assertEquals("build/hello-klib", cmd.outputPath)
        assertEquals(
            listOf("konanc", "src", "-p", "library", "-nopack", "-o", "build/hello-klib"),
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
                "konanc", "src",
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
                "konanc", "src",
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

    // --- nativeLinkCommand (Stage 2: klib -> kexe) ---

    @Test
    fun nativeLinkCommandLinksKlibToProgramKexe() {
        val cmd = nativeLinkCommand(testConfig(target = "native"))

        assertEquals(
            listOf(
                "konanc",
                "-p", "program",
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
                "-p", "program",
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
                "-p", "program",
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
    fun nativeLinkCommandDoesNotEmitEntryPointFlag() {
        // -Xinclude pulls in the compiled main() from the klib. -e would be
        // redundant and risks conflicting with what the klib already declares.
        val cmd = nativeLinkCommand(testConfig(target = "native"))

        assertFalse(cmd.args.contains("-e"))
    }

    @Test
    fun nativeEntryPointStripsClassNameAndAppendsMain() {
        assertEquals("com.example.main", nativeEntryPoint(testConfig()))
    }

    @Test
    fun nativeEntryPointRootPackage() {
        val config = testConfig().copy(main = "MainKt")
        assertEquals("main", nativeEntryPoint(config))
    }

    @Test
    fun nativeEntryPointDeepPackage() {
        val config = testConfig().copy(main = "foo.bar.baz.AppKt")
        assertEquals("foo.bar.baz.main", nativeEntryPoint(config))
    }

    // --- needsNativeEntryPointWarning ---

    @Test
    fun needsNativeEntryPointWarningFalseForKtSuffix() {
        assertFalse(needsNativeEntryPointWarning(testConfig()))
    }

    @Test
    fun needsNativeEntryPointWarningFalseForRootKtSuffix() {
        assertFalse(needsNativeEntryPointWarning(testConfig().copy(main = "MainKt")))
    }

    @Test
    fun needsNativeEntryPointWarningTrueForNonKtClass() {
        assertTrue(needsNativeEntryPointWarning(testConfig().copy(main = "com.example.App")))
    }

    @Test
    fun needsNativeEntryPointWarningTrueForRootNonKt() {
        assertTrue(needsNativeEntryPointWarning(testConfig().copy(main = "Main")))
    }

    // --- nativeTestLibraryCommand (Stage 1: main+test sources -> klib) ---

    @Test
    fun nativeTestLibraryCommandIncludesMainAndTestSources() {
        val cmd = nativeTestLibraryCommand(testConfig(target = "native"))

        assertEquals(
            listOf(
                "konanc",
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

    // --- nativeTestLinkCommand (Stage 2: klib -> test kexe) ---

    @Test
    fun nativeTestLinkCommandLinksWithGeneratedRunner() {
        val cmd = nativeTestLinkCommand(testConfig(target = "native"))

        assertEquals(
            listOf(
                "konanc",
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
        // Given: a managed jar binary path
        val managedJarBin = "/home/user/.kolt/toolchains/jdk/21/bin/jar"

        // When: jarCommand is called with jarPath
        val cmd = jarCommand(testConfig(name = "hello-world"), jarPath = managedJarBin)

        // Then: outputPath is still derived from project name, not affected by jarPath
        assertEquals("build/hello-world.jar", cmd.outputPath)
        assertEquals(
            listOf(managedJarBin, "cf", "build/hello-world.jar", "-C", "build/classes", "."),
            cmd.args
        )
    }
}
