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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Flagship regression test for issue #426. The reported repro was
// `kolt add io.ktor:ktor-server-core` on a linuxX64 project failing on a
// `kotlin-reflect-*.module` 404. Translated into a deterministic mock fixture:
// the parent (ktor-server-core analogue) publishes a native variant whose
// platform `.module` declares `org.jetbrains.kotlin:kotlin-reflect` as a
// transitive dependency; `kotlin-reflect`'s `.module` returns HTTP 404 across
// all repos while its `.pom` returns 200. The structural `.module` 404 →
// `.pom` fallback must let resolution complete with `kotlin-reflect` skipped
// and a single stderr note emitted — without `kotlin-reflect` being added to
// any name-based skip list in the production code.
class NativeResolverJvmOnlyFallbackTest {

  @Test
  fun ktorTransitiveKotlinReflectModule404FallsBackViaPom() {
    // Override the default `central` repo so URL synthesis cannot accidentally
    // point at live Maven Central even if the mock leaked. The `.example` TLD
    // is reserved (RFC 2606) so no name resolution can reach a real host.
    val config =
      testConfig(target = "linuxX64")
        .copy(
          dependencies = mapOf("io.ktor:ktor-server-core" to "3.4.3"),
          repositories =
            mapOf("central" to Repository(name = "central", url = "https://repo1.example/")),
        )

    val parentRoot =
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
                "url": "../../ktor-server-core-linuxx64/3.4.3/ktor-server-core-linuxx64-3.4.3.module",
                "group": "io.ktor",
                "module": "ktor-server-core-linuxx64",
                "version": "3.4.3"
              }
            }
          ]
        }
        """
        .trimIndent()

    val parentPlatform =
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
                  "group": "org.jetbrains.kotlin",
                  "module": "kotlin-reflect",
                  "version": { "requires": "2.3.0" }
                }
              ],
              "files": [
                {
                  "name": "inner.klib",
                  "url": "ktor-server-core-linuxx64-3.4.3.klib",
                  "sha256": "h-ktor"
                }
              ]
            }
          ]
        }
        """
        .trimIndent()

    // Content is intentionally minimal — the fallback path only confirms
    // existence of the `.pom`, it does not parse the body.
    val kotlinReflectPom =
      "<project><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-reflect</artifactId><version>2.3.0</version></project>"

    val parentRootPath = "/cache/io/ktor/ktor-server-core/3.4.3/ktor-server-core-3.4.3.module"
    val parentPlatformPath =
      "/cache/io/ktor/ktor-server-core-linuxx64/3.4.3/ktor-server-core-linuxx64-3.4.3.module"
    val parentKlibPath =
      "/cache/io/ktor/ktor-server-core-linuxx64/3.4.3/ktor-server-core-linuxx64-3.4.3.klib"
    val kotlinReflectModulePath =
      "/cache/org/jetbrains/kotlin/kotlin-reflect/2.3.0/kotlin-reflect-2.3.0.module"
    val kotlinReflectPomPath =
      "/cache/org/jetbrains/kotlin/kotlin-reflect/2.3.0/kotlin-reflect-2.3.0.pom"

    val moduleContents =
      mutableMapOf(parentRootPath to parentRoot, parentPlatformPath to parentPlatform)
    val pomContents = mutableMapOf(kotlinReflectPomPath to kotlinReflectPom)
    val klibSha = mapOf(parentKlibPath to "h-ktor")
    val cachedFiles = mutableSetOf<String>()
    cachedFiles.addAll(moduleContents.keys)

    val recordedDownloads = mutableListOf<String>()

    val deps =
      object : ResolverDeps {
        override fun fileExists(path: String): Boolean = path in cachedFiles

        override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

        override fun downloadFile(
          url: String,
          destPath: String,
          headers: Map<String, String>?,
        ): Result<Unit, DownloadError> {
          recordedDownloads.add(url)
          if (destPath == kotlinReflectModulePath) {
            return Err(DownloadError.HttpFailed(url, 404))
          }
          if (destPath == kotlinReflectPomPath) {
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

    // Verification 1: resolve completes successfully (does not abort).
    val resolved = assertNotNull(result.get(), "expected Ok from resolveNative; got: $result")

    // Verification 2: the JvmOnly transitive (kotlin-reflect) is not in resolvedDeps.
    assertFalse(
      resolved.deps.any { it.groupArtifact == "org.jetbrains.kotlin:kotlin-reflect" },
      "kotlin-reflect must not appear in resolvedDeps, got: ${resolved.deps.map { it.groupArtifact }}",
    )

    // Klib direct dep must still be resolved. `ResolvedDep.groupArtifact`
    // preserves the user-facing root coordinate (the `available-at` redirect
    // is an internal detail of the native module layout).
    assertTrue(
      resolved.deps.any { it.groupArtifact == "io.ktor:ktor-server-core" },
      "parent klib must be resolved, got: ${resolved.deps.map { it.groupArtifact }}",
    )

    // Verification 3: exactly one stderr note for the kotlin-reflect skip.
    val expectedNote =
      "note: org.jetbrains.kotlin:kotlin-reflect:2.3.0 has no Gradle Module Metadata; skipping for native target"
    assertEquals(
      listOf(expectedNote),
      capturedNotes,
      "expected exactly one stderr note for the transitive kotlin-reflect skip",
    )

    // Verification 4: production code adds no name-based skip for
    // kotlin-reflect — confirmed by leaving NativeResolver.kt unmodified;
    // the test reaches its assertions only by exercising the structural
    // .module 404 → .pom fallback.
    //
    // Verification 5: fixture is offline. The repo base is the RFC 2606
    // reserved `.example` TLD, so even if `downloadFile` were not mocked, no
    // request could reach Maven Central. Asserting both the positive shape
    // (all URLs use the test base) and the negative shape (no `maven.org`
    // hostname leaked) pins the property against future `testConfig` drift.
    assertTrue(
      recordedDownloads.all { it.startsWith("https://repo1.example/") },
      "expected all download URLs to use the test repo base, recorded: $recordedDownloads",
    )
    assertTrue(
      recordedDownloads.none { it.contains("maven.org") },
      "expected no live Maven Central downloads, recorded: $recordedDownloads",
    )
    // The fallback must have probed kotlin-reflect's `.pom` — proves the
    // structural detection path executed rather than a hardcoded skip.
    assertTrue(
      recordedDownloads.any { it.endsWith("kotlin-reflect-2.3.0.pom") },
      "expected a kotlin-reflect.pom download attempt (fallback probe), recorded: $recordedDownloads",
    )
  }
}
