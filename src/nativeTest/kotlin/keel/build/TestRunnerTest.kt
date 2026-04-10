package keel.build

import kotlin.test.Test
import kotlin.test.assertEquals

class TestRunnerTest {

    @Test
    fun testRunCommandBasic() {
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/home/user/.keel/tools/junit-platform-console-standalone-1.11.4.jar"
        )

        assertEquals(
            listOf(
                "java", "-jar",
                "/home/user/.keel/tools/junit-platform-console-standalone-1.11.4.jar",
                "--class-path", "build/classes:build/test-classes",
                "--scan-class-path"
            ),
            cmd.args
        )
    }

    @Test
    fun testRunCommandWithClasspath() {
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/tools/launcher.jar",
            classpath = "/cache/dep.jar:/cache/junit.jar"
        )

        assertEquals(
            listOf(
                "java", "-jar", "/tools/launcher.jar",
                "--class-path", "build/classes:build/test-classes:/cache/dep.jar:/cache/junit.jar",
                "--scan-class-path"
            ),
            cmd.args
        )
    }

    @Test
    fun testRunCommandWithTestArgs() {
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/tools/launcher.jar",
            testArgs = listOf("--include-classname", ".*Test")
        )

        assertEquals(
            listOf(
                "java", "-jar", "/tools/launcher.jar",
                "--class-path", "build/classes:build/test-classes",
                "--scan-class-path",
                "--include-classname", ".*Test"
            ),
            cmd.args
        )
    }

    @Test
    fun testRunCommandWithEmptyClasspath() {
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/tools/launcher.jar",
            classpath = ""
        )

        assertEquals(
            listOf(
                "java", "-jar", "/tools/launcher.jar",
                "--class-path", "build/classes:build/test-classes",
                "--scan-class-path"
            ),
            cmd.args
        )
    }

    @Test
    fun testRunCommandWithTestResourceDirs() {
        // Given: a single test resource directory
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/tools/launcher.jar",
            testResourceDirs = listOf("test-resources")
        )

        // Then: test resource dir is inserted between test-classes and deps
        assertEquals(
            listOf(
                "java", "-jar", "/tools/launcher.jar",
                "--class-path", "build/classes:build/test-classes:test-resources",
                "--scan-class-path"
            ),
            cmd.args
        )
    }

    @Test
    fun testRunCommandWithMultipleTestResourceDirs() {
        // Given: multiple test resource directories
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/tools/launcher.jar",
            testResourceDirs = listOf("test-resources", "fixtures")
        )

        assertEquals(
            listOf(
                "java", "-jar", "/tools/launcher.jar",
                "--class-path", "build/classes:build/test-classes:test-resources:fixtures",
                "--scan-class-path"
            ),
            cmd.args
        )
    }

    @Test
    fun testRunCommandWithTestResourceDirsAndClasspath() {
        // Given: both test resource dirs and dependency classpath
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/tools/launcher.jar",
            testResourceDirs = listOf("test-resources"),
            classpath = "/cache/dep.jar"
        )

        // Then: order is classes:test-classes:test-resources:deps
        assertEquals(
            listOf(
                "java", "-jar", "/tools/launcher.jar",
                "--class-path", "build/classes:build/test-classes:test-resources:/cache/dep.jar",
                "--scan-class-path"
            ),
            cmd.args
        )
    }

    @Test
    fun testRunCommandWithEmptyTestResourceDirs() {
        // Given: empty testResourceDirs (same as not providing it)
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/tools/launcher.jar",
            testResourceDirs = emptyList()
        )

        assertEquals(
            listOf(
                "java", "-jar", "/tools/launcher.jar",
                "--class-path", "build/classes:build/test-classes",
                "--scan-class-path"
            ),
            cmd.args
        )
    }
}
