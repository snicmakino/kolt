package kolt.selfupdate.testfixture

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.infra.computeSha256
import kolt.infra.extractArchive
import kolt.infra.isRegularFile
import kolt.infra.removeDirectoryRecursive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class GithubReleasesFixtureTest {

  @Test
  fun producedTarballIsExtractableByExtractArchive() {
    val release = GithubReleasesFixture.buildRelease(version = "0.21.0")
    val destDir = "${release.workDir}/extracted"
    kolt.infra.ensureDirectoryRecursive(destDir)
    try {
      val result = extractArchive(release.tarballPath, destDir)
      if (result.getError() != null) fail("extractArchive failed: ${result.getError()}")
      assertTrue(
        isRegularFile("$destDir/bin/kolt"),
        "bin/kolt should exist as a regular file after extraction",
      )
    } finally {
      removeDirectoryRecursive(release.workDir)
    }
  }

  @Test
  fun sha256FileMatchesComputeSha256OfTarball() {
    val release = GithubReleasesFixture.buildRelease(version = "0.21.0")
    try {
      val computed =
        computeSha256(release.tarballPath).get()
          ?: fail("computeSha256 failed for ${release.tarballPath}")
      assertEquals(
        computed,
        release.sha256Hex,
        "the .sha256 file must carry computeSha256 of the tarball",
      )
      assertEquals(
        "$computed  ${release.tarballName}\n",
        kolt.infra.readFileAsString(release.sha256Path).get(),
        ".sha256 file must be in standard sha256sum format",
      )
    } finally {
      removeDirectoryRecursive(release.workDir)
    }
  }

  @Test
  fun cannedJsonParsesWithExpectedTagAndAssets() {
    val json =
      GithubReleasesFixture.releasesLatestJson(
        version = "0.21.0",
        assetBaseUrl = "https://example.test/dl",
      )
    val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<ProbeRelease>(json)
    assertEquals("v0.21.0", decoded.tagName)
    val names = decoded.assets.map { it.name }.toSet()
    assertTrue(
      "kolt-0.21.0-linux-x64.tar.gz" in names,
      "canned JSON must list the tarball asset, got $names",
    )
    assertTrue(
      "kolt-0.21.0-linux-x64.tar.gz.sha256" in names,
      "canned JSON must list the .sha256 asset, got $names",
    )
    val tarballAsset = decoded.assets.first { it.name == "kolt-0.21.0-linux-x64.tar.gz" }
    assertEquals(
      "https://example.test/dl/kolt-0.21.0-linux-x64.tar.gz",
      tarballAsset.browserDownloadUrl,
    )
  }

  @Serializable
  private data class ProbeRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<ProbeAsset>,
  )

  @Serializable
  private data class ProbeAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
  )
}
