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

class ResolveNativeProgressTest {

  @Test
  fun threeUncachedKlibsEmitOneOfThreeTwoOfThreeThreeOfThree() {
    val config =
      testConfig(target = "linuxX64")
        .copy(
          dependencies =
            mapOf(
              "com.example:a" to "1.0.0",
              "com.example:b" to "2.0.0",
              "com.example:c" to "3.0.0",
            )
        )

    val aRoot = rootModuleJson("com.example", "a-linuxx64", "1.0.0")
    val aPlatform = platformModuleJson("a-linuxx64-1.0.0.klib", "h-a")
    val bRoot = rootModuleJson("com.example", "b-linuxx64", "2.0.0")
    val bPlatform = platformModuleJson("b-linuxx64-2.0.0.klib", "h-b")
    val cRoot = rootModuleJson("com.example", "c-linuxx64", "3.0.0")
    val cPlatform = platformModuleJson("c-linuxx64-3.0.0.klib", "h-c")

    val deps =
      fakeDeps(
        contents =
          mapOf(
            "/cache/com/example/a/1.0.0/a-1.0.0.module" to aRoot,
            "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.module" to aPlatform,
            "/cache/com/example/b/2.0.0/b-2.0.0.module" to bRoot,
            "/cache/com/example/b-linuxx64/2.0.0/b-linuxx64-2.0.0.module" to bPlatform,
            "/cache/com/example/c/3.0.0/c-3.0.0.module" to cRoot,
            "/cache/com/example/c-linuxx64/3.0.0/c-linuxx64-3.0.0.module" to cPlatform,
          ),
        sha256 =
          mapOf(
            "/cache/com/example/a-linuxx64/1.0.0/a-linuxx64-1.0.0.klib" to "h-a",
            "/cache/com/example/b-linuxx64/2.0.0/b-linuxx64-2.0.0.klib" to "h-b",
            "/cache/com/example/c-linuxx64/3.0.0/c-linuxx64-3.0.0.klib" to "h-c",
          ),
      )

    val sink = RecordingResolverProgressSink()

    val result = resolveNative(config, "/cache", deps, progress = sink)
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
  fun fourOhFourThenTwoHundredEmitsArtifactStartFollowedByRetryAgainstSecondRepo() {
    val repo1 = "https://repo1.example.com"
    val repo2 = "https://repo2.example.com"
    val config =
      testConfig(
        target = "linuxX64",
        dependencies = mapOf("com.example:lib" to "1.0.0"),
        repositories =
          mapOf(
            "primary" to Repository(name = "primary", url = repo1),
            "fallback" to Repository(name = "fallback", url = repo2),
          ),
      )

    val rootModule = rootModuleJson("com.example", "lib-linuxx64", "1.0.0")
    val platformModule = platformModuleJson("lib-linuxx64-1.0.0.klib", "hashLib")

    // Pre-cache the .module files so only the klib download exercises the
    // 404-then-200 retry path. This isolates the test to the materialize
    // loop's klib `downloadFromRepositories` call (the one that should
    // forward `progress::onRetryAgainst`); module metadata fetches must
    // stay silent regardless.
    val cachedFiles =
      mutableSetOf(
        "/cache/com/example/lib/1.0.0/lib-1.0.0.module",
        "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module",
      )
    val contents =
      mapOf(
        "/cache/com/example/lib/1.0.0/lib-1.0.0.module" to rootModule,
        "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module" to platformModule,
      )

    val deps =
      object : ResolverDeps {
        override fun fileExists(path: String): Boolean = path in cachedFiles

        override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

        override fun downloadFile(
          url: String,
          destPath: String,
          headers: Map<String, String>?,
        ): Result<Unit, DownloadError> {
          if (destPath.endsWith(".klib") && url.startsWith(repo1)) {
            return Err(DownloadError.HttpFailed(url, 404))
          }
          cachedFiles.add(destPath)
          return Ok(Unit)
        }

        override fun computeSha256(filePath: String): Result<String, Sha256Error> = Ok("hashLib")

        override fun readFileContent(path: String): Result<String, OpenFailed> {
          val content = contents[path] ?: return Err(OpenFailed(path))
          return Ok(content)
        }
      }

    val sink = RecordingResolverProgressSink()

    val result = resolveNative(config, "/cache", deps, progress = sink)
    assertNotNull(result.get())

    val expected =
      listOf<RecordedProgressEvent>(
        RecordedProgressEvent.ArtifactStart(1, 1, "com.example:lib", "1.0.0"),
        RecordedProgressEvent.RetryAgainst(repo2),
      )
    assertEquals(expected, sink.events)
  }

  private fun rootModuleJson(
    redirectGroup: String,
    redirectModule: String,
    redirectVersion: String,
  ): String =
    """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "linuxX64ApiElements-published",
              "attributes": {
                "org.gradle.category": "library",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "linux_x64",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "available-at": {
                "url": "../../$redirectModule/$redirectVersion/$redirectModule-$redirectVersion.module",
                "group": "$redirectGroup",
                "module": "$redirectModule",
                "version": "$redirectVersion"
              }
            }
          ]
        }
    """
      .trimIndent()

  // A node with N klibs must still increment progress once, not N times:
  // M/N counts artifacts, not file downloads. Regression guard for the
  // per-node-not-per-klib invariant in resolveNative's pre-count + loop.
  @Test
  fun multiKlibVariantStillTicksProgressOnceForTheNode() {
    val config =
      testConfig(target = "linuxX64").copy(dependencies = mapOf("com.example:lib" to "1.0.0"))

    val rootModule = rootModuleJson("com.example", "lib-linuxx64", "1.0.0")
    val platformModule =
      platformModuleJsonMulti(
        klibs =
          listOf(
            "lib-linuxx64-1.0.0.klib" to "h-main",
            "lib-linuxx64-1.0.0-cinterop-something.klib" to "h-cinterop",
          )
      )

    val deps =
      fakeDeps(
        contents =
          mapOf(
            "/cache/com/example/lib/1.0.0/lib-1.0.0.module" to rootModule,
            "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module" to platformModule,
          ),
        sha256 =
          mapOf(
            "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.klib" to "h-main",
            "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0-cinterop-something.klib" to
              "h-cinterop",
          ),
      )

    val sink = RecordingResolverProgressSink()

    val result = resolveNative(config, "/cache", deps, progress = sink)
    assertNotNull(result.get())

    val artifactStarts = sink.events.filterIsInstance<RecordedProgressEvent.ArtifactStart>()
    assertEquals(
      listOf(RecordedProgressEvent.ArtifactStart(1, 1, "com.example:lib", "1.0.0")),
      artifactStarts,
      "Multi-klib variant must emit exactly one ArtifactStart with total=1",
    )
  }

  private fun platformModuleJsonMulti(
    klibs: List<Pair<String, String>>,
    dependencies: List<NativeDependency> = emptyList(),
  ): String {
    val filesJson =
      klibs.joinToString(",\n") { (url, sha256) ->
        """
              {
                "name": "$url",
                "url": "$url",
                "sha256": "$sha256"
              }
            """
          .trimIndent()
      }
    val depsJson =
      dependencies.joinToString(",\n") { d ->
        """
              {
                "group": "${d.group}",
                "module": "${d.module}",
                "version": { "requires": "${d.version}" }
              }
            """
          .trimIndent()
      }
    return """
            {
              "formatVersion": "1.1",
              "variants": [
                {
                  "name": "linuxX64ApiElements-published",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.usage": "kotlin-api",
                    "org.jetbrains.kotlin.native.target": "linux_x64",
                    "org.jetbrains.kotlin.platform.type": "native"
                  },
                  "dependencies": [$depsJson],
                  "files": [$filesJson]
                }
              ]
            }
        """
      .trimIndent()
  }

  private fun platformModuleJson(klibFileName: String, klibSha256: String): String =
    """
            {
              "formatVersion": "1.1",
              "variants": [
                {
                  "name": "linuxX64ApiElements-published",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.usage": "kotlin-api",
                    "org.jetbrains.kotlin.native.target": "linux_x64",
                    "org.jetbrains.kotlin.platform.type": "native"
                  },
                  "dependencies": [],
                  "files": [
                    {
                      "name": "inner.klib",
                      "url": "$klibFileName",
                      "sha256": "$klibSha256"
                    }
                  ]
                }
              ]
            }
        """
      .trimIndent()

  private fun fakeDeps(
    cachedFiles: MutableSet<String> = mutableSetOf(),
    contents: Map<String, String> = emptyMap(),
    sha256: Map<String, String> = emptyMap(),
  ): ResolverDeps {
    cachedFiles.addAll(contents.keys)
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
        val hash = sha256[filePath] ?: return Err(Sha256Error(filePath))
        return Ok(hash)
      }

      override fun readFileContent(path: String): Result<String, OpenFailed> {
        val content = contents[path] ?: return Err(OpenFailed(path))
        return Ok(content)
      }
    }
  }
}
