package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.resolve.Coordinate
import kolt.resolve.LockEntry
import kolt.resolve.ResolveError
import kolt.resolve.SingleArtifact
import kolt.usertool.ToolEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DependencyCommandsToolsTest {

  // ----- buildToolsBundleLockMap: pure orchestration over tool entries -----

  @Test
  fun emptyToolsMapProducesEmptyLockBundles() {
    val result = buildToolsBundleLockMap(emptyMap()) { _, _, _ -> error("must not call resolver") }
    assertEquals(emptyMap(), result.get())
  }

  @Test
  fun singleToolEntryProducesAliasedLockBundle() {
    val tools =
      mapOf("ktlint" to ToolEntry(Coordinate("g", "ktlint-cli", "1.3.1"), classifier = "all"))
    val result =
      buildToolsBundleLockMap(tools) { coord, classifier, _ ->
        Ok(
          SingleArtifact(
            groupArtifact = "${coord.group}:${coord.artifact}",
            version = coord.version,
            classifier = classifier,
            cachePath = "/cache/${coord.artifact}-${coord.version}.jar",
            sha256 = "fakesha-${coord.version}",
          )
        )
      }
    val toolsBundles = assertNotNull(result.get())
    assertEquals(setOf("ktlint"), toolsBundles.keys)
    val ktlintInner = toolsBundles.getValue("ktlint")
    assertEquals(
      mapOf("g:ktlint-cli:all" to LockEntry(version = "1.3.1", sha256 = "fakesha-1.3.1")),
      ktlintInner,
    )
  }

  @Test
  fun multipleToolEntriesProduceAliasedLockBundles() {
    val tools =
      mapOf(
        "ktlint" to ToolEntry(Coordinate("g", "ktlint-cli", "1.3.1"), classifier = null),
        "detekt" to ToolEntry(Coordinate("io.detekt", "detekt-cli", "1.23.6"), classifier = null),
      )
    val result =
      buildToolsBundleLockMap(tools) { coord, classifier, _ ->
        Ok(
          SingleArtifact(
            groupArtifact = "${coord.group}:${coord.artifact}",
            version = coord.version,
            classifier = classifier,
            cachePath = "/cache/${coord.artifact}-${coord.version}.jar",
            sha256 = "sha-${coord.artifact}",
          )
        )
      }
    val toolsBundles = assertNotNull(result.get())
    assertEquals(setOf("ktlint", "detekt"), toolsBundles.keys)
    assertTrue(toolsBundles.getValue("ktlint").containsKey("g:ktlint-cli"))
    assertTrue(toolsBundles.getValue("detekt").containsKey("io.detekt:detekt-cli"))
  }

  @Test
  fun resolverFailureBubblesUp() {
    val tools = mapOf("bad" to ToolEntry(Coordinate("g", "a", "1.0"), classifier = null))
    val result =
      buildToolsBundleLockMap(tools) { _, _, _ -> Err(ResolveError.DirectoryCreateFailed("/x")) }
    assertNotNull(result.getError())
  }
}
