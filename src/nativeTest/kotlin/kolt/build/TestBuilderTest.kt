package kolt.build

import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TestBuilderTest {

    @Test
    fun testBuildCommandBasic() {
        val cmd = testBuildCommand(
            config = testConfig(),
            classesDir = "build/classes"
        )

        assertEquals(
            listOf("kotlinc", "-cp", "build/classes", "test", "-jvm-target", "17", "-d", "build/test-classes"),
            cmd.args
        )
        assertEquals("build/test-classes", cmd.outputPath)
    }

    @Test
    fun testBuildCommandWithClasspath() {
        val cmd = testBuildCommand(
            config = testConfig(),
            classesDir = "build/classes",
            classpath = "/cache/dep.jar:/cache/junit.jar"
        )

        assertEquals(
            listOf("kotlinc", "-cp", "build/classes:/cache/dep.jar:/cache/junit.jar", "test", "-jvm-target", "17", "-d", "build/test-classes"),
            cmd.args
        )
    }

    @Test
    fun testBuildCommandCustomTestSources() {
        val cmd = testBuildCommand(
            config = testConfig(testSources = listOf("test", "integration-test")),
            classesDir = "build/classes"
        )

        assertEquals(
            listOf("kotlinc", "-cp", "build/classes", "test", "integration-test", "-jvm-target", "17", "-d", "build/test-classes"),
            cmd.args
        )
    }

    @Test
    fun testBuildCommandDifferentJvmTarget() {
        val cmd = testBuildCommand(
            config = testConfig(jvmTarget = "21"),
            classesDir = "build/classes"
        )

        assertEquals(
            listOf("kotlinc", "-cp", "build/classes", "test", "-jvm-target", "21", "-d", "build/test-classes"),
            cmd.args
        )
    }

    @Test
    fun testBuildCommandOutputPathIsTestClassesDir() {
        // outputPath is always build/test-classes regardless of project name
        val cmd = testBuildCommand(
            config = testConfig(name = "hello-world"),
            classesDir = "build/classes"
        )

        assertEquals("build/test-classes", cmd.outputPath)
    }

    @Test
    fun testBuildCommandNoIncludeRuntime() {
        val cmd = testBuildCommand(
            config = testConfig(),
            classesDir = "build/classes"
        )

        assertFalse(cmd.args.contains("-include-runtime"))
    }

    @Test
    fun testBuildCommandWithPluginArgs() {
        val pluginArgs = listOf("-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar")

        val cmd = testBuildCommand(
            config = testConfig(),
            classesDir = "build/classes",
            pluginArgs = pluginArgs
        )

        assertEquals(
            listOf(
                "kotlinc", "-cp", "build/classes", "test", "-jvm-target", "17",
                "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
                "-d", "build/test-classes"
            ),
            cmd.args
        )
    }

    @Test
    fun testBuildCommandWithMultiplePluginArgs() {
        val pluginArgs = listOf(
            "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
            "-Xplugin=/usr/local/kotlinc/lib/allopen-compiler-plugin.jar"
        )

        val cmd = testBuildCommand(
            config = testConfig(),
            classesDir = "build/classes",
            pluginArgs = pluginArgs
        )

        assertEquals(
            listOf(
                "kotlinc", "-cp", "build/classes", "test", "-jvm-target", "17",
                "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
                "-Xplugin=/usr/local/kotlinc/lib/allopen-compiler-plugin.jar",
                "-d", "build/test-classes"
            ),
            cmd.args
        )
    }

    @Test
    fun testBuildCommandWithEmptyPluginArgs() {
        val cmd = testBuildCommand(
            config = testConfig(),
            classesDir = "build/classes",
            pluginArgs = emptyList()
        )

        assertEquals(
            listOf("kotlinc", "-cp", "build/classes", "test", "-jvm-target", "17", "-d", "build/test-classes"),
            cmd.args
        )
    }

    // --- kotlincPath parameter ---

    @Test
    fun testBuildCommandWithManagedKotlincPathUsesItAsCompiler() {
        // Given: a managed kotlinc binary path
        val managedKotlincBin = "/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc"

        // When: testBuildCommand is called with that path
        val cmd = testBuildCommand(
            config = testConfig(),
            classesDir = "build/classes",
            kotlincPath = managedKotlincBin
        )

        // Then: the managed path is used as the first arg, not system "kotlinc"
        assertEquals(
            listOf(managedKotlincBin, "-cp", "build/classes", "test", "-jvm-target", "17", "-d", "build/test-classes"),
            cmd.args
        )
    }

    @Test
    fun testBuildCommandWithNullKotlincPathDefaultsToSystemKotlinc() {
        // Given: no managed toolchain (null)
        val cmd = testBuildCommand(
            config = testConfig(),
            classesDir = "build/classes",
            kotlincPath = null
        )

        // Then: falls back to system "kotlinc"
        assertEquals("kotlinc", cmd.args.first())
    }

    @Test
    fun testBuildCommandWithManagedKotlincAndClasspath() {
        // Given: managed kotlinc + extra classpath
        val managedKotlincBin = "/home/user/.kolt/toolchains/kotlinc/2.1.0/bin/kotlinc"

        val cmd = testBuildCommand(
            config = testConfig(),
            classesDir = "build/classes",
            classpath = "/cache/dep.jar",
            kotlincPath = managedKotlincBin
        )

        assertEquals(
            listOf(managedKotlincBin, "-cp", "build/classes:/cache/dep.jar", "test", "-jvm-target", "17", "-d", "build/test-classes"),
            cmd.args
        )
    }

    @Test
    fun testBuildCommandWithClasspathAndPluginArgs() {
        val pluginArgs = listOf("-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar")

        val cmd = testBuildCommand(
            config = testConfig(),
            classesDir = "build/classes",
            classpath = "/cache/dep.jar",
            pluginArgs = pluginArgs
        )

        assertEquals(
            listOf(
                "kotlinc", "-cp", "build/classes:/cache/dep.jar", "test", "-jvm-target", "17",
                "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
                "-d", "build/test-classes"
            ),
            cmd.args
        )
    }
}
