package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BundleResolverVersionDivergenceTest {

  // Bundle A pins foo:1.0, bundle B pins foo:2.0. Each bundle resolves in
  // its own graph (Option C), so neither resolution sees the other's pin and
  // no StrictVersionConflict is raised. Pin for Req 4.5 — the version
  // conflict-resolution scope must stay bundle-local.
  @Test
  fun bundlesCanIndependentlyPinDifferentVersionsOfSameGa() {
    val config = testConfig()

    val foo10Pom =
      """
            <project><groupId>com.example</groupId><artifactId>foo</artifactId><version>1.0.0</version></project>
        """
        .trimIndent()
    val foo20Pom =
      """
            <project><groupId>com.example</groupId><artifactId>foo</artifactId><version>2.0.0</version></project>
        """
        .trimIndent()

    val deps =
      fakeBundleDeps(
        sha256Results =
          mapOf(
            "/cache/com/example/foo/1.0.0/foo-1.0.0.jar" to "hashFoo10",
            "/cache/com/example/foo/2.0.0/foo-2.0.0.jar" to "hashFoo20",
          ),
        pomContents =
          mapOf(
            "/cache/com/example/foo/1.0.0/foo-1.0.0.pom" to foo10Pom,
            "/cache/com/example/foo/2.0.0/foo-2.0.0.pom" to foo20Pom,
          ),
      )

    val bundleA =
      assertNotNull(
        resolveBundle(
            config = config,
            bundleName = "a",
            bundleSeeds = mapOf("com.example:foo" to "1.0.0"),
            existingLock = null,
            cacheBase = "/cache",
            deps = deps,
          )
          .get(),
        "bundle A must resolve cleanly with foo:1.0",
      )

    val bundleB =
      assertNotNull(
        resolveBundle(
            config = config,
            bundleName = "b",
            bundleSeeds = mapOf("com.example:foo" to "2.0.0"),
            existingLock = null,
            cacheBase = "/cache",
            deps = deps,
          )
          .get(),
        "bundle B must resolve cleanly with foo:2.0 — independent graph",
      )

    val bundleACoords = bundleA.jars.map { it.groupArtifactVersion }
    val bundleBCoords = bundleB.jars.map { it.groupArtifactVersion }

    assertEquals(listOf("com.example:foo:1.0.0"), bundleACoords)
    assertEquals(listOf("com.example:foo:2.0.0"), bundleBCoords)

    assertTrue(
      "/cache/com/example/foo/1.0.0/foo-1.0.0.jar" in bundleA.jars.map { it.cachePath },
      "bundle A's classpath must point at foo-1.0.0.jar",
    )
    assertTrue(
      "/cache/com/example/foo/2.0.0/foo-2.0.0.jar" in bundleB.jars.map { it.cachePath },
      "bundle B's classpath must point at foo-2.0.0.jar",
    )
  }

  private fun fakeBundleDeps(
    cachedFiles: MutableSet<String> = mutableSetOf(),
    sha256Results: Map<String, String> = emptyMap(),
    pomContents: Map<String, String> = emptyMap(),
  ): ResolverDeps {
    return object : ResolverDeps {
      override fun fileExists(path: String): Boolean = path in cachedFiles

      override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

      override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
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
