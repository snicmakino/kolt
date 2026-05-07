package kolt.cli

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.config.BuildSection
import kolt.config.KoltConfig
import kolt.config.KotlinSection
import kolt.resolve.Coordinate
import kolt.usertool.ToolEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ToolCommandsTest {

  // ----- parseToolArgs: argv shape -----

  @Test
  fun parsesRunWithAliasOnly() {
    val invocation = parseToolArgs(listOf("run", "ktlint")).get()
    assertEquals(ToolInvocation.Run(alias = "ktlint", args = emptyList()), invocation)
  }

  @Test
  fun parsesRunWithAliasAndPositionalArgs() {
    val invocation = parseToolArgs(listOf("run", "ktlint", "--reporter", "plain")).get()
    assertEquals(
      ToolInvocation.Run(alias = "ktlint", args = listOf("--reporter", "plain")),
      invocation,
    )
  }

  @Test
  fun stripsLeadingDoubleDashAfterAlias() {
    // `kolt tool run ktlint -- -F` and `kolt tool run ktlint -F` are equivalent.
    val a = parseToolArgs(listOf("run", "ktlint", "--", "-F")).get()
    val b = parseToolArgs(listOf("run", "ktlint", "-F")).get()
    assertEquals(b, a)
  }

  @Test
  fun preservesNonLeadingDoubleDashTokens() {
    val invocation = parseToolArgs(listOf("run", "ktlint", "-x", "--", "y")).get()
    assertEquals(ToolInvocation.Run(alias = "ktlint", args = listOf("-x", "--", "y")), invocation)
  }

  @Test
  fun returnsMissingSubcommandForEmptyArgs() {
    val err = parseToolArgs(emptyList()).getError()
    assertIs<ToolArgError.MissingSubcommand>(err)
  }

  @Test
  fun returnsMissingAliasForRunWithoutAlias() {
    val err = parseToolArgs(listOf("run")).getError()
    assertIs<ToolArgError.MissingAlias>(err)
  }

  @Test
  fun returnsUnknownSubcommandForNonRunFirstArg() {
    val err = parseToolArgs(listOf("list")).getError()
    val unknown = assertIs<ToolArgError.UnknownSubcommand>(err)
    assertEquals("list", unknown.subcommand)
  }

  // ----- lookupToolEntry: alias dispatch -----

  @Test
  fun lookupReturnsEntryForKnownAlias() {
    val entry = ToolEntry(Coordinate("g", "a", "1.0"), classifier = null)
    val config = configWithTools(mapOf("ktlint" to entry))
    assertEquals(entry, lookupToolEntry("ktlint", config).get())
  }

  @Test
  fun lookupReturnsExitConfigErrorForUnknownAlias() {
    val config = configWithTools(emptyMap())
    val exit = lookupToolEntry("ktlint", config).getError()
    assertNotNull(exit)
    assertEquals(EXIT_CONFIG_ERROR, exit)
  }

  // ----- helpers -----

  private fun configWithTools(tools: Map<String, ToolEntry>): KoltConfig =
    KoltConfig(
      name = "demo",
      version = "0.0.1",
      kotlin = KotlinSection(version = "2.1.0"),
      build = BuildSection(target = "jvm", main = null, sources = listOf("src")),
      tools = tools,
    )
}
