package kolt.build

import kotlin.test.Test
import kotlin.test.assertEquals

class TestRunnerTest {

    @Test
    fun testRunCommandBasic() {
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/home/user/.kolt/tools/junit-platform-console-standalone-1.11.4.jar"
        )

        assertEquals(
            listOf(
                "java", "-jar",
                "/home/user/.kolt/tools/junit-platform-console-standalone-1.11.4.jar",
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
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/tools/launcher.jar",
            testResourceDirs = listOf("test-resources")
        )

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
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/tools/launcher.jar",
            testResourceDirs = listOf("test-resources"),
            classpath = "/cache/dep.jar"
        )

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

    @Test
    fun testRunCommandWithManagedJavaPathUsesItAsJava() {
        val managedJavaBin = "/home/user/.kolt/toolchains/jdk/21/bin/java"

        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/tools/launcher.jar",
            javaPath = managedJavaBin
        )

        assertEquals(
            listOf(
                managedJavaBin, "-jar", "/tools/launcher.jar",
                "--class-path", "build/classes:build/test-classes",
                "--scan-class-path"
            ),
            cmd.args
        )
    }

    @Test
    fun testRunCommandWithNullJavaPathDefaultsToSystemJava() {
        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/tools/launcher.jar",
            javaPath = null
        )

        assertEquals("java", cmd.args.first())
    }

    @Test
    fun testRunCommandWithManagedJavaAndClasspath() {
        val managedJavaBin = "/home/user/.kolt/toolchains/jdk/21/bin/java"

        val cmd = testRunCommand(
            classesDir = "build/classes",
            testClassesDir = "build/test-classes",
            consoleLauncherPath = "/tools/launcher.jar",
            classpath = "/cache/dep.jar",
            javaPath = managedJavaBin
        )

        assertEquals(
            listOf(
                managedJavaBin, "-jar", "/tools/launcher.jar",
                "--class-path", "build/classes:build/test-classes:/cache/dep.jar",
                "--scan-class-path"
            ),
            cmd.args
        )
    }
}
