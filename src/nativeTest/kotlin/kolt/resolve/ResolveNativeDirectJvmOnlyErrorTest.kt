package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kolt.testConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResolveNativeDirectJvmOnlyErrorTest {

  @Test
  fun directJvmOnlyReturnsNoNativeVariantAndSuppressesNote() {
    val config =
      testConfig(target = "linuxX64")
        .copy(dependencies = mapOf("com.example:fake-jvm-only" to "1.0.0"))

    val pomXml =
      "<project><groupId>com.example</groupId><artifactId>fake-jvm-only</artifactId><version>1.0.0</version></project>"

    val pomContents =
      mutableMapOf("/cache/com/example/fake-jvm-only/1.0.0/fake-jvm-only-1.0.0.pom" to pomXml)
    val cachedFiles = mutableSetOf<String>()

    val jvmOnlyModulePath = "/cache/com/example/fake-jvm-only/1.0.0/fake-jvm-only-1.0.0.module"
    val jvmOnlyPomPath = "/cache/com/example/fake-jvm-only/1.0.0/fake-jvm-only-1.0.0.pom"

    val deps =
      object : ResolverDeps {
        override fun fileExists(path: String): Boolean = path in cachedFiles

        override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

        override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
          if (destPath == jvmOnlyModulePath) {
            return Err(DownloadError.HttpFailed(url, 404))
          }
          if (destPath == jvmOnlyPomPath) {
            cachedFiles.add(destPath)
            return Ok(Unit)
          }
          cachedFiles.add(destPath)
          return Ok(Unit)
        }

        override fun computeSha256(filePath: String): Result<String, Sha256Error> =
          Err(Sha256Error(filePath))

        override fun readFileContent(path: String): Result<String, OpenFailed> {
          pomContents[path]?.let {
            return Ok(it)
          }
          return Err(OpenFailed(path))
        }
      }

    val capturedNotes = mutableListOf<String>()
    val result = resolveNative(config, "/cache", deps, noteSink = { capturedNotes += it })

    val error = assertNotNull(result.getError(), "expected Err(NoNativeVariant) for direct JvmOnly")
    assertTrue(
      error is ResolveError.NoNativeVariant,
      "expected NoNativeVariant, got ${error::class.simpleName}: $error",
    )
    assertEquals("com.example:fake-jvm-only", error.groupArtifact)
    assertEquals("linux_x64", error.nativeTarget)

    assertEquals(
      emptyList(),
      capturedNotes,
      "direct JvmOnly must not emit a transitive-skip note; captured: $capturedNotes",
    )
  }
}
