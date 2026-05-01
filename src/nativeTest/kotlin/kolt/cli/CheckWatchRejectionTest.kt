package kolt.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckWatchRejectionTest {

  @Test
  fun rejectCheckWatchEmitsLspGuidanceAndReturnsConfigError() {
    val stderr = mutableListOf<String>()

    val exit = rejectCheckWatch(eprint = { stderr.add(it) })

    assertEquals(EXIT_CONFIG_ERROR, exit)
    assertEquals(1, stderr.size, "rejection must emit exactly one stderr line")
    assertTrue(
      stderr[0].contains("--watch") && stderr[0].contains("check"),
      "stderr must name the rejected flag/command, got: ${stderr[0]}",
    )
    assertTrue(stderr[0].contains("LSP"), "stderr must point at LSP, got: ${stderr[0]}")
  }
}
