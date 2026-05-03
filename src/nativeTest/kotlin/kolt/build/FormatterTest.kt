package kolt.build

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatterTest {

  @Test
  fun formatCommandDefaultsToGoogleStyle() {
    val cmd =
      formatCommand(
        ktfmtJarPath = "/tools/ktfmt.jar",
        files = listOf("src/Main.kt"),
        checkOnly = false,
      )

    assertEquals(
      listOf("java", "-jar", "/tools/ktfmt.jar", "--google-style", "src/Main.kt"),
      cmd.args,
    )
  }

  @Test
  fun formatCommandWithGoogleStyle() {
    val cmd =
      formatCommand(
        ktfmtJarPath = "/tools/ktfmt.jar",
        files = listOf("src/Main.kt"),
        checkOnly = false,
        style = "google",
      )

    assertEquals(
      listOf("java", "-jar", "/tools/ktfmt.jar", "--google-style", "src/Main.kt"),
      cmd.args,
    )
  }

  @Test
  fun formatCommandWithKotlinlangStyle() {
    val cmd =
      formatCommand(
        ktfmtJarPath = "/tools/ktfmt.jar",
        files = listOf("src/Main.kt"),
        checkOnly = false,
        style = "kotlinlang",
      )

    assertEquals(
      listOf("java", "-jar", "/tools/ktfmt.jar", "--kotlinlang-style", "src/Main.kt"),
      cmd.args,
    )
  }

  @Test
  fun formatCommandWithMetaStyle() {
    val cmd =
      formatCommand(
        ktfmtJarPath = "/tools/ktfmt.jar",
        files = listOf("src/Main.kt"),
        checkOnly = false,
        style = "meta",
      )

    assertEquals(
      listOf("java", "-jar", "/tools/ktfmt.jar", "--meta-style", "src/Main.kt"),
      cmd.args,
    )
  }

  @Test
  fun formatCommandWithMultipleFiles() {
    val cmd =
      formatCommand(
        ktfmtJarPath = "/tools/ktfmt.jar",
        files = listOf("src/Main.kt", "src/Config.kt", "test/MainTest.kt"),
        checkOnly = false,
      )

    assertEquals(
      listOf(
        "java",
        "-jar",
        "/tools/ktfmt.jar",
        "--google-style",
        "src/Main.kt",
        "src/Config.kt",
        "test/MainTest.kt",
      ),
      cmd.args,
    )
  }

  @Test
  fun formatCommandWithEmptyFiles() {
    val cmd =
      formatCommand(ktfmtJarPath = "/tools/ktfmt.jar", files = emptyList(), checkOnly = false)

    assertEquals(listOf("java", "-jar", "/tools/ktfmt.jar", "--google-style"), cmd.args)
  }

  @Test
  fun formatCommandWithCheckOnly() {
    val cmd =
      formatCommand(
        ktfmtJarPath = "/tools/ktfmt.jar",
        files = listOf("src/Main.kt"),
        checkOnly = true,
      )

    assertEquals(
      listOf(
        "java",
        "-jar",
        "/tools/ktfmt.jar",
        "--google-style",
        "--set-exit-if-changed",
        "--dry-run",
        "src/Main.kt",
      ),
      cmd.args,
    )
  }

  @Test
  fun formatCommandOmitsUnsafeFlagOnPreJdk23() {
    val cmd =
      formatCommand(
        ktfmtJarPath = "/tools/ktfmt.jar",
        files = listOf("src/Main.kt"),
        checkOnly = false,
        jdkMajorVersion = 22,
      )

    assertEquals(
      listOf("java", "-jar", "/tools/ktfmt.jar", "--google-style", "src/Main.kt"),
      cmd.args,
    )
  }

  @Test
  fun formatCommandOmitsUnsafeFlagWhenJdkVersionUnknown() {
    val cmd =
      formatCommand(
        ktfmtJarPath = "/tools/ktfmt.jar",
        files = listOf("src/Main.kt"),
        checkOnly = false,
        jdkMajorVersion = null,
      )

    assertEquals(
      listOf("java", "-jar", "/tools/ktfmt.jar", "--google-style", "src/Main.kt"),
      cmd.args,
    )
  }

  @Test
  fun formatCommandAddsUnsafeFlagBeforeJarOnJdk23() {
    val cmd =
      formatCommand(
        ktfmtJarPath = "/tools/ktfmt.jar",
        files = listOf("src/Main.kt"),
        checkOnly = false,
        jdkMajorVersion = 23,
      )

    assertEquals(
      listOf(
        "java",
        "--sun-misc-unsafe-memory-access=allow",
        "-jar",
        "/tools/ktfmt.jar",
        "--google-style",
        "src/Main.kt",
      ),
      cmd.args,
    )
  }

  @Test
  fun formatCommandAddsUnsafeFlagOnJdk25() {
    val cmd =
      formatCommand(
        ktfmtJarPath = "/tools/ktfmt.jar",
        files = listOf("src/Main.kt"),
        checkOnly = false,
        jdkMajorVersion = 25,
      )

    assertEquals(
      listOf(
        "java",
        "--sun-misc-unsafe-memory-access=allow",
        "-jar",
        "/tools/ktfmt.jar",
        "--google-style",
        "src/Main.kt",
      ),
      cmd.args,
    )
  }
}
