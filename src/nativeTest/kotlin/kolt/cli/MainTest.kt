package kolt.cli

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MainTest {

  @Test
  fun dashDLineMentionsThreeLayerOverlayOrder() {
    val lines = usageLines()

    val dashD = lines.firstOrNull { it.contains("-D<key>=<value>") }
    assertNotNull(dashD, "expected a -D<key>=<value> line in usage output")
    assertTrue(
      dashD.contains("kolt.toml") && dashD.contains("kolt.local.toml") && dashD.contains("-D"),
      "expected three-layer order (kolt.toml, kolt.local.toml, -D) in -D line, got: $dashD",
    )
  }

  @Test
  fun selfIsARecognizedSubcommand() {
    assertTrue(
      "self" in KNOWN_SUBCOMMANDS_SORTED,
      "expected 'self' to be a recognized subcommand, not treated as unknown",
    )
  }

  @Test
  fun usageMentionsSelfUpdate() {
    val lines = usageLines()

    val self = lines.firstOrNull { it.contains("self") && it.contains("update") }
    assertNotNull(self, "expected a 'kolt self update' line in usage output")
  }

  @Test
  fun flagsBlockMentionsKoltLocalTomlOverlay() {
    val lines = usageLines()

    val overlay =
      lines.firstOrNull { it.contains("kolt.local.toml") && !it.contains("-D<key>=<value>") }
    assertNotNull(overlay, "expected a kolt.local.toml description line distinct from -D")
    assertTrue(
      overlay.contains("[test.sys_props]") &&
        overlay.contains("[run.sys_props]") &&
        overlay.contains("[repositories"),
      "expected allowlist sections in kolt.local.toml description, got: $overlay",
    )
  }
}
