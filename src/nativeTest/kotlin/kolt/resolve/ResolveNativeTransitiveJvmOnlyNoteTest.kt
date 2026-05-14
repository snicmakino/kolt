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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResolveNativeTransitiveJvmOnlyNoteTest {

  @Test
  fun transitiveJvmOnlyNonStdlibEmitsNoteAndSkipsFromResolvedDeps() {
    val config =
      testConfig(target = "linuxX64").copy(dependencies = mapOf("com.example:lib" to "1.0.0"))

    val libRoot =
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

    val libPlatform =
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
                  "group": "org.example",
                  "module": "fake-jvm-only",
                  "version": { "requires": "1.0.0" }
                }
              ],
              "files": [
                {
                  "name": "inner.klib",
                  "url": "lib-linuxx64-1.0.0.klib",
                  "sha256": "h-lib"
                }
              ]
            }
          ]
        }
        """
        .trimIndent()

    val pomXml =
      "<project><groupId>org.example</groupId><artifactId>fake-jvm-only</artifactId><version>1.0.0</version></project>"

    val moduleContents =
      mutableMapOf(
        "/cache/com/example/lib/1.0.0/lib-1.0.0.module" to libRoot,
        "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module" to libPlatform,
      )
    val pomContents =
      mutableMapOf("/cache/org/example/fake-jvm-only/1.0.0/fake-jvm-only-1.0.0.pom" to pomXml)
    val klibSha = mapOf("/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.klib" to "h-lib")
    val cachedFiles = mutableSetOf<String>()
    cachedFiles.addAll(moduleContents.keys)

    val jvmOnlyModulePath = "/cache/org/example/fake-jvm-only/1.0.0/fake-jvm-only-1.0.0.module"
    val jvmOnlyPomPath = "/cache/org/example/fake-jvm-only/1.0.0/fake-jvm-only-1.0.0.pom"

    val deps =
      object : ResolverDeps {
        override fun fileExists(path: String): Boolean = path in cachedFiles

        override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

        override fun downloadFile(
          url: String,
          destPath: String,
          headers: Map<String, String>?,
        ): Result<Unit, DownloadError> {
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

        override fun computeSha256(filePath: String): Result<String, Sha256Error> {
          val hash = klibSha[filePath] ?: return Err(Sha256Error(filePath))
          return Ok(hash)
        }

        override fun readFileContent(path: String): Result<String, OpenFailed> {
          moduleContents[path]?.let {
            return Ok(it)
          }
          pomContents[path]?.let {
            return Ok(it)
          }
          return Err(OpenFailed(path))
        }
      }

    val capturedNotes = mutableListOf<String>()
    val result = resolveNative(config, "/cache", deps, noteSink = { capturedNotes += it })

    val resolved = assertNotNull(result.get())

    assertFalse(
      resolved.deps.any { it.groupArtifact == "org.example:fake-jvm-only" },
      "JvmOnly transitive must not appear in resolvedDeps, got: ${resolved.deps.map { it.groupArtifact }}",
    )

    assertTrue(
      resolved.deps.any { it.groupArtifact == "com.example:lib" },
      "Klib direct dep must still be resolved, got: ${resolved.deps.map { it.groupArtifact }}",
    )

    val expectedNote =
      "note: org.example:fake-jvm-only:1.0.0 has no Gradle Module Metadata; skipping for native target"
    assertEquals(
      listOf(expectedNote),
      capturedNotes,
      "expected exactly one stderr note for the transitive JvmOnly skip",
    )
  }
}
