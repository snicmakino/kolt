package keel.build

import keel.testConfig
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
        // Given: a managed java binary path
        val managedJavaBin = "/home/user/.keel/toolchains/jdk/21/bin/java"

        // When: runCommand is called with that path
        val cmd = runCommand(testConfig(), null, javaPath = managedJavaBin)

        // Then: the managed path is used as the first arg, not system "java"
        assertEquals(
            listOf(managedJavaBin, "-cp", "build/classes", "com.example.MainKt"),
            cmd.args
        )
    }

    @Test
    fun runCommandWithNullJavaPathDefaultsToSystemJava() {
        // Given: no managed JDK (null)
        // When: runCommand is called with explicit null javaPath
        val cmd = runCommand(testConfig(), null, javaPath = null)

        // Then: falls back to system "java"
        assertEquals("java", cmd.args.first())
    }

    @Test
    fun runCommandWithManagedJavaAndClasspath() {
        // Given: managed java + dependency classpath
        val managedJavaBin = "/home/user/.keel/toolchains/jdk/21/bin/java"

        // When: runCommand is called with both
        val cmd = runCommand(testConfig(), "/cache/lib.jar", javaPath = managedJavaBin)

        // Then: managed path is first, classpath is appended after build/classes
        assertEquals(
            listOf(managedJavaBin, "-cp", "build/classes:/cache/lib.jar", "com.example.MainKt"),
            cmd.args
        )
    }

    // --- nativeRunCommand ---

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

    // --- nativeTestRunCommand ---

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
        // Given: managed java + app arguments
        val managedJavaBin = "/home/user/.keel/toolchains/jdk/21/bin/java"

        // When: runCommand is called with javaPath and appArgs
        val cmd = runCommand(testConfig(), null, listOf("--port", "8080"), javaPath = managedJavaBin)

        // Then: managed java path is first, appArgs are appended at the end
        assertEquals(
            listOf(managedJavaBin, "-cp", "build/classes", "com.example.MainKt", "--port", "8080"),
            cmd.args
        )
    }
}
