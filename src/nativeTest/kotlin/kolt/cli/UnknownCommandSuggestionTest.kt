package kolt.cli

import kolt.infra.suggest.closestMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnknownCommandSuggestionTest {

  @Test
  fun knownSubcommandsListIsAlphabetical() {
    assertEquals(KNOWN_SUBCOMMANDS_SORTED.sorted(), KNOWN_SUBCOMMANDS_SORTED)
  }

  @Test
  fun knownSubcommandsCatalogMatchesDispatcher() {
    // Drift guard: every `when` branch in `Main.main` must have a matching
    // entry here, and vice versa. Update this set when the dispatcher gains
    // or loses a subcommand.
    val expected =
      setOf(
        "add",
        "build",
        "cache",
        "check",
        "clean",
        "daemon",
        "fetch",
        "fmt",
        "info",
        "init",
        "new",
        "outdated",
        "remove",
        "run",
        "test",
        "tool",
        "toolchain",
        "tree",
        "update",
        "version",
      )
    assertEquals(expected, KNOWN_SUBCOMMANDS_SORTED.toSet())
  }

  @Test
  fun closestMatchSuggestsBuildForBuidl() {
    assertEquals("build", closestMatch("buidl", KNOWN_SUBCOMMANDS_SORTED))
  }

  @Test
  fun closestMatchSuggestsCheckForChck() {
    // length 4 → adaptive threshold 1; "chck" → "check" (insert 'e') is distance 1.
    assertEquals("check", closestMatch("chck", KNOWN_SUBCOMMANDS_SORTED))
  }

  @Test
  fun closestMatchReturnsNullForFarOffTypo() {
    assertNull(closestMatch("totally-unrelated-xxx", KNOWN_SUBCOMMANDS_SORTED))
  }

  @Test
  fun closestMatchOnExactKnownNameReturnsItself() {
    assertEquals("build", closestMatch("build", KNOWN_SUBCOMMANDS_SORTED))
  }
}
