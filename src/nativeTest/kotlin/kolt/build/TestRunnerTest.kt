package kolt.build

import kotlin.test.Test
import kotlin.test.assertEquals

class TestRunnerTest {

  @Test
  fun testRunCommandBasic() {
    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/home/user/.kolt/tools/junit-platform-console-standalone-1.11.4.jar",
      )

    assertEquals(
      listOf(
        "java",
        "-jar",
        "/home/user/.kolt/tools/junit-platform-console-standalone-1.11.4.jar",
        "--class-path",
        "build/classes:build/test-classes",
        "--scan-class-path",
      ),
      cmd.args,
    )
  }

  @Test
  fun testRunCommandWithClasspath() {
    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        classpath = "/cache/dep.jar:/cache/junit.jar",
      )

    assertEquals(
      listOf(
        "java",
        "-jar",
        "/tools/launcher.jar",
        "--class-path",
        "build/classes:build/test-classes:/cache/dep.jar:/cache/junit.jar",
        "--scan-class-path",
      ),
      cmd.args,
    )
  }

  @Test
  fun testRunCommandWithTestArgs() {
    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        testArgs = listOf("--include-classname", ".*Test"),
      )

    assertEquals(
      listOf(
        "java",
        "-jar",
        "/tools/launcher.jar",
        "--class-path",
        "build/classes:build/test-classes",
        "--scan-class-path",
        "--include-classname",
        ".*Test",
      ),
      cmd.args,
    )
  }

  @Test
  fun testRunCommandWithEmptyClasspath() {
    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        classpath = "",
      )

    assertEquals(
      listOf(
        "java",
        "-jar",
        "/tools/launcher.jar",
        "--class-path",
        "build/classes:build/test-classes",
        "--scan-class-path",
      ),
      cmd.args,
    )
  }

  @Test
  fun testRunCommandWithTestResourceDirs() {
    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        testResourceDirs = listOf("test-resources"),
      )

    assertEquals(
      listOf(
        "java",
        "-jar",
        "/tools/launcher.jar",
        "--class-path",
        "build/classes:build/test-classes:test-resources",
        "--scan-class-path",
      ),
      cmd.args,
    )
  }

  @Test
  fun testRunCommandWithMultipleTestResourceDirs() {
    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        testResourceDirs = listOf("test-resources", "fixtures"),
      )

    assertEquals(
      listOf(
        "java",
        "-jar",
        "/tools/launcher.jar",
        "--class-path",
        "build/classes:build/test-classes:test-resources:fixtures",
        "--scan-class-path",
      ),
      cmd.args,
    )
  }

  @Test
  fun testRunCommandWithTestResourceDirsAndClasspath() {
    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        testResourceDirs = listOf("test-resources"),
        classpath = "/cache/dep.jar",
      )

    assertEquals(
      listOf(
        "java",
        "-jar",
        "/tools/launcher.jar",
        "--class-path",
        "build/classes:build/test-classes:test-resources:/cache/dep.jar",
        "--scan-class-path",
      ),
      cmd.args,
    )
  }

  @Test
  fun testRunCommandWithEmptyTestResourceDirs() {
    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        testResourceDirs = emptyList(),
      )

    assertEquals(
      listOf(
        "java",
        "-jar",
        "/tools/launcher.jar",
        "--class-path",
        "build/classes:build/test-classes",
        "--scan-class-path",
      ),
      cmd.args,
    )
  }

  @Test
  fun testRunCommandWithManagedJavaPathUsesItAsJava() {
    val managedJavaBin = "/home/user/.kolt/toolchains/jdk/21/bin/java"

    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        javaPath = managedJavaBin,
      )

    assertEquals(
      listOf(
        managedJavaBin,
        "-jar",
        "/tools/launcher.jar",
        "--class-path",
        "build/classes:build/test-classes",
        "--scan-class-path",
      ),
      cmd.args,
    )
  }

  @Test
  fun testRunCommandWithNullJavaPathDefaultsToSystemJava() {
    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        javaPath = null,
      )

    assertEquals("java", cmd.args.first())
  }

  @Test
  fun testRunCommandWithManagedJavaAndClasspath() {
    val managedJavaBin = "/home/user/.kolt/toolchains/jdk/21/bin/java"

    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        classpath = "/cache/dep.jar",
        javaPath = managedJavaBin,
      )

    assertEquals(
      listOf(
        managedJavaBin,
        "-jar",
        "/tools/launcher.jar",
        "--class-path",
        "build/classes:build/test-classes:/cache/dep.jar",
        "--scan-class-path",
      ),
      cmd.args,
    )
  }

  // JUnit Platform Console Launcher 1.11 rejects `--scan-class-path` together
  // with `--select-class=` / `--select-package=` etc. Suppress the implicit
  // scan when the caller passes any explicit `--select-*` selector, so
  // `kolt test -- --select-class=foo.bar.AlphaTest` actually runs the
  // single class instead of failing argv parsing.
  @Test
  fun testRunCommandSuppressesScanClassPathWhenSelectClassPresent() {
    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        testArgs = listOf("--select-class=foo.bar.AlphaTest"),
      )

    assertEquals(
      listOf(
        "java",
        "-jar",
        "/tools/launcher.jar",
        "--class-path",
        "build/classes:build/test-classes",
        "--select-class=foo.bar.AlphaTest",
      ),
      cmd.args,
    )
  }

  @Test
  fun testRunCommandSuppressesScanClassPathWhenSelectPackagePresent() {
    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        testArgs = listOf("--select-package=foo.bar"),
      )

    assertEquals(
      listOf(
        "java",
        "-jar",
        "/tools/launcher.jar",
        "--class-path",
        "build/classes:build/test-classes",
        "--select-package=foo.bar",
      ),
      cmd.args,
    )
  }
}
