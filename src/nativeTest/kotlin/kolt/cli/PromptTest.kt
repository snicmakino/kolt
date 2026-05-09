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
  fun ttyNoFlagsPromptsPresetThenGroupForJvm() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    val presetIdx = joined.indexOf("Presets:")
    val groupIdx = joined.indexOf("Group (")
    assertTrue(presetIdx >= 0, "preset prompt missing: $joined")
    assertTrue(groupIdx > presetIdx, "group must follow preset: $joined")
    assertFalse(
      joined.contains("Native target:"),
      "jvm preset must not trigger native sub-prompt: $joined",
    )
    assertFalse(joined.contains("Kinds:"), "no-flag path must not emit kind prompt: $joined")
    assertFalse(joined.contains("Targets:"), "no-flag path must not emit target prompt: $joined")
  }

  @Test
  fun ttyPresetPromptListsFourOptionsOnePerLineWithNumbers() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertTrue(joined.contains("  1) jvm app (default)"), "missing numbered jvm app line: $joined")
    assertTrue(joined.contains("  2) jvm lib"), "missing numbered jvm lib line: $joined")
    assertTrue(joined.contains("  3) native app"), "missing numbered native app line: $joined")
    assertTrue(joined.contains("  4) native lib"), "missing numbered native lib line: $joined")
  }

  @Test
  fun ttyTargetPromptListsOptionsOnePerLineWithNumbers() {
    // --lib pins kind, so only target + group are prompted; this isolates
    // the target prompt from the no-flag path (which task 2.1 routes through
    // a different prompt) and keeps this assertion stable.
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", ""))

    doInit(listOf("myapp", "--lib"), io, ColorPolicy.Never).getOrElse {
      error("doInit failed: exit=$it")
    }

    val joined = io.outputs.joinToString("\n")
    assertTrue(joined.contains("  1) jvm (default)"), "missing numbered jvm line: $joined")
    assertTrue(joined.contains("  2) linuxX64"), "missing numbered linuxX64 line: $joined")
    assertTrue(joined.contains("  3) macosArm64"), "missing numbered macosArm64 line: $joined")
    assertTrue(joined.contains("  4) mingwX64"), "missing numbered mingwX64 line: $joined")
    assertTrue(joined.contains("  5) linuxArm64"), "missing numbered linuxArm64 line: $joined")
  }

  @Test
  fun ttyTargetPromptSeparatesJvmFromNativeWithMarker() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", ""))

    doInit(listOf("myapp", "--lib"), io, ColorPolicy.Never).getOrElse {
      error("doInit failed: exit=$it")
    }

    val joined = io.outputs.joinToString("\n")
    val markerIdx = joined.indexOf("-- native --")
    val jvmIdx = joined.indexOf("1) jvm")
    val firstNativeIdx = joined.indexOf("2) linuxX64")
    assertTrue(markerIdx >= 0, "native separator missing: $joined")
    assertTrue(markerIdx > jvmIdx, "separator must come after jvm line: $joined")
    assertTrue(markerIdx < firstNativeIdx, "separator must precede first native line: $joined")
  }

  @Test
  fun ttyTargetPromptShowsDeprecatedSuffixOnMacosX64() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", ""))

    doInit(listOf("myapp", "--lib"), io, ColorPolicy.Never).getOrElse {
      error("doInit failed: exit=$it")
    }

    val joined = io.outputs.joinToString("\n")
    assertTrue(
      joined.contains("  6) macosX64 (deprecated)"),
      "expected '6) macosX64 (deprecated)' line: $joined",
    )
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
    // Targets ordering: 1) jvm, 2) linuxX64, 3) macosArm64, 4) mingwX64,
    // 5) linuxArm64, 6) macosX64 (deprecated). --lib pins kind so only the
    // target + group prompts fire here.
    val io = FakeScaffoldIO(tty = true, inputs = listOf("3", ""))

    doInit(listOf("myapp", "--lib"), io, ColorPolicy.Never).getOrElse {
      error("doInit failed: exit=$it")
    }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(
      toml.contains("target = \"macosArm64\""),
      "numeric '3' must select macosArm64: $toml",
    )
  }

  @Test
  fun ttyPromptUsesPlainArrowInputLine() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    // The arrow is printed as its own line after each option list. No-flag jvm
    // path -> two prompts (preset + group) -> two `>` lines.
    val arrowLines = io.outputs.count { it == ">" }
    assertEquals(2, arrowLines, "expected 2 arrow lines (one per prompt), got: ${io.outputs}")
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
  fun ttyPresetPromptRejectsNamedInput() {
    // No flags -> preset prompt fires first; "lib" is non-numeric and rejected.
    val io = FakeScaffoldIO(tty = true, inputs = listOf("lib"))

    val exit = doInit(listOf("myapp"), io, ColorPolicy.Never).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit, "named input must be rejected; only numeric accepted")
    assertFalse(fileExists("kolt.toml"), "no scaffold output on rejected input")
  }

  @Test
  fun ttyTargetPromptDefaultIsJvm() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", ""))

    doInit(listOf("myapp", "--lib"), io, ColorPolicy.Never).getOrElse {
      error("doInit failed: exit=$it")
    }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"jvm\""), "blank target must default to jvm")
  }

  @Test
  fun ttyTargetPromptRejectsNamedInput() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("linuxX64"))

    val exit = doInit(listOf("myapp", "--lib"), io, ColorPolicy.Never).getError()

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
    // Inputs: preset=blank (jvm app default), group=com.example.
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "com.example"))

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
  fun ttyPresetInvalidInputExitsNonZero() {
    // No flags -> preset prompt fires first; "bogus" is non-numeric and rejected.
    val io = FakeScaffoldIO(tty = true, inputs = listOf("bogus"))

    val exit = doInit(listOf("myapp"), io, ColorPolicy.Never).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit)
    assertFalse(fileExists("kolt.toml"), "no scaffold output on invalid prompt")
  }

  @Test
  fun ttyTargetInvalidInputExitsNonZero() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("wasm"))

    val exit = doInit(listOf("myapp", "--lib"), io, ColorPolicy.Never).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit)
  }

  @Test
  fun ttyGroupInvalidInputExitsNonZero() {
    // Inputs: preset=blank (jvm app default), group="9bad" (invalid).
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", "9bad"))

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
  fun ttyPresetPromptColorsDefaultCyanAndOthersYellowWhenPolicyAllows() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Always).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertTrue(
      joined.contains("${AnsiCodes.CYAN}jvm app${AnsiCodes.RESET}"),
      "expected cyan-wrapped 'jvm app' default: $joined",
    )
    assertTrue(
      joined.contains("${AnsiCodes.YELLOW}jvm lib${AnsiCodes.RESET}"),
      "expected yellow-wrapped 'jvm lib': $joined",
    )
    assertTrue(
      joined.contains("${AnsiCodes.YELLOW}native app${AnsiCodes.RESET}"),
      "expected yellow-wrapped 'native app': $joined",
    )
  }

  @Test
  fun ttyPresetPromptHasNoAnsiWhenPolicyDisables() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertFalse(joined.contains("["), "color disabled must not emit ANSI: $joined")
  }

  @Test
  fun ttyTargetPromptColorsJvmCyanAndNativeYellowWhenPolicyAllows() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", ""))

    doInit(listOf("myapp", "--lib"), io, ColorPolicy.Always).getOrElse {
      error("doInit failed: exit=$it")
    }

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

  @Test
  fun ttyPresetThreeShowsNativeSubPromptWithFiveOptions() {
    // Preset 3 = native app. Inputs: preset=3, native target=blank, group=blank.
    val io = FakeScaffoldIO(tty = true, inputs = listOf("3", "", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertTrue(joined.contains("Native target:"), "missing native sub-prompt header: $joined")
    assertTrue(joined.contains("  1) linuxX64 (default)"), "missing 1) linuxX64 line: $joined")
    assertTrue(joined.contains("  2) macosArm64"), "missing 2) macosArm64 line: $joined")
    assertTrue(joined.contains("  3) mingwX64"), "missing 3) mingwX64 line: $joined")
    assertTrue(joined.contains("  4) linuxArm64"), "missing 4) linuxArm64 line: $joined")
    assertTrue(
      joined.contains("  5) macosX64 (deprecated)"),
      "missing 5) macosX64 (deprecated) line: $joined",
    )
  }

  @Test
  fun ttyPresetFourAlsoShowsNativeSubPrompt() {
    // Preset 4 = native lib. Inputs: preset=4, native target=blank, group=blank.
    val io = FakeScaffoldIO(tty = true, inputs = listOf("4", "", ""))

    doInit(listOf("mylib"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertTrue(
      joined.contains("Native target:"),
      "preset 4 (native lib) must trigger native sub-prompt: $joined",
    )
    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("kind = \"lib\""), "preset 4 must produce kind = lib: $toml")
    assertTrue(
      toml.contains("target = \"linuxX64\""),
      "blank native sub-prompt must default to linuxX64: $toml",
    )
  }

  @Test
  fun ttyPresetOneSkipsNativeSubPrompt() {
    // Preset 1 = jvm app. Inputs: preset=1, group=blank. Native sub-prompt
    // must NOT appear because target is pinned to jvm by the preset.
    val io = FakeScaffoldIO(tty = true, inputs = listOf("1", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertFalse(
      joined.contains("Native target:"),
      "jvm preset must not trigger native sub-prompt: $joined",
    )
    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"jvm\""), "preset 1 must produce target = jvm: $toml")
  }

  @Test
  fun ttyNativeSubPromptDefaultIsLinuxX64() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("3", "", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(
      toml.contains("target = \"linuxX64\""),
      "blank native sub-prompt input must default to linuxX64: $toml",
    )
  }

  @Test
  fun ttyNativeSubPromptNumericTwoSelectsMacosArm64() {
    // Native target ordering: 1) linuxX64, 2) macosArm64, 3) mingwX64, 4) linuxArm64, 5) macosX64.
    val io = FakeScaffoldIO(tty = true, inputs = listOf("3", "2", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(
      toml.contains("target = \"macosArm64\""),
      "numeric '2' on native sub-prompt must select macosArm64: $toml",
    )
  }

  @Test
  fun ttyNativeSubPromptShowsDeprecatedSuffixOnMacosX64() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("3", "", ""))

    doInit(listOf("myapp"), io, ColorPolicy.Never).getOrElse { error("doInit failed: exit=$it") }

    val joined = io.outputs.joinToString("\n")
    assertTrue(
      joined.contains("  5) macosX64 (deprecated)"),
      "expected '5) macosX64 (deprecated)' in native sub-prompt: $joined",
    )
  }

  @Test
  fun ttyNativeSubPromptInvalidInputExitsNonZero() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("3", "wasm"))

    val exit = doInit(listOf("myapp"), io, ColorPolicy.Never).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit, "non-numeric native sub-prompt input must exit non-zero")
  }

  @Test
  fun ttyTargetFlagJvmOnlyPromptsKindButNotPreset() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", ""))

    doInit(listOf("myapp", "--target=jvm"), io, ColorPolicy.Never).getOrElse {
      error("doInit failed: exit=$it")
    }

    val joined = io.outputs.joinToString("\n")
    assertTrue(joined.contains("Kinds:"), "kind prompt missing when --target= alone: $joined")
    assertFalse(
      joined.contains("Presets:"),
      "preset prompt must not fire when --target= set: $joined",
    )
    assertFalse(
      joined.contains("Native target:"),
      "native sub-prompt must not fire when --target= set: $joined",
    )
    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"jvm\""))
    assertFalse(toml.contains("kind = \"lib\""))
  }

  @Test
  fun ttyTargetFlagNativeOnlyPromptsKindButNotPreset() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf("", ""))

    doInit(listOf("myapp", "--target=linuxX64"), io, ColorPolicy.Never).getOrElse {
      error("doInit failed: exit=$it")
    }

    val joined = io.outputs.joinToString("\n")
    assertTrue(joined.contains("Kinds:"), "kind prompt missing: $joined")
    assertFalse(joined.contains("Presets:"))
    assertFalse(joined.contains("Native target:"))
    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"linuxX64\""))
  }

  @Test
  fun ttyBothFlagsPinnedSkipsPresetTargetAndKindPrompts() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf(""))

    doNew(listOf("mylib", "--lib", "--target=linuxX64"), io, ColorPolicy.Never).getOrElse {
      error("doNew failed: exit=$it")
    }

    val joined = io.outputs.joinToString("\n")
    assertFalse(joined.contains("Presets:"))
    assertFalse(joined.contains("Kinds:"))
    assertFalse(joined.contains("Targets:"))
    assertFalse(joined.contains("Native target:"))
    val toml = readFileAsString("mylib/kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("kind = \"lib\""))
    assertTrue(toml.contains("target = \"linuxX64\""))
  }

  @Test
  fun nonTtyLibFlagProducesJvmLibScaffold() {
    val io = FakeScaffoldIO(tty = false, inputs = emptyList())

    doInit(listOf("mylib", "--lib"), io, ColorPolicy.Never).getOrElse {
      error("doInit failed: exit=$it")
    }

    val joined = io.outputs.joinToString("\n")
    assertFalse(joined.contains("Presets:"))
    assertFalse(joined.contains("Kinds:"))
    assertFalse(joined.contains("Targets:"))
    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("kind = \"lib\""))
    assertTrue(toml.contains("target = \"jvm\""))
  }

  @Test
  fun nonTtyTargetFlagNativeProducesAppScaffoldForThatTarget() {
    val io = FakeScaffoldIO(tty = false, inputs = emptyList())

    doInit(listOf("myapp", "--target=linuxX64"), io, ColorPolicy.Never).getOrElse {
      error("doInit failed: exit=$it")
    }

    val joined = io.outputs.joinToString("\n")
    assertFalse(joined.contains("Presets:"))
    assertFalse(joined.contains("Kinds:"))
    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"linuxX64\""))
    assertFalse(toml.contains("kind = \"lib\""))
  }

  @Test
  fun nonInteractiveInvalidTargetFlagExitsBeforePrompt() {
    val io = FakeScaffoldIO(tty = true, inputs = emptyList())

    val exit = doInit(listOf("myapp", "--target=wasm"), io, ColorPolicy.Never).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit)
    assertFalse(fileExists("kolt.toml"), "no scaffold output on invalid target flag")
    val joined = io.outputs.joinToString("\n")
    assertFalse(joined.contains("Presets:"))
    assertFalse(joined.contains("Targets:"))
    assertFalse(joined.contains("Kinds:"))
  }

  @Test
  fun ttyGroupFlagSuppressesGroupPrompt() {
    val io = FakeScaffoldIO(tty = true, inputs = listOf(""))

    doInit(listOf("myapp", "--group=com.example"), io, ColorPolicy.Never).getOrElse {
      error("doInit failed: exit=$it")
    }

    val joined = io.outputs.joinToString("\n")
    assertFalse(joined.contains("Group ("), "--group= must suppress the group prompt: $joined")
    assertTrue(fileExists("src/com/example/myapp/Main.kt"), "scaffold must nest under group dir")
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
