package kolt.cli

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.fileExists
import kolt.infra.output.AnsiCodes
import kolt.infra.output.ColorPolicy
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

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    val kindIdx = joined.indexOf("Kinds:")
    val targetIdx = joined.indexOf("Targets:")
    val groupIdx = joined.indexOf("> group ")
    assertTrue(kindIdx >= 0, "kind prompt missing: $joined")
    assertTrue(targetIdx > kindIdx, "target must follow kind: $joined")
    assertTrue(groupIdx > targetIdx, "group must follow target: $joined")
  }

  @Test
  fun ttyKindPromptHasArrowAndDefaultBracket() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertTrue(joined.contains("> kind [app]:"), "missing arrow + default bracket: $joined")
    assertTrue(joined.contains("> target [jvm]:"), "missing target arrow + default: $joined")
    assertTrue(joined.contains("> group [none]:"), "missing group arrow + default: $joined")
  }

  @Test
  fun ttyKindPromptDefaultIsApp() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(fileExists("src/Main.kt"), "blank kind input must default to app")
    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertFalse(toml.contains("kind = \"lib\""), "default app must not write kind = lib")
    assertTrue(toml.contains("main = \"main\""), "app must declare main")
  }

  @Test
  fun ttyKindPromptExplicitLib() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("lib", "", ""))

    doInit(listOf("mylib"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(fileExists("src/Lib.kt"))
    assertFalse(fileExists("src/Main.kt"))
  }

  @Test
  fun ttyTargetPromptDefaultIsJvm() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"jvm\""), "blank target must default to jvm")
  }

  @Test
  fun ttyTargetPromptByNameAcceptsNativeTarget() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "linuxX64", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"linuxX64\""))
  }

  @Test
  fun ttyGroupPromptBlankMeansNoGroup() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(fileExists("src/Main.kt"))
    val source = readFileAsString("src/Main.kt").getOrElse { error("read failed") }
    assertFalse(source.startsWith("package "), "blank group must not add package decl")
  }

  @Test
  fun ttyGroupPromptValidValueNestsSource() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", "com.example"))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(fileExists("src/com/example/myapp/Main.kt"))
  }

  @Test
  fun ttyMixedFlagAndPromptOnlyAsksMissing() {
    // --lib supplied → only target + group prompted
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "com.example"))

    doNew(listOf("mylib", "--lib"), io, ColorPolicy.Never).getOrElse {
      error("doNew failed: exit=$it")
    }

    val joined = io.outputs.joinToString("\n")
    assertFalse(joined.contains("Kinds:"), "kind must not be prompted when --lib given")
    val targetIdx = joined.indexOf("Targets:")
    val groupIdx = joined.indexOf("> group ")
    assertTrue(targetIdx >= 0, "target prompt missing")
    assertTrue(groupIdx > targetIdx, "target must precede group even when kind is fixed")
    assertTrue(fileExists("mylib/src/com/example/mylib/Lib.kt"))
  }

  @Test
  fun nonTtyNoPromptsAndDefaultsApply() {
    val io = FakeScaffoldIO(tty = false, inputs = emptyList())

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

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

    val exit = doInit(listOf("myapp"), io, ColorPolicy.Never).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit)
    assertFalse(fileExists("kolt.toml"), "no scaffold output on invalid prompt")
  }

  @Test
  fun ttyTargetInvalidInputExitsNonZero() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "wasm"))

    val exit = doInit(listOf("myapp"), io, ColorPolicy.Never).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit)
  }

  @Test
  fun ttyGroupInvalidInputExitsNonZero() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", "9bad"))

    val exit = doInit(listOf("myapp"), io, ColorPolicy.Never).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit)
  }

  @Test
  fun ttyEofOnFirstPromptCollapsesToDefaults() {
    // Ctrl-D / closed stdin under TTY: readlnOrNull returns null on every
    // call. The pipeline should treat it as blank input on each prompt and
    // produce the default scaffold (app/jvm/no group) without erroring.
    val io = FakeScaffoldIO(tty = true, inputs = emptyList())

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(fileExists("src/Main.kt"))
    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertFalse(toml.contains("kind = \"lib\""))
    assertTrue(toml.contains("target = \"jvm\""))
    assertTrue(toml.contains("main = \"main\""))
  }

  @Test
  fun ttyDoNewPromptsWhenFlagsOmitted() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("lib", "", ""))

    doNew(listOf("mylib"), io, ColorPolicy.Never).getOrElse { error("doNew failed: exit=$it") }

    assertTrue(fileExists("mylib/src/Lib.kt"))
  }

  @Test
  fun ttyKindPromptColorsAppCyanAndLibYellowWhenPolicyAllows() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Always).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertTrue(
      joined.contains("${AnsiCodes.CYAN}app${AnsiCodes.RESET}"),
      "expected cyan-wrapped app: $joined",
    )
    assertTrue(
      joined.contains("${AnsiCodes.YELLOW}lib${AnsiCodes.RESET}"),
      "expected yellow-wrapped lib: $joined",
    )
  }

  @Test
  fun ttyKindPromptHasNoAnsiWhenPolicyDisables() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertFalse(joined.contains("["), "color disabled must not emit ANSI: $joined")
  }

  @Test
  fun ttyTargetPromptColorsJvmCyanAndNativeYellowWhenPolicyAllows() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Always).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertTrue(
      joined.contains("${AnsiCodes.CYAN}jvm${AnsiCodes.RESET}"),
      "expected cyan-wrapped jvm: $joined",
    )
    assertTrue(
      joined.contains("${AnsiCodes.YELLOW}linuxX64${AnsiCodes.RESET}"),
      "expected yellow-wrapped linuxX64: $joined",
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
