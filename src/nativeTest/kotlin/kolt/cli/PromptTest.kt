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
    val groupIdx = joined.indexOf("Group (")
    assertTrue(kindIdx >= 0, "kind prompt missing: $joined")
    assertTrue(targetIdx > kindIdx, "target must follow kind: $joined")
    assertTrue(groupIdx > targetIdx, "group must follow target: $joined")
  }

  @Test
  fun ttyKindPromptListsOptionsOnePerLineWithNumbers() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertTrue(joined.contains("  1) app (default)"), "missing numbered app line: $joined")
    assertTrue(joined.contains("  2) lib"), "missing numbered lib line: $joined")
  }

  @Test
  fun ttyTargetPromptListsOptionsOnePerLineWithNumbers() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertTrue(joined.contains("  1) jvm (default)"), "missing numbered jvm line: $joined")
    assertTrue(joined.contains("  2) linuxArm64"), "missing numbered linuxArm64 line: $joined")
    assertTrue(joined.contains("  3) linuxX64"), "missing numbered linuxX64 line: $joined")
  }

  @Test
  fun ttyTargetPromptSeparatesJvmFromNativeWithMarker() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    val markerIdx = joined.indexOf("-- native --")
    val jvmIdx = joined.indexOf("1) jvm")
    val firstNativeIdx = joined.indexOf("2) linuxArm64")
    assertTrue(markerIdx >= 0, "native separator missing: $joined")
    assertTrue(markerIdx > jvmIdx, "separator must come after jvm line: $joined")
    assertTrue(markerIdx < firstNativeIdx, "separator must precede first native line: $joined")
  }

  @Test
  fun ttyKindPromptAcceptsNumericInput() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("2", "", ""))

    doInit(listOf("mylib"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(fileExists("src/Lib.kt"), "numeric '2' must select lib")
    assertFalse(fileExists("src/Main.kt"))
  }

  @Test
  fun ttyTargetPromptAcceptsNumericInput() {
    // Targets ordering: 1) jvm, 2) linuxArm64, 3) linuxX64, 4) macosArm64, 5) macosX64, 6) mingwX64
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "3", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"linuxX64\""), "numeric '3' must select linuxX64: $toml")
  }

  @Test
  fun ttyPromptUsesPlainArrowInputLine() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    // The arrow is printed as its own line after each option list. Three
    // prompts (kind, target, group) -> three `>` lines.
    val arrowLines = io.outputs.count { it == ">" }
    assertEquals(3, arrowLines, "expected 3 arrow lines (one per prompt), got: ${io.outputs}")
    assertTrue(
      io.outputs.any { it.startsWith("Group (") && it.contains("blank for none") },
      "group prompt header missing 'blank for none' hint: ${io.outputs}",
    )
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
  fun ttyKindPromptExplicitLibViaNumber() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("2", "", ""))

    doInit(listOf("mylib"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(fileExists("src/Lib.kt"))
    assertFalse(fileExists("src/Main.kt"))
  }

  @Test
  fun ttyKindPromptRejectsNamedInput() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("lib"))

    val exit = doInit(listOf("myapp"), io, ColorPolicy.Never).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit, "named input must be rejected; only numeric accepted")
    assertFalse(fileExists("kolt.toml"), "no scaffold output on rejected input")
  }

  @Test
  fun ttyTargetPromptDefaultIsJvm() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"jvm\""), "blank target must default to jvm")
  }

  @Test
  fun ttyTargetPromptRejectsNamedInput() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "linuxX64"))

    val exit = doInit(listOf("myapp"), io, ColorPolicy.Never).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit, "named input must be rejected; only numeric accepted")
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
    val groupIdx = joined.indexOf("Group (")
    assertTrue(targetIdx >= 0, "target prompt missing")
    assertTrue(groupIdx > targetIdx, "target must precede group even when kind is fixed")
    assertTrue(fileExists("mylib/src/com/example/mylib/Lib.kt"))
  }

  @Test
  fun nonTtyNoPromptsAndDefaultsApply() {
    val io = FakeScaffoldIO(tty = false, inputs = emptyList())

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertFalse(joined.contains("Kinds:"), "non-TTY must not print kind prompt: $joined")
    assertFalse(joined.contains("Targets:"), "non-TTY must not print target prompt: $joined")
    assertFalse(joined.contains("Group ("), "non-TTY must not print group prompt: $joined")
    assertFalse(io.outputs.contains(">"), "non-TTY must not print arrow input line: $joined")
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
    val io = FakeScaffoldIO(tty = true, inputs = listOf("2", "", ""))

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
