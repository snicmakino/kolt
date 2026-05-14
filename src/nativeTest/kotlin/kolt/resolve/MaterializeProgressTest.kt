package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import kolt.config.MAVEN_CENTRAL_BASE
import kolt.config.Repository
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MaterializeProgressTest {

  @Test
  fun threeUncachedDepsEmitOneOfThreeTwoOfThreeThreeOfThree() {
    val config =
      testConfig()
        .copy(
          dependencies =
            mapOf(
              "com.example:a" to "1.0.0",
              "com.example:b" to "2.0.0",
              "com.example:c" to "3.0.0",
            )
        )
    val aPom =
      """
            <project><groupId>com.example</groupId><artifactId>a</artifactId><version>1.0.0</version></project>
        """
        .trimIndent()
    val bPom =
      """
            <project><groupId>com.example</groupId><artifactId>b</artifactId><version>2.0.0</version></project>
        """
        .trimIndent()
    val cPom =
      """
            <project><groupId>com.example</groupId><artifactId>c</artifactId><version>3.0.0</version></project>
        """
        .trimIndent()

    val deps =
      fakeDeps(
        sha256Results =
          mapOf(
            "/cache/com/example/a/1.0.0/a-1.0.0.jar" to "hashA",
            "/cache/com/example/b/2.0.0/b-2.0.0.jar" to "hashB",
            "/cache/com/example/c/3.0.0/c-3.0.0.jar" to "hashC",
          ),
        pomContents =
          mapOf(
            "/cache/com/example/a/1.0.0/a-1.0.0.pom" to aPom,
            "/cache/com/example/b/2.0.0/b-2.0.0.pom" to bPom,
            "/cache/com/example/c/3.0.0/c-3.0.0.pom" to cPom,
          ),
      )
    val sink = RecordingResolverProgressSink()

    val result = resolveTransitive(config, null, "/cache", deps, progress = sink)
    assertNotNull(result.get())

    val artifactStarts = sink.events.filterIsInstance<RecordedProgressEvent.ArtifactStart>()
    assertEquals(3, artifactStarts.size)
    val gaToEvent = artifactStarts.associateBy { it.groupArtifact }
    assertEquals(
      RecordedProgressEvent.ArtifactStart(1, 3, "com.example:a", "1.0.0"),
      gaToEvent["com.example:a"],
    )
    assertEquals(
      RecordedProgressEvent.ArtifactStart(2, 3, "com.example:b", "2.0.0"),
      gaToEvent["com.example:b"],
    )
    assertEquals(
      RecordedProgressEvent.ArtifactStart(3, 3, "com.example:c", "3.0.0"),
      gaToEvent["com.example:c"],
    )
    val indices = artifactStarts.map { it.index }
    assertEquals(listOf(1, 2, 3), indices)
  }

  @Test
  fun oneCachedAndOneUncachedEmitsOnlyOneOfOne() {
    val config =
      testConfig()
        .copy(dependencies = mapOf("com.example:cached" to "1.0.0", "com.example:fresh" to "1.0.0"))
    val cachedPom =
      """
            <project><groupId>com.example</groupId><artifactId>cached</artifactId><version>1.0.0</version></project>
        """
        .trimIndent()
    val freshPom =
      """
            <project><groupId>com.example</groupId><artifactId>fresh</artifactId><version>1.0.0</version></project>
        """
        .trimIndent()

    val deps =
      fakeDeps(
        cachedFiles =
          mutableSetOf(
            "/cache/com/example/cached/1.0.0/cached-1.0.0.jar",
            "/cache/com/example/cached/1.0.0/cached-1.0.0.pom",
          ),
        sha256Results =
          mapOf(
            "/cache/com/example/cached/1.0.0/cached-1.0.0.jar" to "hashCached",
            "/cache/com/example/fresh/1.0.0/fresh-1.0.0.jar" to "hashFresh",
          ),
        pomContents =
          mapOf(
            "/cache/com/example/cached/1.0.0/cached-1.0.0.pom" to cachedPom,
            "/cache/com/example/fresh/1.0.0/fresh-1.0.0.pom" to freshPom,
          ),
      )
    val sink = RecordingResolverProgressSink()

    val result = resolveTransitive(config, null, "/cache", deps, progress = sink)
    assertNotNull(result.get())

    val artifactStarts = sink.events.filterIsInstance<RecordedProgressEvent.ArtifactStart>()
    assertEquals(1, artifactStarts.size)
    val event = artifactStarts.single()
    assertEquals(1, event.index)
    assertEquals(1, event.total)
    assertEquals("com.example:fresh", event.groupArtifact)
    assertEquals("1.0.0", event.version)
  }

  @Test
  fun fullyWarmCacheEmitsNothing() {
    val config = testConfig().copy(dependencies = mapOf("com.example:lib" to "1.0.0"))
    val libPom =
      """
            <project><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0.0</version></project>
        """
        .trimIndent()

    val deps =
      fakeDeps(
        cachedFiles =
          mutableSetOf(
            "/cache/com/example/lib/1.0.0/lib-1.0.0.jar",
            "/cache/com/example/lib/1.0.0/lib-1.0.0.pom",
          ),
        sha256Results = mapOf("/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "hash1"),
        pomContents = mapOf("/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to libPom),
      )
    val sink = RecordingResolverProgressSink()

    val result = resolveTransitive(config, null, "/cache", deps, progress = sink)
    assertNotNull(result.get())

    assertEquals(emptyList(), sink.events)
  }

  @Test
  fun fourOhFourThenTwoHundredEmitsArtifactStartFollowedByRetryAgainstSecondRepo() {
    val repo1 = "https://repo1.example.com"
    val repo2 = "https://repo2.example.com"
    val config =
      testConfig(
        dependencies = mapOf("com.example:lib" to "1.0.0"),
        repositories =
          mapOf(
            "primary" to Repository(name = "primary", url = repo1),
            "fallback" to Repository(name = "fallback", url = repo2),
          ),
      )
    val pomXml =
      """
            <project><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0.0</version></project>
        """
        .trimIndent()

    val cachedFiles = mutableSetOf<String>()
    val deps =
      object : ResolverDeps {
        val pomContents = mapOf("/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to pomXml)

        override fun fileExists(path: String): Boolean = path in cachedFiles

        override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

        override fun downloadFile(
          url: String,
          destPath: String,
          headers: Map<String, String>?,
        ): Result<Unit, DownloadError> {
          if (destPath.endsWith(".jar") && url.startsWith(repo1)) {
            return Err(DownloadError.HttpFailed(url, 404))
          }
          cachedFiles.add(destPath)
          return Ok(Unit)
        }

        override fun computeSha256(filePath: String): Result<String, Sha256Error> = Ok("hashLib")

        override fun readFileContent(path: String): Result<String, OpenFailed> {
          val content = pomContents[path] ?: return Err(OpenFailed(path))
          return Ok(content)
        }
      }
    val sink = RecordingResolverProgressSink()

    val result = resolveTransitive(config, null, "/cache", deps, progress = sink)
    assertNotNull(result.get())

    val expected =
      listOf<RecordedProgressEvent>(
        RecordedProgressEvent.ArtifactStart(1, 1, "com.example:lib", "1.0.0"),
        RecordedProgressEvent.RetryAgainst(repo2),
      )
    assertEquals(expected, sink.events)
  }

  @Test
  fun binaryCachedAndMissingSourcesEmitsNothingForSourcesFetch() {
    val config = testConfig().copy(dependencies = mapOf("com.example:lib" to "1.0.0"))
    val libPom =
      """
            <project><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0.0</version></project>
        """
        .trimIndent()

    // Binary cached, sources NOT cached. Per resolveSourcesPath semantics: when
    // binaryWasCached is true and sources is not on disk, no network probe runs;
    // the sink must remain silent regardless. This pins Req 5.1.
    val deps =
      fakeDeps(
        cachedFiles =
          mutableSetOf(
            "/cache/com/example/lib/1.0.0/lib-1.0.0.jar",
            "/cache/com/example/lib/1.0.0/lib-1.0.0.pom",
          ),
        sha256Results = mapOf("/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "hash1"),
        pomContents = mapOf("/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to libPom),
      )
    val sink = RecordingResolverProgressSink()

    val result = resolveTransitive(config, null, "/cache", deps, progress = sink)
    assertNotNull(result.get())

    assertEquals(emptyList(), sink.events)
  }

  private fun fakeDeps(
    cachedFiles: MutableSet<String> = mutableSetOf(),
    sha256Results: Map<String, String> = emptyMap(),
    pomContents: Map<String, String> = emptyMap(),
    downloadErrors: Map<String, DownloadError> = emptyMap(),
  ): ResolverDeps {
    return object : ResolverDeps {
      override fun fileExists(path: String): Boolean = path in cachedFiles

      override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

      override fun downloadFile(
        url: String,
        destPath: String,
        headers: Map<String, String>?,
      ): Result<Unit, DownloadError> {
        val forcedError = downloadErrors[url]
        if (forcedError != null) return Err(forcedError)
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

  // Maven Central base is referenced just to keep the import linked in case a
  // future test uses it; resolved tests above use explicit repositories or
  // testConfig defaults.
  @Suppress("unused") private val mavenCentralBase = MAVEN_CENTRAL_BASE
}
