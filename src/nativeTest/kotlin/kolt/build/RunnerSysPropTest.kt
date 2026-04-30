package kolt.build

import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class RunnerSysPropTest {

  @Test
  fun sysPropsEmptyProducesByteIdenticalArgvAsBefore() {
    val without = runCommand(testConfig(), main = "com.example.main", classpath = "/cache/lib.jar")
    val withEmpty =
      runCommand(
        testConfig(),
        main = "com.example.main",
        classpath = "/cache/lib.jar",
        sysProps = emptyList(),
      )

    assertEquals(
      listOf("java", "-cp", "build/classes:/cache/lib.jar", "com.example.MainKt"),
      withEmpty.args,
    )
    assertEquals(without.args, withEmpty.args)
  }

  @Test
  fun singleSysPropAppearsImmediatelyAfterJava() {
    val cmd =
      runCommand(
        testConfig(),
        main = "com.example.main",
        classpath = null,
        sysProps = listOf("foo" to "bar"),
      )

    assertEquals("java", cmd.args[0])
    assertEquals("-Dfoo=bar", cmd.args[1])
    assertEquals(
      listOf("java", "-Dfoo=bar", "-cp", "build/classes", "com.example.MainKt"),
      cmd.args,
    )
  }

  @Test
  fun multipleSysPropsPreserveDeclarationOrder() {
    val cmd =
      runCommand(
        testConfig(),
        main = "com.example.main",
        classpath = null,
        sysProps = listOf("a" to "1", "z" to "2", "m" to "3"),
      )

    assertEquals(
      listOf("java", "-Da=1", "-Dz=2", "-Dm=3", "-cp", "build/classes", "com.example.MainKt"),
      cmd.args,
    )
  }

  @Test
  fun sysPropsCoexistWithClasspathAndAppArgs() {
    val cmd =
      runCommand(
        testConfig(),
        main = "com.example.main",
        classpath = "/cache/lib.jar:/cache/util.jar",
        appArgs = listOf("--port", "8080"),
        sysProps = listOf("kolt.run.dir" to "/abs/dir", "feature.flag" to "on"),
      )

    assertEquals(
      listOf(
        "java",
        "-Dkolt.run.dir=/abs/dir",
        "-Dfeature.flag=on",
        "-cp",
        "build/classes:/cache/lib.jar:/cache/util.jar",
        "com.example.MainKt",
        "--port",
        "8080",
      ),
      cmd.args,
    )
  }

  @Test
  fun sysPropsRespectManagedJavaPath() {
    val managedJavaBin = "/home/user/.kolt/toolchains/jdk/21/bin/java"
    val cmd =
      runCommand(
        testConfig(),
        main = "com.example.main",
        classpath = null,
        javaPath = managedJavaBin,
        sysProps = listOf("foo" to "bar"),
      )

    assertEquals(managedJavaBin, cmd.args[0])
    assertEquals("-Dfoo=bar", cmd.args[1])
    assertEquals(
      listOf(managedJavaBin, "-Dfoo=bar", "-cp", "build/classes", "com.example.MainKt"),
      cmd.args,
    )
  }
}
