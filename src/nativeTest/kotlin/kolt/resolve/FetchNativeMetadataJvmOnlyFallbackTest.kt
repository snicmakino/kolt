package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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

        override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
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
        repos = listOf("https://repo1.example/", "https://repo2.example/"),
        deps = deps,
      )

    val resolved = assertIs<NativeResolved.JvmOnly>(result.get())
    assertEquals("com.example", resolved.coordinate.group)
    assertEquals("lib", resolved.coordinate.artifact)
    assertEquals("1.0.0", resolved.coordinate.version)
  }
}
