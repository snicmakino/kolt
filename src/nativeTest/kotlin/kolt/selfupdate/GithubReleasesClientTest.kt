package kolt.selfupdate

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.downloadFile
import kolt.infra.testfixture.LoopbackHttpServer
import kolt.selfupdate.testfixture.GithubReleasesFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getpid
import platform.posix.remove

@OptIn(ExperimentalForeignApi::class)
class GithubReleasesClientTest {

  private fun tempPath(): String = "/tmp/kolt_gh_releases_test_${getpid()}.json"

  private fun client(server: LoopbackHttpServer): GithubReleasesClient =
    GithubReleasesClient(
      downloader = ::downloadFile,
      userAgent = "kolt/9.9.9",
      tempPathFactory = ::tempPath,
    )

  private fun startOk(body: String): LoopbackHttpServer =
    LoopbackHttpServer.start(LoopbackHttpServer.Response.ok(body)).getOrElse {
      fail("LoopbackHttpServer.start failed: $it")
    }

  // Req 2.1 / 4.1: a well-formed releases/latest body decodes into the
  // expected tag and both assets with their browser_download_url.
  @Test
  fun fetchLatestDecodesTagAndAssets() {
    remove(tempPath())
    val json =
      GithubReleasesFixture.releasesLatestJson(
        version = "0.21.0",
        assetBaseUrl = "https://example.test/dl",
      )
    startOk(json).use { server ->
      try {
        val release =
          client(server).fetchLatest("http://127.0.0.1:${server.port}/releases/latest").get()
            ?: fail("fetchLatest should succeed on valid JSON")
        assertEquals("v0.21.0", release.tagName)
        val tarball = release.assetByName("kolt-0.21.0-linux-x64.tar.gz")
        assertEquals(
          "https://example.test/dl/kolt-0.21.0-linux-x64.tar.gz",
          tarball?.browserDownloadUrl,
          "tarball asset URL must round-trip from the JSON",
        )
        assertTrue(
          release.assets.any { it.name == "kolt-0.21.0-linux-x64.tar.gz.sha256" },
          "the .sha256 asset must also be present",
        )
      } finally {
        remove(tempPath())
      }
    }
  }

  // Req 7.1: a syntactically broken / tag_name-less body is a metadata
  // failure, distinguishable from a network failure.
  @Test
  fun malformedBodyIsMetadataError() {
    remove(tempPath())
    startOk("""{ "not_a_release": true }""").use { server ->
      try {
        val error =
          client(server).fetchLatest("http://127.0.0.1:${server.port}/releases/latest").getError()
            ?: fail("fetchLatest must fail when tag_name is absent")
        assertIs<SelfUpdateError.Metadata>(error)
      } finally {
        remove(tempPath())
      }
    }
  }

  // Req 2.1: tag validation accepts exactly vX.Y.Z and strips the leading v.
  @Test
  fun validateTagAcceptsSemverAndStripsV() {
    val client =
      GithubReleasesClient(
        downloader = ::downloadFile,
        userAgent = "kolt/9.9.9",
        tempPathFactory = ::tempPath,
      )
    assertEquals("1.2.3", client.validateTag("v1.2.3").get())
  }

  // Req 2.1 / 7.1: non-vX.Y.Z tags (prerelease suffix, missing v, extra
  // component) are metadata failures, not silently accepted.
  @Test
  fun validateTagRejectsNonSemver() {
    val client =
      GithubReleasesClient(
        downloader = ::downloadFile,
        userAgent = "kolt/9.9.9",
        tempPathFactory = ::tempPath,
      )
    for (bad in listOf("1.2.3", "v1.2", "v1.2.3.4", "v1.2.3-rc1", "vX.Y.Z", "")) {
      val error = client.validateTag(bad).getError() ?: fail("'$bad' must be rejected")
      assertIs<SelfUpdateError.Metadata>(error)
    }
  }

  // Req contract: GitHub API returns 403 without a User-Agent, so the
  // configured "kolt/<ver>" UA must actually reach the wire.
  @Test
  fun userAgentHeaderReachesTheWire() {
    remove(tempPath())
    val json =
      GithubReleasesFixture.releasesLatestJson(
        version = "0.21.0",
        assetBaseUrl = "https://example.test/dl",
      )
    startOk(json).use { server ->
      try {
        client(server).fetchLatest("http://127.0.0.1:${server.port}/releases/latest").get()
          ?: fail("fetchLatest should succeed")
        val log = server.awaitAccessLog()
        assertEquals(
          "kolt/9.9.9",
          log.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value,
          "server access log must capture the exact User-Agent kolt sent",
        )
      } finally {
        remove(tempPath())
      }
    }
  }

  // Req 4.4 / 7.2: an asset that the release does not publish surfaces as
  // SelfUpdateError.Asset carrying the missing asset's name.
  @Test
  fun missingAssetIsAssetErrorCarryingName() {
    remove(tempPath())
    val json =
      GithubReleasesFixture.releasesLatestJson(
        version = "0.21.0",
        assetBaseUrl = "https://example.test/dl",
      )
    startOk(json).use { server ->
      try {
        val release =
          client(server).fetchLatest("http://127.0.0.1:${server.port}/releases/latest").get()
            ?: fail("fetchLatest should succeed")
        assertNull(
          release.assetByName("kolt-9.9.9-linux-x64.tar.gz"),
          "an absent asset must not be found by assetByName",
        )
        val client = client(server)
        val error =
          client.assetUrl(release, "kolt-9.9.9-linux-x64.tar.gz").getError()
            ?: fail("resolving an absent asset must fail")
        val asset = assertIs<SelfUpdateError.Asset>(error)
        assertEquals("kolt-9.9.9-linux-x64.tar.gz", asset.name)
      } finally {
        remove(tempPath())
      }
    }
  }
}
