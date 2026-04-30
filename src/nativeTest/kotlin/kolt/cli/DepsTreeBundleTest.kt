package kolt.cli

import kolt.resolve.PomDependency
import kolt.resolve.PomInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Req 4.2: `kolt deps tree` lists every declared bundle under a labelled
// section distinct from main / test. `buildBundlesSection` is the pure
// rendering helper extracted from `doTree` so the format can be asserted
// without invoking the network-touching `createPomLookup`.
class DepsTreeBundleTest {

  private fun stubLookup(
    pomInfos: Map<String, PomInfo> = emptyMap()
  ): (String, String) -> PomInfo? = { ga, version -> pomInfos["$ga:$version"] }

  @Test
  fun emptyBundlesProducesEmptyString() {
    val rendered = buildBundlesSection(bundles = emptyMap(), pomLookup = stubLookup())

    assertTrue(rendered.isEmpty(), "no bundles -> no section: '$rendered'")
  }

  @Test
  fun singleBundleRendersSectionHeaderAndBundleName() {
    val bundles = mapOf("fixture" to mapOf("com.example:fix" to "1.0.0"))

    val rendered = buildBundlesSection(bundles = bundles, pomLookup = stubLookup())

    assertTrue(rendered.contains("bundles:"), "section header missing: '$rendered'")
    assertTrue(rendered.contains("fixture:"), "bundle name missing: '$rendered'")
    assertTrue(rendered.contains("com.example:fix:1.0.0"), "bundle dep missing: '$rendered'")
  }

  @Test
  fun multipleBundlesRenderEachInDeclarationOrder() {
    val bundles =
      linkedMapOf(
        "fixture" to mapOf("com.example:fix" to "1.0.0"),
        "tools" to mapOf("com.example:t" to "2.0.0"),
      )

    val rendered = buildBundlesSection(bundles = bundles, pomLookup = stubLookup())

    val fixturePos = rendered.indexOf("fixture:")
    val toolsPos = rendered.indexOf("tools:")
    assertTrue(fixturePos >= 0, "fixture sub-header missing: '$rendered'")
    assertTrue(toolsPos >= 0, "tools sub-header missing: '$rendered'")
    assertTrue(
      fixturePos < toolsPos,
      "declaration order must be preserved (fixture before tools): '$rendered'",
    )
    assertTrue(rendered.contains("com.example:fix:1.0.0"), "fixture dep missing: '$rendered'")
    assertTrue(rendered.contains("com.example:t:2.0.0"), "tools dep missing: '$rendered'")
  }

  @Test
  fun bundleTreeIncludesTransitiveFromPomLookup() {
    val pomInfos =
      mapOf(
        "com.example:fix:1.0.0" to
          PomInfo(
            parent = null,
            groupId = "com.example",
            artifactId = "fix",
            version = "1.0.0",
            properties = emptyMap(),
            dependencyManagement = emptyList(),
            dependencies =
              listOf(
                PomDependency(
                  groupId = "com.example",
                  artifactId = "fix-impl",
                  version = "1.0.0",
                  scope = null,
                  optional = false,
                )
              ),
          )
      )
    val bundles = mapOf("fixture" to mapOf("com.example:fix" to "1.0.0"))

    val rendered = buildBundlesSection(bundles = bundles, pomLookup = stubLookup(pomInfos))

    assertTrue(rendered.contains("com.example:fix:1.0.0"), "direct dep missing: '$rendered'")
    assertTrue(
      rendered.contains("com.example:fix-impl:1.0.0"),
      "transitive dep missing: '$rendered'",
    )
  }

  @Test
  fun bundlesAreSeparatedByBlankLine() {
    val bundles =
      linkedMapOf(
        "a" to mapOf("com.example:a" to "1.0.0"),
        "b" to mapOf("com.example:b" to "2.0.0"),
      )

    val rendered = buildBundlesSection(bundles = bundles, pomLookup = stubLookup())

    // The two per-bundle blocks should be separated by at least one blank
    // line so visual scanning works the same as main / test sections.
    val lines = rendered.split("\n")
    val aIdx = lines.indexOfFirst { it == "a:" }
    val bIdx = lines.indexOfFirst { it == "b:" }
    assertTrue(aIdx >= 0 && bIdx >= 0, "bundle headers missing in '$rendered'")
    assertTrue(bIdx - aIdx > 2, "expected blank line between bundles: '$rendered'")
  }

  // Smoke-coverage for the "no bundles" path of the higher-level `doTree`
  // when it is wired up: `buildBundlesSection` returning empty must mean
  // the caller emits no header at all (regression-guard for the gating
  // check `bundles.isNotEmpty()` in doTree).
  @Test
  fun emptyBundlesDoesNotEmitHeaderText() {
    val rendered = buildBundlesSection(bundles = emptyMap(), pomLookup = stubLookup())

    assertEquals("", rendered)
    assertTrue(!rendered.contains("bundles:"), "header must not appear when bundles empty")
  }
}
