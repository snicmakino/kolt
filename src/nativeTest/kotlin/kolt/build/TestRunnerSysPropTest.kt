package kolt.build

import kotlin.test.Test
import kotlin.test.assertEquals

class TestRunnerSysPropTest {

  @Test
  fun sysPropsEmptyProducesByteIdenticalArgvAsBefore() {
    val without =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
      )
    val withEmpty =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        sysProps = emptyList(),
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
      withEmpty.args,
    )
    assertEquals(without.args, withEmpty.args)
  }

  @Test
  fun singleSysPropAppearsImmediatelyAfterJava() {
    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        sysProps = listOf("foo" to "bar"),
      )

    assertEquals("java", cmd.args[0])
    assertEquals("-Dfoo=bar", cmd.args[1])
    assertEquals(
      listOf(
        "java",
        "-Dfoo=bar",
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
  fun multipleSysPropsPreserveDeclarationOrder() {
    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        sysProps = listOf("a" to "1", "z" to "2", "m" to "3"),
      )

    assertEquals(
      listOf(
        "java",
        "-Da=1",
        "-Dz=2",
        "-Dm=3",
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
  fun sysPropsCoexistWithClasspathAndTestArgs() {
    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        classpath = "/cache/dep.jar",
        testArgs = listOf("--include-classname", ".*Test"),
        sysProps = listOf("kolt.test.dir" to "/abs/dir", "feature.flag" to "on"),
      )

    assertEquals(
      listOf(
        "java",
        "-Dkolt.test.dir=/abs/dir",
        "-Dfeature.flag=on",
        "-jar",
        "/tools/launcher.jar",
        "--class-path",
        "build/classes:build/test-classes:/cache/dep.jar",
        "--scan-class-path",
        "--include-classname",
        ".*Test",
      ),
      cmd.args,
    )
  }

  @Test
  fun sysPropsRespectManagedJavaPath() {
    val managedJavaBin = "/home/user/.kolt/toolchains/jdk/21/bin/java"
    val cmd =
      testRunCommand(
        classesDir = "build/classes",
        testClassesDir = "build/test-classes",
        consoleLauncherPath = "/tools/launcher.jar",
        javaPath = managedJavaBin,
        sysProps = listOf("foo" to "bar"),
      )

    assertEquals(managedJavaBin, cmd.args[0])
    assertEquals("-Dfoo=bar", cmd.args[1])
  }
}
