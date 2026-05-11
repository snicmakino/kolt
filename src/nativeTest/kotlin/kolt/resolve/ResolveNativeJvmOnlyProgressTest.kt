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

class ResolveNativeJvmOnlyProgressTest {

  @Test
  fun jvmOnlyTransitiveIsExcludedFromTotalAndArtifactStartCount() {
    val config =
      testConfig(target = "linuxX64").copy(dependencies = mapOf("com.example:lib" to "1.0.0"))

    // Direct Klib (lib) declares two transitives in its platform module:
    //   - com.example:other:2.0.0  -> Klib (normal .module resolution)
    //   - org.example:fake-jvm:1.0.0 -> JvmOnly (.module 404, .pom 200 fallback)
    // M/N progress must read [1/2] [2/2], not [1/3] [2/3] / [3/3].
    val libRoot = rootModuleJson("com.example", "lib-linuxx64", "1.0.0")
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
                  "group": "com.example",
                  "module": "other",
                  "version": { "requires": "2.0.0" }
                },
                {
                  "group": "org.example",
                  "module": "fake-jvm",
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
    val otherRoot = rootModuleJson("com.example", "other-linuxx64", "2.0.0")
    val otherPlatform =
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
                  "url": "other-linuxx64-2.0.0.klib",
                  "sha256": "h-other"
                }
              ]
            }
          ]
        }
        """
        .trimIndent()
    val fakeJvmPom =
      "<project><groupId>org.example</groupId><artifactId>fake-jvm</artifactId><version>1.0.0</version></project>"

    val moduleContents =
      mapOf(
        "/cache/com/example/lib/1.0.0/lib-1.0.0.module" to libRoot,
        "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.module" to libPlatform,
        "/cache/com/example/other/2.0.0/other-2.0.0.module" to otherRoot,
        "/cache/com/example/other-linuxx64/2.0.0/other-linuxx64-2.0.0.module" to otherPlatform,
      )
    val pomContents = mapOf("/cache/org/example/fake-jvm/1.0.0/fake-jvm-1.0.0.pom" to fakeJvmPom)
    val klibSha =
      mapOf(
        "/cache/com/example/lib-linuxx64/1.0.0/lib-linuxx64-1.0.0.klib" to "h-lib",
        "/cache/com/example/other-linuxx64/2.0.0/other-linuxx64-2.0.0.klib" to "h-other",
      )
    val cachedFiles = mutableSetOf<String>()
    cachedFiles.addAll(moduleContents.keys)

    val jvmOnlyModulePath = "/cache/org/example/fake-jvm/1.0.0/fake-jvm-1.0.0.module"
    val jvmOnlyPomPath = "/cache/org/example/fake-jvm/1.0.0/fake-jvm-1.0.0.pom"

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

    val sink = RecordingResolverProgressSink()
    val result = resolveNative(config, "/cache", deps, progress = sink, noteSink = {})
    assertNotNull(result.get())

    val artifactStarts = sink.events.filterIsInstance<RecordedProgressEvent.ArtifactStart>()
    assertEquals(
      2,
      artifactStarts.size,
      "expected onArtifactStart for the 2 Klib nodes only, got ${artifactStarts.map { it.groupArtifact }}",
    )
    assertEquals(
      setOf("com.example:lib", "com.example:other"),
      artifactStarts.map { it.groupArtifact }.toSet(),
      "JvmOnly transitive must not appear in onArtifactStart notifications",
    )
    artifactStarts.forEach {
      assertEquals(
        2,
        it.total,
        "total must exclude the JvmOnly transitive so M/N reads as [_/2], got [${it.index}/${it.total}] for ${it.groupArtifact}",
      )
    }
    assertEquals(
      listOf(1, 2),
      artifactStarts.map { it.index }.sorted(),
      "indices must be the contiguous 1..2 range, got ${artifactStarts.map { it.index }}",
    )
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
}
