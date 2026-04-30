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

class BundleResolverIsolationTest {

  // Bundle A pulls X transitively. Bundle B does not request X anywhere.
  // Each bundle pass is an independent fixpointResolve invocation, so X must
  // appear only in bundle A's resolution. Pin for Req 4.5.
  @Test
  fun bundleBundleTransitiveDoesNotLeakIntoOtherBundle() {
    val config = testConfig()

    val aPom =
      """
            <project>
                <groupId>com.example</groupId>
                <artifactId>a</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>x</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """
        .trimIndent()
    val xPom =
      """
            <project><groupId>com.example</groupId><artifactId>x</artifactId><version>1.0.0</version></project>
        """
        .trimIndent()
    val bPom =
      """
            <project><groupId>com.example</groupId><artifactId>b</artifactId><version>1.0.0</version></project>
        """
        .trimIndent()

    val deps =
      fakeBundleDeps(
        sha256Results =
          mapOf(
            "/cache/com/example/a/1.0.0/a-1.0.0.jar" to "hashA",
            "/cache/com/example/x/1.0.0/x-1.0.0.jar" to "hashX",
            "/cache/com/example/b/1.0.0/b-1.0.0.jar" to "hashB",
          ),
        pomContents =
          mapOf(
            "/cache/com/example/a/1.0.0/a-1.0.0.pom" to aPom,
            "/cache/com/example/x/1.0.0/x-1.0.0.pom" to xPom,
            "/cache/com/example/b/1.0.0/b-1.0.0.pom" to bPom,
          ),
      )

    val bundleA =
      assertNotNull(
        resolveBundle(
            config = config,
            bundleName = "a",
            bundleSeeds = mapOf("com.example:a" to "1.0.0"),
            existingLock = null,
            cacheBase = "/cache",
            deps = deps,
          )
          .get()
      )

    val bundleB =
      assertNotNull(
        resolveBundle(
            config = config,
            bundleName = "b",
            bundleSeeds = mapOf("com.example:b" to "1.0.0"),
            existingLock = null,
            cacheBase = "/cache",
            deps = deps,
          )
          .get()
      )

    val bundleACoords = bundleA.jars.map { it.groupArtifactVersion }.toSet()
    val bundleBCoords = bundleB.jars.map { it.groupArtifactVersion }.toSet()

    assertTrue("com.example:a:1.0.0" in bundleACoords, "bundle A must include its direct dep a")
    assertTrue("com.example:x:1.0.0" in bundleACoords, "bundle A must include its transitive dep x")
    assertTrue("com.example:b:1.0.0" in bundleBCoords, "bundle B must include its direct dep b")
    assertTrue(
      bundleBCoords.none { it.startsWith("com.example:x:") },
      "bundle B must not see X (transitive of bundle A only); got $bundleBCoords",
    )
  }

  // Sanity check: resolving a config with no [classpaths] using the existing
  // main/test pass must continue to behave as before. Bundle pass is purely
  // additive.
  @Test
  fun mainResolveRemainsUntouchedWhenNoBundlesDeclared() {
    val config = testConfig().copy(dependencies = mapOf("com.example:lib" to "1.0.0"))
    val libPom =
      """
            <project><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0.0</version></project>
        """
        .trimIndent()
    val deps =
      fakeBundleDeps(
        sha256Results = mapOf("/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "hashLib"),
        pomContents = mapOf("/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to libPom),
      )

    val result = resolveTransitive(config, null, "/cache", deps)
    val resolved = assertNotNull(result.get())
    assertEquals(1, resolved.deps.size)
    assertEquals("com.example:lib", resolved.deps[0].groupArtifact)
    assertTrue(
      resolved.deps.none { it.groupArtifact == "com.example:x" },
      "main resolve must not contain bundle-only transitive X",
    )
  }

  @Test
  fun bundleResolutionClasspathIsColonJoinedJarPaths() {
    val config = testConfig()

    val libPom =
      """
            <project><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0.0</version></project>
        """
        .trimIndent()

    val deps =
      fakeBundleDeps(
        sha256Results = mapOf("/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "hashLib"),
        pomContents = mapOf("/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to libPom),
      )

    val bundle =
      assertNotNull(
        resolveBundle(
            config = config,
            bundleName = "tools",
            bundleSeeds = mapOf("com.example:lib" to "1.0.0"),
            existingLock = null,
            cacheBase = "/cache",
            deps = deps,
          )
          .get()
      )

    assertEquals(1, bundle.jars.size)
    assertEquals("/cache/com/example/lib/1.0.0/lib-1.0.0.jar", bundle.jars[0].cachePath)
    assertEquals("/cache/com/example/lib/1.0.0/lib-1.0.0.jar", bundle.classpath)
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
