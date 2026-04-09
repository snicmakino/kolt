package keel

import kotlin.test.Test
import kotlin.test.assertEquals

class TestRunnerTest {

    @Test
    fun testRunCommandBasic() {
        val cmd = testRunCommand(
            mainJarPath = "build/my-app.jar",
            testJarPath = "build/my-app-test.jar",
            consoleLauncherPath = "/home/user/.keel/tools/junit-platform-console-standalone-1.11.4.jar"
        )

        assertEquals(
            listOf(
                "java", "-jar",
                "/home/user/.keel/tools/junit-platform-console-standalone-1.11.4.jar",
                "--class-path", "build/my-app.jar:build/my-app-test.jar",
                "--scan-class-path"
            ),
            cmd.args
        )
    }

    @Test
    fun testRunCommandWithClasspath() {
        val cmd = testRunCommand(
            mainJarPath = "build/my-app.jar",
            testJarPath = "build/my-app-test.jar",
            consoleLauncherPath = "/tools/launcher.jar",
            classpath = "/cache/dep.jar:/cache/junit.jar"
        )

        assertEquals(
            listOf(
                "java", "-jar", "/tools/launcher.jar",
                "--class-path", "build/my-app.jar:build/my-app-test.jar:/cache/dep.jar:/cache/junit.jar",
                "--scan-class-path"
            ),
            cmd.args
        )
    }

    @Test
    fun testRunCommandWithTestArgs() {
        val cmd = testRunCommand(
            mainJarPath = "build/my-app.jar",
            testJarPath = "build/my-app-test.jar",
            consoleLauncherPath = "/tools/launcher.jar",
            testArgs = listOf("--include-classname", ".*Test")
        )

        assertEquals(
            listOf(
                "java", "-jar", "/tools/launcher.jar",
                "--class-path", "build/my-app.jar:build/my-app-test.jar",
                "--scan-class-path",
                "--include-classname", ".*Test"
            ),
            cmd.args
        )
    }
}
