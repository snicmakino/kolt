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

    // --- nativeBuildCommand ---

    @Test
    fun nativeBuildCommandProducesProgramKexe() {
        val cmd = nativeBuildCommand(testConfig(target = "native"))

        assertEquals(
            listOf("konanc", "src", "-p", "program", "-e", "com.example.main", "-o", "build/my-app"),
            cmd.args
        )
        assertEquals("build/my-app.kexe", cmd.outputPath)
    }

    @Test
    fun nativeBuildCommandMultipleSources() {
        val cmd = nativeBuildCommand(testConfig(sources = listOf("src", "generated"), target = "native"))

        assertEquals(
            listOf("konanc", "src", "generated", "-p", "program", "-e", "com.example.main", "-o", "build/my-app"),
            cmd.args
        )
    }

    @Test
    fun nativeBuildCommandWithProjectName() {
        val cmd = nativeBuildCommand(testConfig(name = "hello", target = "native"))

        assertEquals("build/hello.kexe", cmd.outputPath)
        assertEquals(
            listOf("konanc", "src", "-p", "program", "-e", "com.example.main", "-o", "build/hello"),
            cmd.args
        )
    }

    @Test
    fun nativeBuildCommandWithManagedKonancPath() {
        val managedKonanc = "/home/user/.kolt/toolchains/konanc/2.1.0/bin/konanc"
        val cmd = nativeBuildCommand(testConfig(target = "native"), konancPath = managedKonanc)

        assertEquals(managedKonanc, cmd.args.first())
    }

    @Test
    fun nativeBuildCommandWithPluginArgs() {
        val cmd = nativeBuildCommand(
            testConfig(target = "native"),
            pluginArgs = listOf("-Xplugin=foo.jar")
        )

        assertEquals(
            listOf("konanc", "src", "-p", "program", "-e", "com.example.main", "-o", "build/my-app", "-Xplugin=foo.jar"),
            cmd.args
        )
    }

    @Test
    fun nativeBuildCommandWithSingleKlib() {
        val cmd = nativeBuildCommand(
            testConfig(target = "native"),
            klibs = listOf("/cache/lib.klib")
        )

        assertEquals(
            listOf("konanc", "src", "-p", "program", "-e", "com.example.main", "-l", "/cache/lib.klib", "-o", "build/my-app"),
            cmd.args
        )
    }

    @Test
    fun nativeBuildCommandWithMultipleKlibsRepeatsLFlag() {
        // -library is single-argument per library: NOT colon/comma-joined.
        val cmd = nativeBuildCommand(
            testConfig(target = "native"),
            klibs = listOf("/cache/a.klib", "/cache/b.klib", "/cache/c.klib")
        )

        assertEquals(
            listOf(
                "konanc", "src",
                "-p", "program",
                "-e", "com.example.main",
                "-l", "/cache/a.klib",
                "-l", "/cache/b.klib",
                "-l", "/cache/c.klib",
                "-o", "build/my-app"
            ),
            cmd.args
        )
    }

    @Test
    fun nativeBuildCommandEmptyKlibsOmitsLibraryFlag() {
        val cmd = nativeBuildCommand(testConfig(target = "native"), klibs = emptyList())

        assertFalse(cmd.args.contains("-l"))
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

    // --- nativeTestBuildCommand ---

    @Test
    fun nativeTestBuildCommandCompilesMainAndTestSourcesWithGeneratedRunner() {
        val cmd = nativeTestBuildCommand(testConfig(target = "native"))

        assertEquals(
            listOf(
                "konanc",
                "src", "test",
                "-p", "program",
                "-generate-test-runner",
                "-o", "build/my-app-test"
            ),
            cmd.args
        )
        assertEquals("build/my-app-test.kexe", cmd.outputPath)
    }

    @Test
    fun nativeTestBuildCommandDoesNotEmitEntryPointFlag() {
        // -generate-test-runner makes the compiler synthesize main(); -e would conflict.
        val cmd = nativeTestBuildCommand(testConfig(target = "native"))

        assertFalse(cmd.args.contains("-e"))
    }

    @Test
    fun nativeTestBuildCommandMultipleSourcesAndTestSources() {
        val cmd = nativeTestBuildCommand(
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
                "-p", "program",
                "-generate-test-runner",
                "-o", "build/my-app-test"
            ),
            cmd.args
        )
    }

    @Test
    fun nativeTestBuildCommandWithProjectName() {
        val cmd = nativeTestBuildCommand(testConfig(name = "hello", target = "native"))

        assertEquals("build/hello-test.kexe", cmd.outputPath)
        assertEquals(
            listOf(
                "konanc",
                "src", "test",
                "-p", "program",
                "-generate-test-runner",
                "-o", "build/hello-test"
            ),
            cmd.args
        )
    }

    @Test
    fun nativeTestBuildCommandWithManagedKonancPath() {
        val managedKonanc = "/home/user/.kolt/toolchains/konanc/2.1.0/bin/konanc"
        val cmd = nativeTestBuildCommand(testConfig(target = "native"), konancPath = managedKonanc)

        assertEquals(managedKonanc, cmd.args.first())
    }

    @Test
    fun nativeTestBuildCommandWithSingleKlib() {
        val cmd = nativeTestBuildCommand(
            testConfig(target = "native"),
            klibs = listOf("/cache/lib.klib")
        )

        assertEquals(
            listOf(
                "konanc",
                "src", "test",
                "-p", "program",
                "-generate-test-runner",
                "-l", "/cache/lib.klib",
                "-o", "build/my-app-test"
            ),
            cmd.args
        )
    }

    @Test
    fun nativeTestBuildCommandWithMultipleKlibsRepeatsLFlag() {
        val cmd = nativeTestBuildCommand(
            testConfig(target = "native"),
            klibs = listOf("/cache/a.klib", "/cache/b.klib", "/cache/c.klib")
        )

        assertEquals(
            listOf(
                "konanc",
                "src", "test",
                "-p", "program",
                "-generate-test-runner",
                "-l", "/cache/a.klib",
                "-l", "/cache/b.klib",
                "-l", "/cache/c.klib",
                "-o", "build/my-app-test"
            ),
            cmd.args
        )
    }

    @Test
    fun nativeTestBuildCommandEmptyKlibsOmitsLibraryFlag() {
        val cmd = nativeTestBuildCommand(testConfig(target = "native"), klibs = emptyList())

        assertFalse(cmd.args.contains("-l"))
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
