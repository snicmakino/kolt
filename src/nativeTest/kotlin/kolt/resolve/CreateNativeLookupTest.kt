package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kolt.config.Repository
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for `createNativeLookup`'s `NativeResolved` variant dispatch.
 *
 * JvmOnly nodes surface as a tree leaf using the original (root) coordinate with no children,
 * because `.pom`-only artifacts have no Gradle Module Metadata and therefore no transitive native
 * dependencies to descend. The Klib path is exercised in parallel to guard against regressions in
 * the existing redirected-coordinate display.
 */
class CreateNativeLookupTest {

  @Test
  fun jvmOnlyNodeReturnsRootCoordinateWithEmptyDependencies() {
    val cachedFiles = mutableSetOf<String>()
    val pomCachePath = "/cache/com/example/jvm-only/1.0.0/jvm-only-1.0.0.pom"
    val deps =
      object : ResolverDeps {
        val pomContents =
          mapOf(
            pomCachePath to
              "<project><groupId>com.example</groupId><artifactId>jvm-only</artifactId><version>1.0.0</version></project>"
          )

        override fun fileExists(path: String): Boolean = path in cachedFiles

        override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

        override fun downloadFile(
          url: String,
          destPath: String,
          headers: Map<String, String>?,
        ): Result<Unit, DownloadError> {
          if (destPath.endsWith(".module")) {
            return Err(DownloadError.HttpFailed(url, 404))
          }
          if (destPath.endsWith(".pom")) {
            cachedFiles.add(destPath)
            return Ok(Unit)
          }
          return Err(DownloadError.HttpFailed(url, 404))
        }

        override fun computeSha256(filePath: String): Result<String, Sha256Error> =
          Err(Sha256Error(filePath))

        override fun readFileContent(path: String): Result<String, OpenFailed> {
          val content = pomContents[path] ?: return Err(OpenFailed(path))
          return Ok(content)
        }
      }

    val lookup =
      createNativeLookup(
        repos = listOf(Repository(name = "r", url = "https://repo1.example/")),
        cacheBase = "/cache",
        deps = deps,
        nativeTarget = "linux_x64",
      )

    val info = assertNotNull(lookup("com.example:jvm-only", "1.0.0"))
    assertEquals("com.example:jvm-only", info.displayGroupArtifact)
    assertEquals("1.0.0", info.displayVersion)
    assertTrue(
      info.dependencies.isEmpty(),
      "expected JvmOnly leaf with empty dependencies, got: ${info.dependencies}",
    )
  }

