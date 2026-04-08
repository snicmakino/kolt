package keel

import kotlin.test.Test
import kotlin.test.assertEquals

class RunnerTest {

    @Test
    fun runCommandWithoutClasspath() {
        val cmd = runCommand(testConfig(), null)
        assertEquals(listOf("java", "-cp", "build/my-app.jar", "com.example.MainKt"), cmd.args)
        assertEquals("build/my-app.jar", cmd.jarPath)
    }

    @Test
    fun runCommandWithClasspath() {
        val cmd = runCommand(testConfig(), "/cache/lib.jar:/cache/util.jar")
        assertEquals(
            listOf("java", "-cp", "build/my-app.jar:/cache/lib.jar:/cache/util.jar", "com.example.MainKt"),
            cmd.args
        )
    }

    @Test
    fun runCommandUsesProjectName() {
        val cmd = runCommand(testConfig(name = "hello-world"), null)
        assertEquals("build/hello-world.jar", cmd.jarPath)
    }
}
