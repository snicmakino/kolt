package kolt.cli

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.fileExists
import kolt.infra.readFileAsString
import kolt.infra.removeDirectoryRecursive
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

private class FakeScaffoldIO(private val tty: Boolean, inputs: List<String>) : ScaffoldIO {
  private val iter = inputs.iterator()
  val outputs = mutableListOf<String>()

  override fun isStdinTty(): Boolean = tty

  override fun readLine(): String? = if (iter.hasNext()) iter.next() else null

  override fun println(msg: String) {
    outputs += msg
  }
}

@OptIn(ExperimentalForeignApi::class)
class PromptTest {
  private var originalCwd: String = ""
  private var tmpDir: String = ""

  @BeforeTest
  fun setUp() {
    originalCwd = memScoped {
      val buf = allocArray<ByteVar>(PATH_MAX)
      getcwd(buf, PATH_MAX.toULong())?.toKString() ?: error("getcwd failed")
    }
    tmpDir = createTempDir("kolt-prompt-")
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
  fun ttyNoFlagsPromptsKindThenTargetThenGroup() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    val kindIdx = joined.indexOf("Project kind:")
    val targetIdx = joined.indexOf("Target:")
    val groupIdx = joined.indexOf("Group ")
    assertTrue(kindIdx >= 0, "kind prompt missing: $joined")
    assertTrue(targetIdx > kindIdx, "target must follow kind: $joined")
    assertTrue(groupIdx > targetIdx, "group must follow target: $joined")
  }

  @Test
  fun ttyKindPromptDefaultIsApp() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(fileExists("src/Main.kt"), "blank kind input must default to app")
    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertFalse(toml.contains("kind = \"lib\""), "default app must not write kind = lib")
    assertTrue(toml.contains("main = \"main\""), "app must declare main")
  }

  @Test
  fun ttyKindPromptExplicitLib() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("2", "", ""))

    doInit(listOf("mylib"), io).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(fileExists("src/Lib.kt"))
    assertFalse(fileExists("src/Main.kt"))
  }

  @Test
  fun ttyKindPromptAcceptsTextualLib() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("lib", "", ""))

    doInit(listOf("mylib"), io).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(fileExists("src/Lib.kt"))
  }

  @Test
  fun ttyTargetPromptDefaultIsJvm() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io).getOrElse { error("doInit failed: exit=$it") }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"jvm\""), "blank target must default to jvm")
  }

  @Test
  fun ttyTargetPromptByNumberPicksNativeTarget() {
    // Targets are listed: 1=jvm, 2=linuxArm64, 3=linuxX64, 4=macosArm64, 5=macosX64, 6=mingwX64
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "3", ""))

    doInit(listOf("myapp"), io).getOrElse { error("doInit failed: exit=$it") }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"linuxX64\""), "expected linuxX64, got: $toml")
  }

  @Test
  fun ttyTargetPromptByNameAccepted() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "linuxX64", ""))

    doInit(listOf("myapp"), io).getOrElse { error("doInit failed: exit=$it") }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"linuxX64\""))
  }

  @Test
  fun ttyGroupPromptBlankMeansNoGroup() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(fileExists("src/Main.kt"))
    val source = readFileAsString("src/Main.kt").getOrElse { error("read failed") }
    assertFalse(source.startsWith("package "), "blank group must not add package decl")
  }

  @Test
  fun ttyGroupPromptValidValueNestsSource() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", "com.example"))

    doInit(listOf("myapp"), io).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(fileExists("src/com/example/myapp/Main.kt"))
  }

  @Test
  fun ttyMixedFlagAndPromptOnlyAsksMissing() {
    // --lib supplied → only target + group prompted
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "com.example"))

    doNew(listOf("mylib", "--lib"), io).getOrElse { error("doNew failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertFalse(joined.contains("Project kind:"), "kind must not be prompted when --lib given")
    assertTrue(joined.contains("Target:"))
    assertTrue(joined.contains("Group "))
    assertTrue(fileExists("mylib/src/com/example/mylib/Lib.kt"))
  }

  @Test
  fun nonTtyNoPromptsAndDefaultsApply() {
    val io = FakeScaffoldIO(tty = false, inputs = emptyList())

    doInit(listOf("myapp"), io).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(io.outputs.isEmpty(), "non-TTY must not print prompts: ${io.outputs}")
    assertTrue(fileExists("src/Main.kt"))
    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertFalse(toml.contains("kind = \"lib\""))
    assertTrue(toml.contains("target = \"jvm\""))
    assertTrue(toml.contains("main = \"main\""))
  }

  @Test
  fun ttyKindInvalidInputExitsNonZero() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("bogus"))

    val exit = doInit(listOf("myapp"), io).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit)
    assertFalse(fileExists("kolt.toml"), "no scaffold output on invalid prompt")
  }

  @Test
  fun ttyTargetInvalidInputExitsNonZero() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "wasm"))

    val exit = doInit(listOf("myapp"), io).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit)
  }

  @Test
  fun ttyGroupInvalidInputExitsNonZero() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", "9bad"))

    val exit = doInit(listOf("myapp"), io).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit)
  }

  @Test
  fun ttyDoNewPromptsWhenFlagsOmitted() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("2", "", ""))

    doNew(listOf("mylib"), io).getOrElse { error("doNew failed: exit=$it") }

    assertTrue(fileExists("mylib/src/Lib.kt"))
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
