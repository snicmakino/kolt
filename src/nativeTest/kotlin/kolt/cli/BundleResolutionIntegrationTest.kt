package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.build.ResolvedJar
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kolt.resolve.BundleResolution
import kolt.resolve.LockEntry
import kolt.resolve.Lockfile
import kolt.resolve.Origin
import kolt.resolve.ResolveError
import kolt.resolve.ResolvedDep
import kolt.resolve.ResolverDeps
import kolt.resolve.buildLockfileFromResolved
import kolt.resolve.materialiseBundleJarsFromLock
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BundleResolutionIntegrationTest {

  // Req 4.1: each bundle's classpath becomes a colon-joined absolute path
  // string in the outcome. Bundles do not pollute mainClasspath / mainJars.
  @Test
  fun integrateBundleClasspathsPopulatesOutcomeWithEachBundle() {
    val baseOutcome =
      JvmResolutionOutcome(
        mainClasspath = "/cache/main.jar",
        mainJars = listOf(ResolvedJar("/cache/main.jar", "com.example:main:1.0.0")),
        allJars = listOf(ResolvedJar("/cache/main.jar", "com.example:main:1.0.0")),
      )

    val bundleResolutions =
      mapOf(
        "fixture" to
          BundleResolution(
            jars =
              listOf(
                ResolvedJar("/cache/com/example/fix/1.0.0/fix-1.0.0.jar", "com.example:fix:1.0.0")
              ),
            classpath = "/cache/com/example/fix/1.0.0/fix-1.0.0.jar",
            deps =
              listOf(
                ResolvedDep(
                  groupArtifact = "com.example:fix",
                  version = "1.0.0",
                  sha256 = "fixSha",
                  cachePath = "/cache/com/example/fix/1.0.0/fix-1.0.0.jar",
                  origin = Origin.MAIN,
                )
              ),
          ),
        "tools" to
          BundleResolution(
            jars =
              listOf(
                ResolvedJar("/cache/com/example/t/1.0.0/t-1.0.0.jar", "com.example:t:1.0.0"),
                ResolvedJar("/cache/com/example/u/2.0.0/u-2.0.0.jar", "com.example:u:2.0.0"),
              ),
            classpath =
              "/cache/com/example/t/1.0.0/t-1.0.0.jar:/cache/com/example/u/2.0.0/u-2.0.0.jar",
            deps =
              listOf(
                ResolvedDep(
                  groupArtifact = "com.example:t",
                  version = "1.0.0",
                  sha256 = "tSha",
                  cachePath = "/cache/com/example/t/1.0.0/t-1.0.0.jar",
                  origin = Origin.MAIN,
                ),
                ResolvedDep(
                  groupArtifact = "com.example:u",
                  version = "2.0.0",
                  sha256 = "uSha",
                  cachePath = "/cache/com/example/u/2.0.0/u-2.0.0.jar",
                  origin = Origin.MAIN,
                  transitive = true,
                ),
              ),
          ),
      )

    val merged = integrateBundleClasspaths(baseOutcome, bundleResolutions)

    // main classpath is unaffected by bundles.
    assertEquals("/cache/main.jar", merged.mainClasspath)
    assertEquals(1, merged.mainJars.size)

    assertEquals(2, merged.bundleClasspaths.size)
    assertEquals("/cache/com/example/fix/1.0.0/fix-1.0.0.jar", merged.bundleClasspaths["fixture"])
    assertEquals(
      "/cache/com/example/t/1.0.0/t-1.0.0.jar:/cache/com/example/u/2.0.0/u-2.0.0.jar",
      merged.bundleClasspaths["tools"],
    )
    assertEquals(2, merged.bundleJars.size)
    assertEquals(2, merged.bundleJars["tools"]!!.size)
  }

  // Req 4.1: lockfile classpathBundles is populated from per-bundle resolved
  // deps. The shape exactly mirrors main `dependencies` (Map<GA, LockEntry>).
  @Test
  fun buildLockfileFromResolvedWritesBundleEntries() {
    val config = testConfig()
    val mainDeps =
      listOf(
        ResolvedDep(
          groupArtifact = "com.example:main",
          version = "1.0.0",
          sha256 = "mainSha",
          cachePath = "/cache/main.jar",
          origin = Origin.MAIN,
        )
      )
    val bundleDeps =
      mapOf(
        "fixture" to
          listOf(
            ResolvedDep(
              groupArtifact = "com.example:fix",
              version = "1.0.0",
              sha256 = "fixSha",
              cachePath = "/cache/fix.jar",
              origin = Origin.MAIN,
            )
          ),
        "tools" to
          listOf(
            ResolvedDep(
              groupArtifact = "com.example:t",
              version = "1.0.0",
              sha256 = "tSha",
              cachePath = "/cache/t.jar",
              origin = Origin.MAIN,
            ),
            ResolvedDep(
              groupArtifact = "com.example:u",
              version = "2.0.0",
              sha256 = "uSha",
              cachePath = "/cache/u.jar",
              origin = Origin.MAIN,
              transitive = true,
            ),
          ),
      )

    val lockfile = buildLockfileFromResolved(config, mainDeps, bundleDeps)

    assertEquals(4, lockfile.version)
    assertEquals(1, lockfile.dependencies.size)
    assertEquals("mainSha", lockfile.dependencies["com.example:main"]?.sha256)

    assertEquals(2, lockfile.classpathBundles.size)

    val fixture = assertNotNull(lockfile.classpathBundles["fixture"])
    assertEquals(1, fixture.size)
    assertEquals("fixSha", fixture["com.example:fix"]?.sha256)

    val tools = assertNotNull(lockfile.classpathBundles["tools"])
    assertEquals(2, tools.size)
    assertEquals("tSha", tools["com.example:t"]?.sha256)
    assertEquals("uSha", tools["com.example:u"]?.sha256)
    assertEquals(true, tools["com.example:u"]?.transitive)
  }

  // Req 4.1: orchestration resolves every bundle declared in config.classpaths.
  @Test
  fun resolveAllBundlesResolvesEveryDeclaredBundle() {
    val config =
      testConfig()
        .copy(
          classpaths =
            mapOf(
              "fixture" to mapOf("com.example:fix" to "1.0.0"),
              "tools" to mapOf("com.example:t" to "1.0.0"),
            )
        )

    val pomFix =
      """
            <project><groupId>com.example</groupId><artifactId>fix</artifactId><version>1.0.0</version></project>
        """
        .trimIndent()
    val pomT =
      """
            <project><groupId>com.example</groupId><artifactId>t</artifactId><version>1.0.0</version></project>
        """
        .trimIndent()

    val deps =
      countingDeps(
        sha256Results =
          mapOf(
            "/cache/com/example/fix/1.0.0/fix-1.0.0.jar" to "fixSha",
            "/cache/com/example/t/1.0.0/t-1.0.0.jar" to "tSha",
          ),
        pomContents =
          mapOf(
            "/cache/com/example/fix/1.0.0/fix-1.0.0.pom" to pomFix,
            "/cache/com/example/t/1.0.0/t-1.0.0.pom" to pomT,
          ),
      )

    val result =
      assertNotNull(
        resolveAllBundles(config, existingLock = null, cacheBase = "/cache", resolverDeps = deps)
          .get()
      )

    assertEquals(2, result.size)

    val fixture = assertNotNull(result["fixture"])
    assertEquals(1, fixture.jars.size)
    assertEquals("/cache/com/example/fix/1.0.0/fix-1.0.0.jar", fixture.classpath)

    val tools = assertNotNull(result["tools"])
    assertEquals(1, tools.jars.size)
    assertEquals("/cache/com/example/t/1.0.0/t-1.0.0.jar", tools.classpath)
  }

  // Req 4.4: when a bundle's declaration matches the locked entries, no fresh
  // resolve work is performed (no downloads, no POM lookups).
  @Test
  fun resolveAllBundlesSkipsResolveWhenDeclarationMatchesLock() {
    val config =
      testConfig().copy(classpaths = mapOf("fixture" to mapOf("com.example:fix" to "1.0.0")))

    val existingLock =
      Lockfile(
        version = 4,
        kotlin = config.kotlin.version,
        jvmTarget = config.build.jvmTarget,
        dependencies = emptyMap(),
        classpathBundles =
          mapOf(
            "fixture" to
              mapOf(
                "com.example:fix" to
                  LockEntry(version = "1.0.0", sha256 = "fixSha", transitive = false, test = false)
              )
          ),
      )

    // No POMs configured: any attempt to resolveTransitive would explode
    // on a missing pom lookup. cachedFiles is pre-populated so the
    // materialise step also short-circuits without downloads.
    val deps =
      countingDeps(
        cachedFiles = mutableSetOf("/cache/com/example/fix/1.0.0/fix-1.0.0.jar"),
        sha256Results = emptyMap(),
        pomContents = emptyMap(),
      )

    val result =
      assertNotNull(
        resolveAllBundles(
            config,
            existingLock = existingLock,
            cacheBase = "/cache",
            resolverDeps = deps,
          )
          .get()
      )

    val fixture = assertNotNull(result["fixture"])
    assertEquals(1, fixture.jars.size)
    assertEquals("/cache/com/example/fix/1.0.0/fix-1.0.0.jar", fixture.jars[0].cachePath)
    assertEquals("/cache/com/example/fix/1.0.0/fix-1.0.0.jar", fixture.classpath)
    assertEquals(
      0,
      deps.downloadCount,
      "no downloads should occur when declaration matches lock and cache is warm",
    )
    assertEquals(0, deps.readFileCount, "no POM lookups should occur when declaration matches lock")
  }

  // A clean ~/.kolt/cache plus an existing lockfile that records bundle
  // jars must materialise those jars during `kolt fetch` (which routes
  // through `resolveAllBundles`). Otherwise subsequent kolt build /
  // kolt test reads bundle classpaths through the lockfile-trusted reuse
  // path and hands consumers paths to non-existent jars, surfacing as
  // `Unresolved reference` at compile time.
  @Test
  fun resolveAllBundlesMaterialisesMissingJarsWhenLockMatchesAndCacheIsCold() {
    val config =
      testConfig().copy(classpaths = mapOf("fixture" to mapOf("com.example:fix" to "1.0.0")))

    val existingLock =
      Lockfile(
        version = 4,
        kotlin = config.kotlin.version,
        jvmTarget = config.build.jvmTarget,
        dependencies = emptyMap(),
        classpathBundles =
          mapOf(
            "fixture" to
              mapOf(
                "com.example:fix" to
                  LockEntry(version = "1.0.0", sha256 = "fixSha", transitive = false, test = false)
              )
          ),
      )

    // No POMs (no fresh resolve walk), but the post-download sha verify
    // must succeed against the lock entry so a real Sha256Mismatch path
    // stays reachable. cachedFiles starts empty — clean cache.
    val deps =
      countingDeps(
        sha256Results = mapOf("/cache/com/example/fix/1.0.0/fix-1.0.0.jar" to "fixSha"),
        pomContents = emptyMap(),
      )

    val result =
      assertNotNull(
        resolveAllBundles(
            config,
            existingLock = existingLock,
            cacheBase = "/cache",
            resolverDeps = deps,
          )
          .get()
      )

    val fixture = assertNotNull(result["fixture"])
    assertEquals(1, fixture.jars.size)
    assertEquals("/cache/com/example/fix/1.0.0/fix-1.0.0.jar", fixture.jars[0].cachePath)
    assertEquals(1, deps.downloadCount, "missing bundle jar must be downloaded")
    assertEquals(0, deps.readFileCount, "no POM lookups should occur when declaration matches lock")
  }

  // When a freshly downloaded bundle jar's sha256 does not match the
  // locked entry, the materialise step must surface the same Sha256Mismatch
  // the main-deps materialise() raises — this is the only line of defense
  // once the lockfile-trusted reuse path is doing I/O.
  @Test
  fun resolveAllBundlesReportsSha256MismatchWhenDownloadedBundleJarDiffersFromLock() {
    val config =
      testConfig().copy(classpaths = mapOf("fixture" to mapOf("com.example:fix" to "1.0.0")))

    val existingLock =
      Lockfile(
        version = 4,
        kotlin = config.kotlin.version,
        jvmTarget = config.build.jvmTarget,
        dependencies = emptyMap(),
        classpathBundles =
          mapOf(
            "fixture" to
              mapOf(
                "com.example:fix" to
                  LockEntry(
                    version = "1.0.0",
                    sha256 = "expectedSha",
                    transitive = false,
                    test = false,
                  )
              )
          ),
      )

    val deps =
      countingDeps(
        sha256Results = mapOf("/cache/com/example/fix/1.0.0/fix-1.0.0.jar" to "tamperedSha"),
        pomContents = emptyMap(),
      )

    val outcome =
      resolveAllBundles(
        config,
        existingLock = existingLock,
        cacheBase = "/cache",
        resolverDeps = deps,
      )

    val error = outcome.getError()
    assertTrue(
      error is ResolveError.Sha256Mismatch,
      "expected ResolveError.Sha256Mismatch, got: $error",
    )
  }

  // The materialise step must derive the download URL from `dep.cachePath`,
  // not from `parseCoordinate(dep.groupArtifact)`. KMP module-metadata
  // redirects (e.g. `kotlinx-coroutines-core` ⇒ `-core-jvm`) are not
  // persisted in the lockfile, so a redirect-shaped cachePath that
  // diverges from the GA must still produce a URL pointing at the
  // redirected jar. This direct call bypasses reuseBundleFromLock so
  // we can hand the function a redirected cachePath / non-redirected GA
  // pair the lockfile alone could never round-trip.
  @Test
  fun materialiseBundleJarsFromLockBuildsUrlFromCachePathNotGroupArtifact() {
    val config = testConfig().copy(repositories = mapOf("central" to "https://example.org/m2"))

    val redirectedRelativePath = "org/example/lib-jvm/1.0.0/lib-jvm-1.0.0.jar"
    val redirectedCachePath = "/cache/$redirectedRelativePath"
    val expectedUrl = "https://example.org/m2/$redirectedRelativePath"

    val downloadedUrls = mutableListOf<String>()
    val deps =
      object : ResolverDeps {
        val cached = mutableSetOf<String>()

        override fun fileExists(path: String): Boolean = path in cached

        override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

        override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
          downloadedUrls.add(url)
          cached.add(destPath)
          return Ok(Unit)
        }

        override fun computeSha256(filePath: String): Result<String, Sha256Error> = Ok("sha")

        override fun readFileContent(path: String): Result<String, OpenFailed> =
          Err(OpenFailed(path))
      }

    val resolution =
      BundleResolution(
        jars = listOf(ResolvedJar(redirectedCachePath, "org.example:lib:1.0.0")),
        classpath = redirectedCachePath,
        deps =
          listOf(
            ResolvedDep(
              groupArtifact = "org.example:lib",
              version = "1.0.0",
              sha256 = "sha",
              cachePath = redirectedCachePath,
              origin = Origin.MAIN,
            )
          ),
      )

    val existingLock =
      Lockfile(
        version = 4,
        kotlin = config.kotlin.version,
        jvmTarget = config.build.jvmTarget,
        dependencies = emptyMap(),
        classpathBundles =
          mapOf(
            "redirected" to
              mapOf(
                "org.example:lib" to
                  LockEntry(version = "1.0.0", sha256 = "sha", transitive = false, test = false)
              )
          ),
      )

    val outcome =
      materialiseBundleJarsFromLock(
        resolution = resolution,
        config = config,
        existingLock = existingLock,
        bundleName = "redirected",
        cacheBase = "/cache",
        deps = deps,
      )

    assertTrue(outcome.get() != null, "expected Ok, got ${outcome.getError()}")
    assertEquals(listOf(expectedUrl), downloadedUrls)
  }

  // Req 4.4: when the declared version differs from the locked version, the
  // bundle is re-resolved (network/disk activity occurs).
  @Test
  fun resolveAllBundlesReResolvesWhenDeclarationVersionChanges() {
    val config =
      testConfig().copy(classpaths = mapOf("fixture" to mapOf("com.example:fix" to "2.0.0")))

    val existingLock =
      Lockfile(
        version = 4,
        kotlin = config.kotlin.version,
        jvmTarget = config.build.jvmTarget,
        dependencies = emptyMap(),
        classpathBundles =
          mapOf(
            "fixture" to
              mapOf(
                "com.example:fix" to
                  LockEntry(version = "1.0.0", sha256 = "fixSha", transitive = false, test = false)
              )
          ),
      )

    val pomFix2 =
      """
            <project><groupId>com.example</groupId><artifactId>fix</artifactId><version>2.0.0</version></project>
        """
        .trimIndent()

    val deps =
      countingDeps(
        sha256Results = mapOf("/cache/com/example/fix/2.0.0/fix-2.0.0.jar" to "fix2Sha"),
        pomContents = mapOf("/cache/com/example/fix/2.0.0/fix-2.0.0.pom" to pomFix2),
      )

    val result =
      assertNotNull(
        resolveAllBundles(
            config,
            existingLock = existingLock,
            cacheBase = "/cache",
            resolverDeps = deps,
          )
          .get()
      )

    val fixture = assertNotNull(result["fixture"])
    assertEquals(1, fixture.jars.size)
    assertEquals("/cache/com/example/fix/2.0.0/fix-2.0.0.jar", fixture.jars[0].cachePath)
    assertTrue(deps.downloadCount > 0, "fresh resolve should hit downloadFile at least once")
  }

  // Req 4.1 / 7.1: empty config.classpaths produces an empty bundle map and
  // no resolver activity.
  @Test
  fun resolveAllBundlesIsNoOpWhenNoClasspathsDeclared() {
    val config = testConfig()
    val deps = countingDeps()

    val result =
      assertNotNull(
        resolveAllBundles(config, existingLock = null, cacheBase = "/cache", resolverDeps = deps)
          .get()
      )

    assertTrue(result.isEmpty())
    assertEquals(0, deps.downloadCount)
  }

  private class CountingDeps(
    private val cachedFiles: MutableSet<String>,
    private val sha256Results: Map<String, String>,
    private val pomContents: Map<String, String>,
  ) : ResolverDeps {
    var downloadCount: Int = 0
    var readFileCount: Int = 0

    override fun fileExists(path: String): Boolean = path in cachedFiles

    override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

    override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
      downloadCount += 1
      cachedFiles.add(destPath)
      return Ok(Unit)
    }

    override fun computeSha256(filePath: String): Result<String, Sha256Error> {
      val hash = sha256Results[filePath] ?: return Err(Sha256Error(filePath))
      return Ok(hash)
    }

    override fun readFileContent(path: String): Result<String, OpenFailed> {
      readFileCount += 1
      val content = pomContents[path] ?: return Err(OpenFailed(path))
      return Ok(content)
    }
  }

  private fun countingDeps(
    cachedFiles: MutableSet<String> = mutableSetOf(),
    sha256Results: Map<String, String> = emptyMap(),
    pomContents: Map<String, String> = emptyMap(),
  ): CountingDeps = CountingDeps(cachedFiles, sha256Results, pomContents)
}
