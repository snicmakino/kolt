package keel.build

import keel.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class BuilderTest {

    @Test
    fun buildCommandSingleSource() {
        val cmd = buildCommand(testConfig())

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "17", "-include-runtime", "-d", "build/my-app.jar"),
            cmd.args
        )
        assertEquals("build/my-app.jar", cmd.outputPath)
    }

    @Test
    fun buildCommandMultipleSources() {
        val cmd = buildCommand(testConfig(sources = listOf("src", "generated")))

        assertEquals(
            listOf("kotlinc", "src", "generated", "-jvm-target", "17", "-include-runtime", "-d", "build/my-app.jar"),
            cmd.args
        )
    }

    @Test
    fun buildCommandWithClasspath() {
        val cmd = buildCommand(testConfig(), classpath = "/cache/a.jar:/cache/b.jar")

        assertEquals(
            listOf("kotlinc", "-cp", "/cache/a.jar:/cache/b.jar", "src", "-jvm-target", "17", "-include-runtime", "-d", "build/my-app.jar"),
            cmd.args
        )
    }

    @Test
    fun buildCommandDifferentJvmTarget() {
        val cmd = buildCommand(testConfig(jvmTarget = "21"))

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "21", "-include-runtime", "-d", "build/my-app.jar"),
            cmd.args
        )
    }

    @Test
    fun outputPathUsesProjectName() {
        val cmd = buildCommand(testConfig(name = "hello-world"))
        assertEquals("build/hello-world.jar", cmd.outputPath)
    }

    @Test
    fun buildCommandEmptyClasspathIsIgnored() {
        val cmd = buildCommand(testConfig(), classpath = "")

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "17", "-include-runtime", "-d", "build/my-app.jar"),
            cmd.args
        )
    }

    @Test
    fun buildCommandEmptySources() {
        val cmd = buildCommand(testConfig(sources = emptyList()))

        assertEquals(
            listOf("kotlinc", "-jvm-target", "17", "-include-runtime", "-d", "build/my-app.jar"),
            cmd.args
        )
    }

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
    fun buildCommandWithPluginArgs() {
        val pluginArgs = listOf("-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar")

        val cmd = buildCommand(testConfig(), pluginArgs = pluginArgs)

        assertEquals(
            listOf(
                "kotlinc", "src", "-jvm-target", "17",
                "-Xplugin=/usr/local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar",
                "-include-runtime", "-d", "build/my-app.jar"
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
                "-include-runtime", "-d", "build/my-app.jar"
            ),
            cmd.args
        )
    }

    @Test
    fun buildCommandWithEmptyPluginArgs() {
        val cmd = buildCommand(testConfig(), pluginArgs = emptyList())

        assertEquals(
            listOf("kotlinc", "src", "-jvm-target", "17", "-include-runtime", "-d", "build/my-app.jar"),
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
                "-include-runtime", "-d", "build/my-app.jar"
            ),
            cmd.args
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
}
