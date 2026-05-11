package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Boundary check for the Layer 1 `.pom` fallback: the fallback fires only on
// the root `.module` 404. When the root `.module` is 200 OK and parses into an
// `available-at` redirect, then the target `.module` (after redirect) 404s,
// design.md > Boundary Commitments > Out of Boundary keeps that branch on the
// existing `DownloadFailed` path. Layer 2 may revisit target-side handling; this
// test pins the current shape so any future change there is a visible diff,
// not a silent regression.
class NativeResolverTargetModule404GoldenTest {

  @Test
  fun targetModule404AfterRedirectStaysOnDownloadFailedAndNoPomFallback() {
    val rootModuleJson =
      """
        {
          "formatVersion": "1.1",
          "variants": [
            {
              "name": "linuxX64ApiElements-published",
              "attributes": {
                "artifactType": "org.jetbrains.kotlin.klib",
                "org.gradle.category": "library",
                "org.gradle.jvm.environment": "non-jvm",
                "org.gradle.usage": "kotlin-api",
                "org.jetbrains.kotlin.native.target": "linux_x64",
                "org.jetbrains.kotlin.platform.type": "native"
              },
              "available-at": {
                "url": "../../parent-linuxx64/1.0.0/parent-linuxx64-1.0.0.module",
                "group": "com.example",
                "module": "parent-linuxx64",
                "version": "1.0.0"
              }
            }
          ]
        }
        """
        .trimIndent()

    val rootModuleCachePath = "/cache/com/example/parent/1.0.0/parent-1.0.0.module"
    val cachedFiles = mutableSetOf<String>()
    val recordedDownloadUrls = mutableListOf<String>()
    val deps =
      object : ResolverDeps {
        val fileContents = mutableMapOf(rootModuleCachePath to rootModuleJson)

        override fun fileExists(path: String): Boolean = path in cachedFiles

        override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

        override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
          recordedDownloadUrls.add(url)
          // Root `.module` succeeds on the first repo so the redirect is parseable.
          // Target `.module` (parent-linuxx64) 404s on every repo. `.pom` URLs
          // must never be reached for the target coordinate: the Layer 1
          // fallback is root-only, and the root `.module` never failed.
          if (destPath == rootModuleCachePath) {
            cachedFiles.add(destPath)
            return Ok(Unit)
          }
          return Err(DownloadError.HttpFailed(url, 404))
        }

        override fun computeSha256(filePath: String): Result<String, Sha256Error> =
          Err(Sha256Error(filePath))

        override fun readFileContent(path: String): Result<String, OpenFailed> {
          val content = fileContents[path] ?: return Err(OpenFailed(path))
          return Ok(content)
        }
      }

    val result =
      fetchNativeMetadata(
        groupArtifact = "com.example:parent",
        version = "1.0.0",
        nativeTarget = "linux_x64",
        cacheBase = "/cache",
        repos = listOf("https://repo1.example/", "https://repo2.example/"),
        deps = deps,
      )

    val error = assertIs<ResolveError.DownloadFailed>(result.getError())
    // `groupArtifact` is the target coordinate (after redirect), not the root,
    // because the failure happened on the second fetch. This pins which
    // coordinate users see in the per-repo attempts dump for this scenario.
    assertEquals("com.example:parent-linuxx64", error.groupArtifact)
    val failure = assertIs<RepositoryDownloadFailure.AllAttemptsFailed>(error.failure)
    assertTrue(
      failure.attempts.isNotEmpty(),
      "expected at least one attempt against the target `.module`",
    )
    assertTrue(
      failure.attempts.all { it.url.contains("parent-linuxx64-1.0.0.module") },
      "expected only target `.module` URLs in attempts, got: ${failure.attempts.map { it.url }}",
    )

    // Layer 1 boundary: target `.module` 404 does NOT trigger `.pom` fallback.
    // The recorder proves no `.pom` URL was ever attempted for either the root
    // or the target coordinate. When Layer 2 lands and changes this behaviour,
    // this assertion is the canary.
    assertTrue(
      recordedDownloadUrls.none { it.endsWith(".pom") },
      "expected no `.pom` download attempts, recorded: $recordedDownloadUrls",
    )
    assertTrue(
      recordedDownloadUrls.any { it.endsWith("parent-linuxx64-1.0.0.module") },
      "expected at least one attempt for the target `.module`, recorded: $recordedDownloadUrls",
    )
  }
}
