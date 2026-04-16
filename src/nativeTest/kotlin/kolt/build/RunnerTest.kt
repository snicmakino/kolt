package kolt.build

import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class RunnerTest {

    @Test
    fun runCommandUsesClassesDirAsClasspath() {
        val cmd = runCommand(testConfig(), null)
        assertEquals(listOf("java", "-cp", "build/classes", "com.example.MainKt"), cmd.args)
    }

    @Test
    fun runCommandWithClasspathAppendedToClassesDir() {
        val cmd = runCommand(testConfig(), "/cache/lib.jar:/cache/util.jar")
        assertEquals(
            listOf("java", "-cp", "build/classes:/cache/lib.jar:/cache/util.jar", "com.example.MainKt"),
            cmd.args
        )
    }

    @Test
    fun runCommandWithAppArgs() {
        val cmd = runCommand(testConfig(), null, listOf("--port", "8080"))
        assertEquals(
            listOf("java", "-cp", "build/classes", "com.example.MainKt", "--port", "8080"),
            cmd.args
        )
    }

    @Test
    fun runCommandWithEmptyAppArgs() {
        val cmd = runCommand(testConfig(), null, emptyList())
        assertEquals(listOf("java", "-cp", "build/classes", "com.example.MainKt"), cmd.args)
    }

    @Test
    fun runCommandEmptyClasspathIsIgnored() {
        val cmd = runCommand(testConfig(), "")
        assertEquals(listOf("java", "-cp", "build/classes", "com.example.MainKt"), cmd.args)
    }

    @Test
    fun runCommandWithManagedJavaPathUsesItAsJava() {
        val managedJavaBin = "/home/user/.kolt/toolchains/jdk/21/bin/java"

        val cmd = runCommand(testConfig(), null, javaPath = managedJavaBin)

        assertEquals(
            listOf(managedJavaBin, "-cp", "build/classes", "com.example.MainKt"),
            cmd.args
        )
    }

    @Test
    fun runCommandWithNullJavaPathDefaultsToSystemJava() {
        val cmd = runCommand(testConfig(), null, javaPath = null)

        assertEquals("java", cmd.args.first())
    }

    @Test
    fun runCommandWithManagedJavaAndClasspath() {
        val managedJavaBin = "/home/user/.kolt/toolchains/jdk/21/bin/java"

        val cmd = runCommand(testConfig(), "/cache/lib.jar", javaPath = managedJavaBin)

        assertEquals(
            listOf(managedJavaBin, "-cp", "build/classes:/cache/lib.jar", "com.example.MainKt"),
            cmd.args
        )
    }

    @Test
    fun nativeRunCommandExecutesKexeBinary() {
        val cmd = nativeRunCommand(testConfig(target = "native"))
        assertEquals(listOf("build/my-app.kexe"), cmd.args)
    }

    @Test
    fun nativeRunCommandAppendsAppArgs() {
        val cmd = nativeRunCommand(testConfig(target = "native"), listOf("--port", "8080"))
        assertEquals(listOf("build/my-app.kexe", "--port", "8080"), cmd.args)
    }

    @Test
    fun nativeRunCommandUsesProjectName() {
        val cmd = nativeRunCommand(testConfig(name = "hello", target = "native"))
        assertEquals(listOf("build/hello.kexe"), cmd.args)
    }

    @Test
    fun nativeTestRunCommandExecutesTestKexeBinary() {
        val cmd = nativeTestRunCommand(testConfig(target = "native"))
        assertEquals(listOf("build/my-app-test.kexe"), cmd.args)
    }

    @Test
    fun nativeTestRunCommandUsesProjectName() {
        val cmd = nativeTestRunCommand(testConfig(name = "hello", target = "native"))
        assertEquals(listOf("build/hello-test.kexe"), cmd.args)
    }

    @Test
    fun nativeTestRunCommandAppendsTestArgs() {
        val cmd = nativeTestRunCommand(
            testConfig(target = "native"),
            testArgs = listOf("--ktest_filter=MyTest.*", "--ktest_logger=SIMPLE")
        )
        assertEquals(
            listOf("build/my-app-test.kexe", "--ktest_filter=MyTest.*", "--ktest_logger=SIMPLE"),
            cmd.args
        )
    }

    @Test
    fun runCommandWithManagedJavaAndAppArgs() {
        val managedJavaBin = "/home/user/.kolt/toolchains/jdk/21/bin/java"

        val cmd = runCommand(testConfig(), null, listOf("--port", "8080"), javaPath = managedJavaBin)

        assertEquals(
            listOf(managedJavaBin, "-cp", "build/classes", "com.example.MainKt", "--port", "8080"),
            cmd.args
        )
    }
}
