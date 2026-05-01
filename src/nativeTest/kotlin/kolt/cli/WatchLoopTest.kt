package kolt.cli

import kolt.infra.InotifyEvent
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectWatchPathsTest {

  @Test
  fun buildWatchesCurrentDirAndSources() {
    val config = testConfig(sources = listOf("src"))
    val paths = collectWatchPaths(config, "build")
    assertEquals(listOf(".", "src"), paths)
  }

  @Test
  fun buildIncludesResources() {
    val base = testConfig(sources = listOf("src"))
    val config = base.copy(build = base.build.copy(resources = listOf("res")))
    val paths = collectWatchPaths(config, "build")
    assertEquals(listOf(".", "src", "res"), paths)
  }

  @Test
  fun checkWatchesSameAsBuild() {
    val config = testConfig(sources = listOf("src"))
    assertEquals(collectWatchPaths(config, "build"), collectWatchPaths(config, "check"))
  }

  @Test
  fun runWatchesSameAsBuild() {
    val config = testConfig(sources = listOf("src"))
    assertEquals(collectWatchPaths(config, "build"), collectWatchPaths(config, "run"))
  }

  @Test
  fun testIncludesTestSourcesAndResources() {
    val base = testConfig(sources = listOf("src"), testSources = listOf("test"))
    val config = base.copy(build = base.build.copy(testResources = listOf("test-res")))
    val paths = collectWatchPaths(config, "test")
    assertEquals(listOf(".", "src", "test", "test-res"), paths)
  }

  @Test
  fun testOmitsEmptyTestResources() {
    val config = testConfig(sources = listOf("src"), testSources = listOf("test"))
    val paths = collectWatchPaths(config, "test")
    assertEquals(listOf(".", "src", "test"), paths)
  }

  @Test
  fun multipleSources() {
    val config = testConfig(sources = listOf("src/main", "src/gen"))
    val paths = collectWatchPaths(config, "build")
    assertEquals(listOf(".", "src/main", "src/gen"), paths)
  }

  @Test
  fun currentDirAlwaysFirst() {
    val config = testConfig(sources = listOf("a", "b"))
    assertEquals(".", collectWatchPaths(config, "build").first())
  }

  @Test
  fun deduplicatesDotInSources() {
    val config = testConfig(sources = listOf("."))
    val paths = collectWatchPaths(config, "build")
    assertEquals(listOf("."), paths)
  }

  @Test
  fun deduplicatesOverlappingPaths() {
    val config = testConfig(sources = listOf("src"), testSources = listOf("src"))
    val paths = collectWatchPaths(config, "test")
    assertEquals(listOf(".", "src"), paths)
  }
}

class ShouldTriggerRebuildTest {

  @Test
  fun kotlinSourceTriggers() {
    assertTrue(shouldTriggerRebuild("Main.kt"))
    assertTrue(shouldTriggerRebuild("build.kts"))
  }

  @Test
  fun koltTomlTriggers() {
    assertTrue(shouldTriggerRebuild("kolt.toml"))
  }

  @Test
  fun dotfileIgnored() {
    assertFalse(shouldTriggerRebuild(".hidden"))
    assertFalse(shouldTriggerRebuild(".gitignore"))
  }

  @Test
  fun vimSwapFilesIgnored() {
    assertFalse(shouldTriggerRebuild("Main.kt.swp"))
    assertFalse(shouldTriggerRebuild("Main.kt.swo"))
    assertFalse(shouldTriggerRebuild("Main.kt.swx"))
    assertFalse(shouldTriggerRebuild("4913"))
  }

  @Test
  fun backupAndTempFilesIgnored() {
    assertFalse(shouldTriggerRebuild("Main.kt~"))
    assertFalse(shouldTriggerRebuild("file.tmp"))
  }

  @Test
  fun nonKotlinFileDoesNotTrigger() {
    assertFalse(shouldTriggerRebuild("readme.md"))
    assertFalse(shouldTriggerRebuild("data.json"))
  }

  @Test
  fun emptyNameDoesNotTrigger() {
    assertFalse(shouldTriggerRebuild(""))
  }
}

class HasKoltTomlEventTest {

  @Test
  fun koltTomlEventInRootDirIsDetected() {
    val wdKinds = mapOf(1 to WatchKind.ROOT_DIR)
    val events = listOf(InotifyEvent(wd = 1, mask = 0u, name = "kolt.toml"))
    assertTrue(hasKoltTomlEvent(events, wdKinds))
  }

  @Test
  fun sourceFileEventDoesNotMatchKoltTomlPath() {
    val wdKinds = mapOf(1 to WatchKind.ROOT_DIR, 2 to WatchKind.SOURCE_DIR)
    val events =
      listOf(
        InotifyEvent(wd = 2, mask = 0u, name = "Main.kt"),
        InotifyEvent(wd = 2, mask = 0u, name = "Lib.kts"),
      )
    assertFalse(hasKoltTomlEvent(events, wdKinds))
  }

  @Test
  fun koltTomlInSourceDirIsNotMatched() {
    val wdKinds = mapOf(2 to WatchKind.SOURCE_DIR)
    val events = listOf(InotifyEvent(wd = 2, mask = 0u, name = "kolt.toml"))
    assertFalse(hasKoltTomlEvent(events, wdKinds))
  }

  @Test
  fun mixedEventsRecognizeKoltToml() {
    val wdKinds = mapOf(1 to WatchKind.ROOT_DIR, 2 to WatchKind.SOURCE_DIR)
    val events =
      listOf(
        InotifyEvent(wd = 2, mask = 0u, name = "Main.kt"),
        InotifyEvent(wd = 1, mask = 0u, name = "kolt.toml"),
      )
    assertTrue(hasKoltTomlEvent(events, wdKinds))
  }

  @Test
  fun emptyEventListYieldsFalse() {
    assertFalse(hasKoltTomlEvent(emptyList(), emptyMap()))
  }
}
