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
import kotlin.test.assertTrue

class FetchNativeMetadataJvmOnlyFallbackTest {

  @Test
  fun moduleAllRepos404AndPom200ReturnsJvmOnly() {
    val pomXml =
      """
            <project><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0.0</version></project>
        """
        .trimIndent()

    val cachedFiles = mutableSetOf<String>()
    val pomCachePath = "/cache/com/example/lib/1.0.0/lib-1.0.0.pom"
    val deps =
      object : ResolverDeps {
        val pomContents = mapOf(pomCachePath to pomXml)

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

    val result =
      fetchNativeMetadata(
        groupArtifact = "com.example:lib",
        version = "1.0.0",
        nativeTarget = "linux_x64",
        cacheBase = "/cache",
        repos =
          listOf(
            Repository(name = "r1", url = "https://repo1.example/"),
            Repository(name = "r2", url = "https://repo2.example/"),
          ),
        deps = deps,
      )

    val resolved = assertIs<NativeResolved.JvmOnly>(result.get())
    assertEquals("com.example", resolved.coordinate.group)
    assertEquals("lib", resolved.coordinate.artifact)
    assertEquals("1.0.0", resolved.coordinate.version)
  }

  // When `.module` and `.pom` both 404 across every repo, the resolver must
  // surface the original `.module` DownloadFailed so a typo or genuinely
  // missing artifact stays visible in the per-repo attempts dump. Returning
  // `.pom` attempts instead would mask the real failure under a fallback
  // path the user did not request.
  @Test
  fun moduleAndPomAll404ReturnsModuleAttempts() {
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

    val result =
      fetchNativeMetadata(
        groupArtifact = "com.example:lib",
        version = "1.0.0",
        nativeTarget = "linux_x64",
        cacheBase = "/cache",
        repos =
          listOf(
            Repository(name = "r1", url = "https://repo1.example/"),
            Repository(name = "r2", url = "https://repo2.example/"),
          ),
        deps = deps,
      )

    val error = assertIs<ResolveError.DownloadFailed>(result.getError())
    assertEquals("com.example:lib", error.groupArtifact)
    val failure = assertIs<RepositoryDownloadFailure.AllAttemptsFailed>(error.failure)
    assertEquals(2, failure.attempts.size)
    assertTrue(
      failure.attempts.all { it.url.endsWith(".module") },
      "expected only .module attempts, got: ${failure.attempts.map { it.url }}",
    )
    assertTrue(
      failure.attempts.none { it.url.endsWith(".pom") },
      "expected no .pom attempts, got: ${failure.attempts.map { it.url }}",
    )
  }

  // Non-404 `.module` failures (5xx, network, local write) must NOT trigger the
  // `.pom` fallback. The fallback exists to recognize "structurally JVM-only"
  // artifacts, not to paper over transient or environmental failures: a 503
  // could become a 200 on retry, and silently downgrading to JvmOnly would
  // mask the real problem. The recorder asserts the resolver never even
  // attempts a `.pom` URL when the gate rejects the module error.
  @Test
  fun moduleFailsWith5xxDoesNotTriggerPomFallback() {
    assertNoPomFallbackOnModuleError { url -> DownloadError.HttpFailed(url, 503) }
  }

  @Test
  fun moduleFailsWithNetworkErrorDoesNotTriggerPomFallback() {
    assertNoPomFallbackOnModuleError { url -> DownloadError.NetworkError(url, "connection reset") }
  }

  @Test
  fun moduleFailsWithWriteFailedDoesNotTriggerPomFallback() {
    assertNoPomFallbackOnModuleError { _ ->
      DownloadError.WriteFailed("/cache/tmp/lib-1.0.0.module")
    }
  }

  private fun assertNoPomFallbackOnModuleError(moduleErrorFor: (String) -> DownloadError) {
    val recorded = mutableListOf<String>()
    val deps =
      object : ResolverDeps {
        override fun fileExists(path: String): Boolean = false

        override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

        override fun downloadFile(
          url: String,
          destPath: String,
          headers: Map<String, String>?,
        ): Result<Unit, DownloadError> {
          recorded.add(url)
          if (destPath.endsWith(".module")) {
            return Err(moduleErrorFor(url))
          }
          // Any `.pom` reach here would already be a regression; return an
          // arbitrary error so we still observe the URL in `recorded`.
          return Err(DownloadError.HttpFailed(url, 404))
        }

        override fun computeSha256(filePath: String): Result<String, Sha256Error> =
          Err(Sha256Error(filePath))

        override fun readFileContent(path: String): Result<String, OpenFailed> =
          Err(OpenFailed(path))
      }

    val result =
      fetchNativeMetadata(
        groupArtifact = "com.example:lib",
        version = "1.0.0",
        nativeTarget = "linux_x64",
        cacheBase = "/cache",
        repos =
          listOf(
            Repository(name = "r1", url = "https://repo1.example/"),
            Repository(name = "r2", url = "https://repo2.example/"),
          ),
        deps = deps,
      )

    val error = assertIs<ResolveError.DownloadFailed>(result.getError())
    assertEquals("com.example:lib", error.groupArtifact)
    val failure = assertIs<RepositoryDownloadFailure.AllAttemptsFailed>(error.failure)
    // `downloadFromRepositories` short-circuits on the first non-404 error
    // rather than walking the rest of the repo list (404 is the only
    // "try the next mirror" signal), so the attempts list has exactly one entry.
    assertEquals(1, failure.attempts.size)
    assertTrue(
      failure.attempts.all { it.url.endsWith(".module") },
      "expected only .module attempts in error, got: ${failure.attempts.map { it.url }}",
    )
    assertTrue(
      failure.attempts.none { attempt ->
        val e = attempt.error
        e is DownloadError.HttpFailed && e.statusCode == 404
      },
      "expected no 404 attempts in non-404 scenario, got: ${failure.attempts.map { it.error }}",
    )
    assertTrue(
      recorded.none { it.endsWith(".pom") },
      "expected no .pom download attempts, recorded: $recorded",
    )
    assertTrue(
      recorded.all { it.endsWith(".module") },
      "expected only .module URLs to be attempted, recorded: $recorded",
    )
  }
}
