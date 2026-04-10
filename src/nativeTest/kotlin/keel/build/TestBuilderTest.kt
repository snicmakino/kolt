package keel.build

import keel.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TestBuilderTest {

    @Test
    fun testBuildCommandBasic() {
        val cmd = testBuildCommand(
            config = testConfig(),
            mainJarPath = "build/my-app.jar"
        )

        assertEquals(
            listOf("kotlinc", "-cp", "build/my-app.jar", "test", "-jvm-target", "17", "-d", "build/my-app-test.jar"),
            cmd.args
        )
        assertEquals("build/my-app-test.jar", cmd.outputPath)
    }

    @Test
    fun testBuildCommandWithClasspath() {
        val cmd = testBuildCommand(
            config = testConfig(),
            mainJarPath = "build/my-app.jar",
            classpath = "/cache/dep.jar:/cache/junit.jar"
        )

        assertEquals(
            listOf("kotlinc", "-cp", "build/my-app.jar:/cache/dep.jar:/cache/junit.jar", "test", "-jvm-target", "17", "-d", "build/my-app-test.jar"),
            cmd.args
        )
    }

    @Test
    fun testBuildCommandCustomTestSources() {
        val cmd = testBuildCommand(
            config = testConfig(testSources = listOf("test", "integration-test")),
            mainJarPath = "build/my-app.jar"
        )

        assertEquals(
            listOf("kotlinc", "-cp", "build/my-app.jar", "test", "integration-test", "-jvm-target", "17", "-d", "build/my-app-test.jar"),
            cmd.args
        )
    }

    @Test
    fun testBuildCommandDifferentJvmTarget() {
        val cmd = testBuildCommand(
            config = testConfig(jvmTarget = "21"),
            mainJarPath = "build/my-app.jar"
        )

        assertEquals(
            listOf("kotlinc", "-cp", "build/my-app.jar", "test", "-jvm-target", "21", "-d", "build/my-app-test.jar"),
            cmd.args
        )
    }

    @Test
    fun testBuildCommandUsesProjectName() {
        val cmd = testBuildCommand(
            config = testConfig(name = "hello-world"),
            mainJarPath = "build/hello-world.jar"
        )

        assertEquals("build/hello-world-test.jar", cmd.outputPath)
    }

    @Test
    fun testBuildCommandNoIncludeRuntime() {
        val cmd = testBuildCommand(
            config = testConfig(),
            mainJarPath = "build/my-app.jar"
        )

        assertFalse(cmd.args.contains("-include-runtime"))
    }

    @Test
    fun testJarPath() {
        assertEquals("build/my-app-test.jar", testJarPath(testConfig()))
        assertEquals("build/hello-test.jar", testJarPath(testConfig(name = "hello")))
    }

    @Test
    fun testBuildCommandWithPluginArgs() {
        val pluginArgs = listOf("-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar")

        val cmd = testBuildCommand(
            config = testConfig(),
            mainJarPath = "build/my-app.jar",
            pluginArgs = pluginArgs
        )

        assertEquals(
            listOf(
                "kotlinc", "-cp", "build/my-app.jar", "test", "-jvm-target", "17",
                "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
                "-d", "build/my-app-test.jar"
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
            mainJarPath = "build/my-app.jar",
            pluginArgs = pluginArgs
        )

        assertEquals(
            listOf(
                "kotlinc", "-cp", "build/my-app.jar", "test", "-jvm-target", "17",
                "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
                "-Xplugin=/usr/local/kotlinc/lib/allopen-compiler-plugin.jar",
                "-d", "build/my-app-test.jar"
            ),
            cmd.args
        )
    }

    @Test
    fun testBuildCommandWithEmptyPluginArgs() {
        val cmd = testBuildCommand(
            config = testConfig(),
            mainJarPath = "build/my-app.jar",
            pluginArgs = emptyList()
        )

        assertEquals(
            listOf("kotlinc", "-cp", "build/my-app.jar", "test", "-jvm-target", "17", "-d", "build/my-app-test.jar"),
            cmd.args
        )
    }

    @Test
    fun testBuildCommandWithClasspathAndPluginArgs() {
        val pluginArgs = listOf("-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar")

        val cmd = testBuildCommand(
            config = testConfig(),
            mainJarPath = "build/my-app.jar",
            classpath = "/cache/dep.jar",
            pluginArgs = pluginArgs
        )

        assertEquals(
            listOf(
                "kotlinc", "-cp", "build/my-app.jar:/cache/dep.jar", "test", "-jvm-target", "17",
                "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
                "-d", "build/my-app-test.jar"
            ),
            cmd.args
        )
    }
}
