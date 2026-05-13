package kolt.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitGitignoreTest {

  @Test
  fun generateGitignoreIncludesKoltLocalTomlExactlyOnce() {
    val output = generateGitignore()
    val occurrences = output.lineSequence().count { it == "kolt.local.toml" }
    assertEquals(
      1,
      occurrences,
      "expected `kolt.local.toml` on its own line exactly once; actual:\n$output",
    )
  }

  @Test
  fun generateGitignoreEntriesAreEachOnTheirOwnLine() {
    // `kolt.local.toml` must appear as a standalone line, not as a fragment of
    // a broader path glob, so .gitignore matches the file at the project root
    // unambiguously.
    val lines = generateGitignore().lineSequence().toList()
    assertTrue(
      "kolt.local.toml" in lines,
      "expected a `kolt.local.toml` line; actual lines: $lines",
    )
  }
}
