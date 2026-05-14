package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import kolt.config.Repository
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BundleResolverProgressTest {

  // Bundle declaration path: three uncached direct deps must surface as
  // (1,3),(2,3),(3,3) ArtifactStart events. resolveBundle delegates to
  // resolveTransitive, so the materialize loop is responsible for the
  // emission — this test pins that the wiring is intact end-to-end through
  // the bundle entry point. Pin for Req 1.1, 1.4.
  @Test
  fun threeUncachedBundleEntriesEmitOneOfThreeTwoOfThreeThreeOfThree() {
    val config = testConfig()
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
      fakeBundleDeps(
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

    val result =
      resolveBundle(
        config = config,
        bundleName = "tools",
        bundleSeeds =
          mapOf("com.example:a" to "1.0.0", "com.example:b" to "2.0.0", "com.example:c" to "3.0.0"),
        existingLock = null,
        cacheBase = "/cache",
        deps = deps,
        progress = sink,
      )
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

  // Lock-reuse path: lockfile matches kolt.toml so the resolver kernel is
  // skipped, but a JAR was evicted from ~/.kolt/cache. The re-download must
  // surface as a single (1,1) ArtifactStart so the user sees activity rather
  // than silence. Pin for Req 1.1, 1.4 on the materialiseBundleJarsFromLock
  // path.
  @Test
  fun lockReuseWithOneEvictedJarEmitsOneOfOne() {
    val config =
      testConfig()
        .copy(
          repositories =
            mapOf("primary" to Repository(name = "primary", url = "https://repo.example.com"))
        )

    // Two bundle deps locked: one cached, one evicted.
    val cachedDep =
      ResolvedDep(
        groupArtifact = "com.example:warm",
        version = "1.0.0",
        sha256 = "hashWarm",
        cachePath = "/cache/com/example/warm/1.0.0/warm-1.0.0.jar",
      )
    val evictedDep =
      ResolvedDep(
        groupArtifact = "com.example:cold",
        version = "2.0.0",
        sha256 = "hashCold",
        cachePath = "/cache/com/example/cold/2.0.0/cold-2.0.0.jar",
      )

    val resolution =
      BundleResolution(jars = emptyList(), classpath = "", deps = listOf(cachedDep, evictedDep))

    val existingLock =
      Lockfile(
        version = LOCKFILE_VERSION,
        kotlin = config.kotlin.version,
        jvmTarget = config.build.jvmTarget,
        dependencies = emptyMap(),
        classpathBundles =
          mapOf(
            "tools" to
              mapOf(
                "com.example:warm" to LockEntry(version = "1.0.0", sha256 = "hashWarm"),
                "com.example:cold" to LockEntry(version = "2.0.0", sha256 = "hashCold"),
              )
          ),
      )

    val deps =
      fakeBundleDeps(
        // Only the warm jar is on disk. The cold jar must be re-downloaded
        // and that re-download must surface as a (1,1) progress event.
        cachedFiles = mutableSetOf("/cache/com/example/warm/1.0.0/warm-1.0.0.jar"),
        sha256Results =
          mapOf(
            "/cache/com/example/warm/1.0.0/warm-1.0.0.jar" to "hashWarm",
            "/cache/com/example/cold/2.0.0/cold-2.0.0.jar" to "hashCold",
          ),
      )
    val sink = RecordingResolverProgressSink()

    val result =
      materialiseBundleJarsFromLock(
        resolution = resolution,
        config = config,
        existingLock = existingLock,
        bundleName = "tools",
        cacheBase = "/cache",
        deps = deps,
        progress = sink,
      )
    assertNotNull(result.get())

    val expected =
      listOf<RecordedProgressEvent>(
        RecordedProgressEvent.ArtifactStart(1, 1, "com.example:cold", "2.0.0")
      )
    assertEquals(expected, sink.events)
  }

  private fun fakeBundleDeps(
    cachedFiles: MutableSet<String> = mutableSetOf(),
    sha256Results: Map<String, String> = emptyMap(),
    pomContents: Map<String, String> = emptyMap(),
  ): ResolverDeps {
    return object : ResolverDeps {
      override fun fileExists(path: String): Boolean = path in cachedFiles

      override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

      override fun downloadFile(
        url: String,
        destPath: String,
        headers: Map<String, String>?,
      ): Result<Unit, DownloadError> {
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
