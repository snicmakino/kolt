package kolt.cli

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.executeCommand
import kolt.infra.fileExists
import kolt.infra.output.AnsiCodes
import kolt.infra.output.ColorPolicy
import kolt.infra.readFileAsString
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.PATH_MAX
import platform.posix.chdir
import platform.posix.getcwd
import platform.posix.mkdtemp
import platform.posix.setenv
import platform.posix.unsetenv

private class RecordingScaffoldIO(private val tty: Boolean, inputs: List<String> = emptyList()) :
  ScaffoldIO {
  private val iter = inputs.iterator()
  val outputs = mutableListOf<String>()

  override fun isStdinTty(): Boolean = tty

  override fun readLine(): String? = if (iter.hasNext()) iter.next() else null

  override fun println(msg: String) {
    outputs += msg
  }
}

@OptIn(ExperimentalForeignApi::class)
class DoNewTest {
  private var originalCwd: String = ""
  private var tmpDir: String = ""

  @BeforeTest
  fun setUp() {
    originalCwd = memScoped {
      val buf = allocArray<ByteVar>(PATH_MAX)
      getcwd(buf, PATH_MAX.toULong())?.toKString() ?: error("getcwd failed")
    }
    tmpDir = createTempDir("kolt-new-")
    check(chdir(tmpDir) == 0) { "chdir to $tmpDir failed" }
    setenv("GIT_CEILING_DIRECTORIES", tmpDir.substringBeforeLast('/'), 1)
  }

  @AfterTest
  fun tearDown() {
    unsetenv("GIT_CEILING_DIRECTORIES")
    chdir(originalCwd)
    if (tmpDir.isNotEmpty() && fileExists(tmpDir)) {
      removeDirectoryRecursive(tmpDir)
    }
  }

  @Test
  fun newCreatesDirectoryAndScaffoldsAppByDefault() {
    doNew(listOf("myapp")).getOrElse { error("doNew failed: exit=$it") }

    assertTrue(fileExists("myapp"), "myapp/ must be created")
    assertTrue(fileExists("myapp/kolt.toml"))
    assertTrue(fileExists("myapp/src/Main.kt"))
    assertTrue(fileExists("myapp/test/MainTest.kt"))
  }

  @Test
  fun newWithoutNameFails() {
    val exit = doNew(emptyList()).getError()
    assertEquals(EXIT_CONFIG_ERROR, exit)
  }

  @Test
  fun newRejectsExistingDirectory() {
    ensureDirectoryRecursive("myapp").getOrElse { error("seed myapp/ failed") }

    val exit = doNew(listOf("myapp")).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit)
  }

  @Test
  fun newRejectsExistingFileAtSameName() {
    writeFileAsString("myapp", "seed").getOrElse { error("seed file failed") }

    val exit = doNew(listOf("myapp")).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit)
    val content = readFileAsString("myapp").getOrElse { error("read failed") }
    assertEquals("seed", content, "existing file must not be overwritten")
  }

  @Test
  fun newWithInvalidProjectNameFails() {
    val exit = doNew(listOf("-bad-name")).getError()
    assertEquals(EXIT_CONFIG_ERROR, exit)
    assertFalse(fileExists("-bad-name"), "no directory must be created on validation failure")
  }

  @Test
  fun newWithLibFlagScaffoldsLibInside() {
    doNew(listOf("mylib", "--lib")).getOrElse { error("doNew failed: exit=$it") }

    assertTrue(fileExists("mylib/src/Lib.kt"))
    assertTrue(fileExists("mylib/test/LibTest.kt"))
    assertFalse(fileExists("mylib/src/Main.kt"))
    val toml = readFileAsString("mylib/kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("kind = \"lib\""))
  }

  @Test
  fun newWithTargetFlagWritesNativeTarget() {
    doNew(listOf("myapp", "--target", "linuxX64")).getOrElse { error("doNew failed: exit=$it") }

    val toml = readFileAsString("myapp/kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"linuxX64\""))
  }

  @Test
  fun newWithGroupNestsSourceUnderGroupPath() {
    doNew(listOf("myapp", "--group", "com.example")).getOrElse { error("doNew failed: exit=$it") }

    assertTrue(fileExists("myapp/src/com/example/myapp/Main.kt"))
    assertTrue(fileExists("myapp/test/com/example/myapp/MainTest.kt"))
    val source =
      readFileAsString("myapp/src/com/example/myapp/Main.kt").getOrElse { error("read failed") }
    assertTrue(source.startsWith("package com.example.myapp\n"))
  }

  @Test
  fun newRunsGitInitInsideNewDirectory() {
    doNew(listOf("myapp")).getOrElse { error("doNew failed: exit=$it") }

    assertTrue(fileExists("myapp/.git"), ".git/ must exist inside the new project")
  }

  @Test
  fun newSkipsGitInitWhenParentIsAlreadyAWorktree() {
    executeCommand(listOf("git", "init", "-q")).getOrElse { error("parent git init failed") }

    doNew(listOf("myapp")).getOrElse { error("doNew failed: exit=$it") }

    assertFalse(
      fileExists("myapp/.git"),
      "nested .git must not be created inside an existing worktree",
    )
  }

  @Test
  fun newPrintsNextStepBlockForAppPointingToKoltRun() {
    val io = RecordingScaffoldIO(tty = false)

    doNew(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doNew failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertTrue(joined.contains("next steps:"), "missing next-step header: $joined")
    assertTrue(joined.contains("cd myapp"), "missing cd line: $joined")
    assertTrue(joined.contains("kolt run"), "app must point to kolt run: $joined")
    assertFalse(joined.contains("kolt build"), "app must not point to kolt build: $joined")
  }

  @Test
  fun newPrintsNextStepBlockForLibPointingToKoltBuild() {
    val io = RecordingScaffoldIO(tty = false)

    doNew(listOf("mylib", "--lib"), io, ColorPolicy.Never).getOrElse {
      error("doNew failed: exit=$it")
    }

    val joined = io.outputs.joinToString("\n")
    assertTrue(joined.contains("next steps:"), "missing next-step header: $joined")
    assertTrue(joined.contains("cd mylib"), "missing cd line: $joined")
    assertTrue(joined.contains("kolt build"), "lib must point to kolt build: $joined")
    assertFalse(joined.contains("kolt run"), "lib must not point to kolt run: $joined")
  }

  @Test
  fun newNextStepBlockHasNoAnsiWhenColorDisabled() {
    val io = RecordingScaffoldIO(tty = false)

    doNew(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doNew failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertFalse(joined.contains("["), "color disabled must not emit ANSI: $joined")
  }

  @Test
  fun newNextStepBlockColorsCommandsWhenPolicyAllows() {
    val io = RecordingScaffoldIO(tty = false)

    doNew(listOf("myapp"), io, ColorPolicy.Always).getOrElse { error("doNew failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertTrue(
      joined.contains("${AnsiCodes.CYAN}cd myapp${AnsiCodes.RESET}"),
      "expected cyan-wrapped cd: $joined",
    )
    assertTrue(
      joined.contains("${AnsiCodes.CYAN}kolt run${AnsiCodes.RESET}"),
      "expected cyan-wrapped kolt run: $joined",
    )
  }

  private fun createTempDir(prefix: String): String {
    val template = "/tmp/${prefix}XXXXXX"
    val buf = template.encodeToByteArray().copyOf(template.length + 1)
    buf.usePinned { pinned ->
      val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
      return result.toKString()
    }
  }
}
