package kolt.usertool

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import kolt.config.KoltPaths
import kolt.infra.CopyFailed
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kolt.resolve.Coordinate
import kolt.resolve.LockEntry
import kolt.resolve.Lockfile
import kolt.resolve.ResolverDeps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolResolutionTest {

  private val paths = KoltPaths(home = "/home/u")
  private val repos = listOf("https://central/")

  // The Maven-layout cacheBase that resolveSingleArtifact writes into.
  private val cacheBase = "/home/u/.kolt/cache"

  // ----- Cache hit: jar exists at toolsBundleJarPath AND lockfile pin matches -----
  @Test
  fun cacheHitReturnsHandleWithoutNetworkOrCopy() {
    val toolsJarPath = paths.toolsBundleJarPath("ktlint", "1.3.1", "ktlint-cli-1.3.1-all.jar")
    val deps =
      fakeDeps(
        cachedFiles = mutableSetOf(toolsJarPath),
        sha256Results = mapOf(toolsJarPath to "abc123"),
      )

    val lockfile =
      lockfileWithToolPin(
        alias = "ktlint",
        innerKey = "com.pinterest.ktlint:ktlint-cli:all",
        version = "1.3.1",
        sha256 = "abc123",
      )

    val handle =
      assertNotNull(
        ensureTool(
            alias = "ktlint",
            entry =
              ToolEntry(
                Coordinate("com.pinterest.ktlint", "ktlint-cli", "1.3.1"),
                classifier = "all",
              ),
            paths = paths,
            lockfile = lockfile,
            netDeps = deps,
          )
          .get()
      )

    assertEquals(toolsJarPath, handle.jarPath)
    assertEquals("all", handle.classifier)
    assertFalse(handle.lockfileChanged, "warm cache must not require lockfile rewrite")
    assertTrue(deps.downloads.isEmpty(), "warm cache must skip download: ${deps.downloads}")
    assertTrue(deps.copies.isEmpty(), "warm cache must skip copy: ${deps.copies}")
  }

  @Test
  fun cacheHitForCoordsWithoutClassifierUsesSimpleInnerKey() {
    val toolsJarPath = paths.toolsBundleJarPath("detekt", "1.23.6", "detekt-cli-1.23.6.jar")
    val deps =
      fakeDeps(
        cachedFiles = mutableSetOf(toolsJarPath),
        sha256Results = mapOf(toolsJarPath to "xyz"),
      )

    val lockfile =
      lockfileWithToolPin(
        alias = "detekt",
        innerKey = "io.gitlab.arturbosch.detekt:detekt-cli",
        version = "1.23.6",
        sha256 = "xyz",
      )

    val handle =
      assertNotNull(
        ensureTool(
            alias = "detekt",
            entry =
              ToolEntry(
                Coordinate("io.gitlab.arturbosch.detekt", "detekt-cli", "1.23.6"),
                classifier = null,
              ),
            paths = paths,
            lockfile = lockfile,
            netDeps = deps,
          )
          .get()
      )

    assertEquals(toolsJarPath, handle.jarPath)
    assertTrue(deps.downloads.isEmpty())
  }

  // ----- Cache miss + no lockfile pin (first run): fetch + copy + lockfileChanged -----
  @Test
  fun cacheMissNoPinFetchesCopiesAndMarksLockfileChanged() {
    val coords = Coordinate("com.example", "tool", "1.0.0")
    val mavenJarPath = "$cacheBase/com/example/tool/1.0.0/tool-1.0.0-all.jar"
    val toolsJarPath = paths.toolsBundleJarPath("mytool", "1.0.0", "tool-1.0.0-all.jar")
    val deps = fakeDeps(sha256Results = mapOf(mavenJarPath to "freshhash"))

    val lockfile = emptyLockfile()

    val handle =
      assertNotNull(
        ensureTool(
            alias = "mytool",
            entry = ToolEntry(coords, classifier = "all"),
            paths = paths,
            lockfile = lockfile,
            netDeps = deps,
          )
          .get()
      )

    assertEquals(toolsJarPath, handle.jarPath)
    assertEquals(coords, handle.resolvedCoords)
    assertEquals("all", handle.classifier)
    assertTrue(handle.lockfileChanged, "first-run cache miss must request lockfile write-through")
    assertEquals(1, deps.downloads.size, "expected single fetch: got ${deps.downloads}")
    assertEquals(
      listOf(mavenJarPath to toolsJarPath),
      deps.copies,
      "must copy from Maven cache to per-alias path",
    )
  }

  // ----- Cache miss + lockfile pin present: fetch per pin, lockfileChanged=false -----
  @Test
  fun cacheMissWithMatchingPinFetchesAndKeepsLockfileUnchanged() {
    val coords = Coordinate("com.pinterest.ktlint", "ktlint-cli", "1.3.1")
    val mavenJarPath = "$cacheBase/com/pinterest/ktlint/ktlint-cli/1.3.1/ktlint-cli-1.3.1-all.jar"
    val toolsJarPath = paths.toolsBundleJarPath("ktlint", "1.3.1", "ktlint-cli-1.3.1-all.jar")
    val deps = fakeDeps(sha256Results = mapOf(mavenJarPath to "pinnedhash"))

    val lockfile =
      lockfileWithToolPin(
        alias = "ktlint",
        innerKey = "com.pinterest.ktlint:ktlint-cli:all",
        version = "1.3.1",
        sha256 = "pinnedhash",
      )

    val handle =
      assertNotNull(
        ensureTool(
            alias = "ktlint",
            entry = ToolEntry(coords, classifier = "all"),
            paths = paths,
            lockfile = lockfile,
            netDeps = deps,
          )
          .get()
      )

    assertEquals(toolsJarPath, handle.jarPath)
    assertFalse(handle.lockfileChanged, "matching pin must not flag lockfile as changed")
    assertEquals(1, deps.downloads.size, "must fetch into Maven cache")
    assertEquals(listOf(mavenJarPath to toolsJarPath), deps.copies)
  }

  // ----- Helpers -----

  private fun emptyLockfile(): Lockfile =
    Lockfile(
      version = 4,
      kotlin = "2.1.0",
      jvmTarget = "17",
      dependencies = emptyMap(),
      classpathBundles = emptyMap(),
      toolsBundles = emptyMap(),
    )

  private fun lockfileWithToolPin(
    alias: String,
    innerKey: String,
    version: String,
    sha256: String,
  ): Lockfile =
    Lockfile(
      version = 4,
      kotlin = "2.1.0",
      jvmTarget = "17",
      dependencies = emptyMap(),
      classpathBundles = emptyMap(),
      toolsBundles =
        mapOf(alias to mapOf(innerKey to LockEntry(version = version, sha256 = sha256))),
    )

  private class FakeNetDeps(
    val cachedFiles: MutableSet<String>,
    val sha256Results: Map<String, String>,
    val pomContents: Map<String, String>,
    val downloadResult: ((String, String) -> Result<Unit, DownloadError>)?,
    val copyResult: ((String, String) -> Result<Unit, CopyFailed>)?,
    val downloads: MutableList<String> = mutableListOf(),
    val copies: MutableList<Pair<String, String>> = mutableListOf(),
  ) : ResolverDeps, ToolFsDeps {
    override fun fileExists(path: String): Boolean = path in cachedFiles

    override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

    override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
      downloads.add(url)
      if (downloadResult != null) return downloadResult.invoke(url, destPath)
      cachedFiles.add(destPath)
      return Ok(Unit)
    }

    override fun computeSha256(filePath: String): Result<String, Sha256Error> {
      val hash = sha256Results[filePath] ?: return Err(Sha256Error(filePath))
      return Ok(hash)
    }

    override fun readFileContent(path: String): Result<String, OpenFailed> {
      val content = pomContents[path] ?: return Err(OpenFailed(path))
      return Ok(content)
    }

    override fun copyFile(src: String, dest: String): Result<Unit, CopyFailed> {
      copies.add(src to dest)
      if (copyResult != null) return copyResult.invoke(src, dest)
      cachedFiles.add(dest)
      return Ok(Unit)
    }
  }

  private fun fakeDeps(
    cachedFiles: MutableSet<String> = mutableSetOf(),
    sha256Results: Map<String, String> = emptyMap(),
    pomContents: Map<String, String> = emptyMap(),
    downloadResult: ((String, String) -> Result<Unit, DownloadError>)? = null,
    copyResult: ((String, String) -> Result<Unit, CopyFailed>)? = null,
  ) = FakeNetDeps(cachedFiles, sha256Results, pomContents, downloadResult, copyResult)
}
