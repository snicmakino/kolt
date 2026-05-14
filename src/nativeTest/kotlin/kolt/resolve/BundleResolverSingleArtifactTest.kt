package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.config.Repository
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundleResolverSingleArtifactTest {

  // Single Coordinate must produce exactly one resolved jar entry — no POM-derived
  // transitive children should appear, even if the upstream POM declares them. The
  // `[tools]` flow runs against fat / runnable jars, so transitive enumeration is
  // out of scope (R2.4 + design.md §Resolver: transitive-skip path).
  @Test
  fun returnsSingleJarWithNoTransitives() {
    val deps =
      fakeDeps(sha256Results = mapOf("/cache/com/example/tool/1.0.0/tool-1.0.0.jar" to "abc123"))

    val result =
      resolveSingleArtifact(
        coord = Coordinate("com.example", "tool", "1.0.0"),
        classifier = null,
        repos = listOf(Repository(name = "r", url = "https://repo1/")),
        cacheBase = "/cache",
        deps = deps,
      )
    val ok = assertNotNull(result.get())

    assertEquals("/cache/com/example/tool/1.0.0/tool-1.0.0.jar", ok.cachePath)
    assertEquals("abc123", ok.sha256)
    assertEquals("com.example:tool", ok.groupArtifact)
    assertEquals("1.0.0", ok.version)
    assertNull(ok.classifier)
  }

  @Test
  fun classifierIsAppendedToCachePath() {
    val deps =
      fakeDeps(
        sha256Results = mapOf("/cache/com/example/tool/1.0.0/tool-1.0.0-all.jar" to "deadbeef")
      )

    val ok =
      assertNotNull(
        resolveSingleArtifact(
            coord = Coordinate("com.example", "tool", "1.0.0"),
            classifier = "all",
            repos = listOf(Repository(name = "r", url = "https://repo1/")),
            cacheBase = "/cache",
            deps = deps,
          )
          .get()
      )

    assertEquals("/cache/com/example/tool/1.0.0/tool-1.0.0-all.jar", ok.cachePath)
    assertEquals("all", ok.classifier)
  }

  @Test
  fun cacheHitSkipsDownload() {
    val downloads = mutableListOf<String>()
    val deps =
      fakeDeps(
        cachedFiles = mutableSetOf("/cache/com/example/tool/1.0.0/tool-1.0.0.jar"),
        sha256Results = mapOf("/cache/com/example/tool/1.0.0/tool-1.0.0.jar" to "abc"),
        recordDownloads = downloads,
      )

    val ok =
      assertNotNull(
        resolveSingleArtifact(
            coord = Coordinate("com.example", "tool", "1.0.0"),
            classifier = null,
            repos = listOf(Repository(name = "r", url = "https://repo1/")),
            cacheBase = "/cache",
            deps = deps,
          )
          .get()
      )

    assertEquals("abc", ok.sha256)
    assertTrue(downloads.isEmpty(), "warm cache must not trigger network: got $downloads")
  }

  @Test
  fun all404sSurfaceAsDownloadFailedWithAttempts() {
    val deps =
      fakeDeps(
        downloadResult = { url, _ -> Err(DownloadError.HttpFailed(url, 404)) },
        sha256Results = emptyMap(),
      )

    val err =
      assertNotNull(
        resolveSingleArtifact(
            coord = Coordinate("com.example", "missing", "1.0.0"),
            classifier = null,
            repos =
              listOf(
                Repository(name = "r1", url = "https://repo1/"),
                Repository(name = "r2", url = "https://repo2/"),
              ),
            cacheBase = "/cache",
            deps = deps,
          )
          .getError()
      )
    val downloadFailed = assertIs<ResolveError.DownloadFailed>(err)
    assertEquals("com.example:missing", downloadFailed.groupArtifact)
    val attempts =
      assertIs<RepositoryDownloadFailure.AllAttemptsFailed>(downloadFailed.failure).attempts
    assertEquals(2, attempts.size)
    assertEquals("https://repo1//com/example/missing/1.0.0/missing-1.0.0.jar", attempts[0].url)
  }

  // Ensure the existing [classpaths] resolveBundle path stays regression-free under
  // a normal POM that declares no transitives. Cross-checks that adding the new entry
  // point did not perturb the bundle pass.
  @Test
  fun classpathsBundlePathStillWorks() {
    val libPom =
      """
            <project><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0.0</version></project>
        """
        .trimIndent()
    val deps =
      fakeDeps(
        sha256Results = mapOf("/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "h"),
        pomContents = mapOf("/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to libPom),
      )

    val bundle =
      assertNotNull(
        resolveBundle(
            config = kolt.testConfig(),
            bundleName = "tools",
            bundleSeeds = mapOf("com.example:lib" to "1.0.0"),
            existingLock = null,
            cacheBase = "/cache",
            deps = deps,
          )
          .get()
      )
    assertEquals(1, bundle.jars.size)
  }

  private fun fakeDeps(
    cachedFiles: MutableSet<String> = mutableSetOf(),
    sha256Results: Map<String, String> = emptyMap(),
    pomContents: Map<String, String> = emptyMap(),
    downloadResult: ((String, String) -> Result<Unit, DownloadError>)? = null,
    recordDownloads: MutableList<String>? = null,
  ): ResolverDeps {
    return object : ResolverDeps {
      override fun fileExists(path: String): Boolean = path in cachedFiles

      override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

      override fun downloadFile(
        url: String,
        destPath: String,
        headers: Map<String, String>?,
      ): Result<Unit, DownloadError> {
        recordDownloads?.add(url)
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
    }
  }
}