  @Test
  fun klibNodeReturnsRedirectedCoordinateWithTransitiveDependencies() {
    val rootModulePath = "/cache/com/example/lib/1.0.0/lib-1.0.0.module"
    val targetModulePath = "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module"
    val rootModuleJson =
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
                "url": "../../lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module",
                "group": "com.example",
                "module": "lib-linuxx64",
                "version": "1.0.0"
              }
            }
          ]
        }
      """
        .trimIndent()
    val targetModuleJson =
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
              "dependencies": [
                {
                  "group": "com.example",
                  "module": "trans",
                  "version": { "requires": "2.0.0" }
                }
              ],
              "files": [
                {
                  "name": "inner.klib",
                  "url": "lib-linuxx64-1.0.0.klib",
                  "sha256": "deadbeef"
                }
              ]
            }
          ]
        }
      """
        .trimIndent()

    val cachedFiles = mutableSetOf<String>()
    val contents = mapOf(rootModulePath to rootModuleJson, targetModulePath to targetModuleJson)
    val deps =
      object : ResolverDeps {
        override fun fileExists(path: String): Boolean = path in cachedFiles

        override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

        override fun downloadFile(
          url: String,
          destPath: String,
          headers: Map<String, String>?,
        ): Result<Unit, DownloadError> {
          if (destPath in contents) {
            cachedFiles.add(destPath)
            return Ok(Unit)
          }
          return Err(DownloadError.HttpFailed(url, 404))
        }

        override fun computeSha256(filePath: String): Result<String, Sha256Error> =
          Err(Sha256Error(filePath))

        override fun readFileContent(path: String): Result<String, OpenFailed> {
          val content = contents[path] ?: return Err(OpenFailed(path))
          return Ok(content)
        }
      }

    val lookup =
      createNativeLookup(
        repos = listOf(Repository(name = "r", url = "https://repo1.example/")),
        cacheBase = "/cache",
        deps = deps,
        nativeTarget = "linux_x64",
      )

    val info = assertNotNull(lookup("com.example:lib", "1.0.0"))
    assertEquals("com.example:lib-linuxx64", info.displayGroupArtifact)
    assertEquals("1.0.0", info.displayVersion)
    assertEquals(listOf("com.example:trans" to "2.0.0"), info.dependencies)
  }

  // Memoization is a contract of `createNativeLookup` (matches createPomLookup);
  // re-issuing the same query must hit the cache and skip the network entirely.
  // Without this, diamond dependencies in tree rendering would re-fetch the
  // same `.module` / `.pom` pair on every occurrence.
  @Test
  fun jvmOnlyLookupMemoizesAndDoesNotRefetch() {
    var moduleAttempts = 0
    var pomAttempts = 0
    val cachedFiles = mutableSetOf<String>()
    val pomCachePath = "/cache/com/example/jvm-only/1.0.0/jvm-only-1.0.0.pom"
    val deps =
      object : ResolverDeps {
        val pomContents =
          mapOf(
            pomCachePath to
              "<project><groupId>com.example</groupId><artifactId>jvm-only</artifactId><version>1.0.0</version></project>"
          )

        override fun fileExists(path: String): Boolean = path in cachedFiles

        override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

        override fun downloadFile(
          url: String,
          destPath: String,
          headers: Map<String, String>?,
        ): Result<Unit, DownloadError> {
          if (destPath.endsWith(".module")) {
            moduleAttempts += 1
            return Err(DownloadError.HttpFailed(url, 404))
          }
          if (destPath.endsWith(".pom")) {
            pomAttempts += 1
            cachedFiles.add(destPath)
            return Ok(Unit)
          }
          return Err(DownloadError.HttpFailed(url, 404))
        }

        override fun computeSha256(filePath: String): Result<String, Sha256Error> =
          Err(Sha256Error(filePath))

        override fun readFileContent(path: String): Result<String, OpenFailed> {
          val content = pomContents[path] ?: return Err(OpenFailed(path))
          return Ok(content)
        }
      }

    val lookup =
      createNativeLookup(
        repos = listOf(Repository(name = "r", url = "https://repo1.example/")),
        cacheBase = "/cache",
        deps = deps,
        nativeTarget = "linux_x64",
      )

    val first = assertNotNull(lookup("com.example:jvm-only", "1.0.0"))
    val moduleAttemptsAfterFirst = moduleAttempts
    val pomAttemptsAfterFirst = pomAttempts

    val second = assertNotNull(lookup("com.example:jvm-only", "1.0.0"))

    assertEquals(first, second)
    assertEquals(
      moduleAttemptsAfterFirst,
      moduleAttempts,
      "expected memoized JvmOnly lookup not to re-attempt .module download",
    )
    assertEquals(
      pomAttemptsAfterFirst,
      pomAttempts,
      "expected memoized JvmOnly lookup not to re-attempt .pom download",
    )
  }

  // `.module` 404 + `.pom` 404 must surface as null (lookup contract: any fetch
  // failure renders the node as an unresolved leaf in the tree). Asserting null
  // here pins the boundary between "structurally JVM-only" (returns leaf with
  // root coord) and "structurally absent" (returns null so the walker keeps
  // the original coordinate without claiming JVM-only semantics).
  @Test
  fun moduleAndPomBothMissingReturnsNull() {
    val deps =
      object : ResolverDeps {
        override fun fileExists(path: String): Boolean = false

        override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

        override fun downloadFile(
          url: String,
          destPath: String,
          headers: Map<String, String>?,
        ): Result<Unit, DownloadError> = Err(DownloadError.HttpFailed(url, 404))

        override fun computeSha256(filePath: String): Result<String, Sha256Error> =
          Err(Sha256Error(filePath))

        override fun readFileContent(path: String): Result<String, OpenFailed> =
          Err(OpenFailed(path))
      }

    val lookup =
      createNativeLookup(
        repos = listOf(Repository(name = "r", url = "https://repo1.example/")),
        cacheBase = "/cache",
        deps = deps,
        nativeTarget = "linux_x64",
      )

    assertNull(lookup("com.example:missing", "9.9.9"))
  }
}
