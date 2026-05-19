package kolt.selfupdate

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.downloadFile
import kolt.infra.fileExists
import kolt.infra.removeDirectoryRecursive
import kolt.infra.testfixture.LoopbackHttpServer
import kolt.selfupdate.testfixture.GithubReleasesFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.fail
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getpid
import platform.posix.remove

@OptIn(ExperimentalForeignApi::class)
class SelfUpdaterCheckTest {

  private fun tempPath(): String = "/tmp/kolt_selfupdate_check_${getpid()}.json"

  private fun tempHome(): String = "/tmp/kolt_selfupdate_check_home_${getpid()}"

  private fun releasesClient(): GithubReleasesClient =
    GithubReleasesClient(
      downloader = ::downloadFile,
      userAgent = "kolt/test",
      tempPathFactory = ::tempPath,
    )

  private fun startOk(body: String): LoopbackHttpServer =
    LoopbackHttpServer.start(LoopbackHttpServer.Response.ok(body)).getOrElse {
      fail("LoopbackHttpServer.start failed: $it")
    }

  private fun updater(
    server: LoopbackHttpServer?,
    home: String = tempHome(),
    currentVersion: String = "0.20.0",
    uname: () -> Pair<String, String> = { "Linux" to "x86_64" },
    out: (String) -> Unit = {},
  ): SelfUpdater =
    SelfUpdater(
      releases = releasesClient(),
      home = home,
      currentVersion = currentVersion,
      uname = uname,
      out = out,
      releasesUrl =
        if (server != null) "http://127.0.0.1:${server.port}/releases/latest"
        else GITHUB_RELEASES_LATEST_URL,
    )

  // Req 2.3: latest > current resolves to UpdateAvailable carrying both the
  // running version and the newer release version.
  @Test
  fun latestNewerThanCurrentIsUpdateAvailable() {
    remove(tempPath())
    val json =
      GithubReleasesFixture.releasesLatestJson(
        version = "0.21.0",
        assetBaseUrl = "https://example.test/dl",
      )
    startOk(json).use { server ->
      try {
        val outcome =
          updater(server, currentVersion = "0.20.0").check().get()
            ?: fail("check should succeed when a newer release exists")
        val available = assertIs<CheckOutcome.UpdateAvailable>(outcome)
        assertEquals("0.20.0", available.current)
        assertEquals("0.21.0", available.latest)
      } finally {
        remove(tempPath())
      }
    }
  }

  // Req 2.4: latest == current resolves to AlreadyLatest (no update offered
  // for an exact version match).
  @Test
  fun latestEqualToCurrentIsAlreadyLatest() {
    remove(tempPath())
    val json =
      GithubReleasesFixture.releasesLatestJson(
        version = "0.20.0",
        assetBaseUrl = "https://example.test/dl",
      )
    startOk(json).use { server ->
      try {
        val outcome =
          updater(server, currentVersion = "0.20.0").check().get()
            ?: fail("check should succeed when current equals latest")
        val latest = assertIs<CheckOutcome.AlreadyLatest>(outcome)
        assertEquals("0.20.0", latest.current)
      } finally {
        remove(tempPath())
      }
    }
  }

  // Req 2.4: a current build that is newer than the published release (e.g. a
  // dev build ahead of the latest tag) is still AlreadyLatest, not a downgrade
  // offer.
  @Test
  fun currentNewerThanLatestIsAlreadyLatest() {
    remove(tempPath())
    val json =
      GithubReleasesFixture.releasesLatestJson(
        version = "0.20.0",
        assetBaseUrl = "https://example.test/dl",
      )
    startOk(json).use { server ->
      try {
        val outcome =
          updater(server, currentVersion = "0.21.0").check().get()
            ?: fail("check should succeed when current is ahead of latest")
        val latest = assertIs<CheckOutcome.AlreadyLatest>(outcome)
        assertEquals("0.21.0", latest.current)
      } finally {
        remove(tempPath())
      }
    }
  }

  // Req 6.1 / 6.2: a non-Linux platform short-circuits to Platform BEFORE any
  // network access — the loopback server must observe zero requests.
  @Test
  fun nonLinuxPlatformShortCircuitsBeforeFetch() {
    remove(tempPath())
    val json =
      GithubReleasesFixture.releasesLatestJson(
        version = "0.21.0",
        assetBaseUrl = "https://example.test/dl",
      )
    startOk(json).use { server ->
      try {
        val error =
          updater(server, uname = { "Darwin" to "arm64" }).check().getError()
            ?: fail("check must fail on a non-Linux platform")
        val platform = assertIs<SelfUpdateError.Platform>(error)
        assertEquals("Darwin", platform.sysname)
        assertEquals("arm64", platform.machine)

        // The server never completes its single accept() because the platform
        // gate rejected before any connection: a captured request would carry
        // a real "GET ... HTTP/1.1" line, the sentinels never do.
        val log = server.awaitAccessLog(timeoutMillis = 300)
        assertFalse(
          log.rawHeaderBlock.contains("HTTP/1.1") || log.headers.isNotEmpty(),
          "platform gate must reject before fetchLatest touches the network, " +
            "but the loopback server captured: ${log.rawHeaderBlock}",
        )
      } finally {
        remove(tempPath())
      }
    }
  }

  // Req 5.1: --check has no layout gate. Even with no installer symlink under
  // HOME at all, check still answers a version comparison result.
  @Test
  fun installerLayoutMismatchIsIrrelevantToCheck() {
    remove(tempPath())
    val home = tempHome()
    removeDirectoryRecursive(home)
    val json =
      GithubReleasesFixture.releasesLatestJson(
        version = "0.21.0",
        assetBaseUrl = "https://example.test/dl",
      )
    startOk(json).use { server ->
      try {
        val outcome =
          updater(server, home = home, currentVersion = "0.20.0").check().get()
            ?: fail("check must not depend on installer layout")
        assertIs<CheckOutcome.UpdateAvailable>(outcome)
      } finally {
        remove(tempPath())
        removeDirectoryRecursive(home)
      }
    }
  }

  // Req 2.5: --check performs zero install-layout filesystem writes. No
  // staging dir, no share root, no bin symlink dir is created under HOME, and
  // the only side effect on `out` is whatever the caller chooses to print.
  @Test
  fun checkPerformsNoInstallLayoutWrites() {
    remove(tempPath())
    val home = tempHome()
    removeDirectoryRecursive(home)
    val captured = mutableListOf<String>()
    val json =
      GithubReleasesFixture.releasesLatestJson(
        version = "0.21.0",
        assetBaseUrl = "https://example.test/dl",
      )
    startOk(json).use { server ->
      try {
        updater(server, home = home, currentVersion = "0.20.0", out = { captured.add(it) })
          .check()
          .get() ?: fail("check should succeed")

        assertFalse(fileExists("$home/.local/share/kolt"), "check must not create the share root")
        assertFalse(
          fileExists("$home/.local/bin/kolt"),
          "check must not create or touch the bin symlink",
        )
        assertFalse(
          fileExists("$home/.local/share/kolt/.staging-${getpid()}"),
          "check must not create a staging dir",
        )
        assertEquals(
          emptyList(),
          captured,
          "check itself must not print; formatting is the caller's job",
        )
      } finally {
        remove(tempPath())
        removeDirectoryRecursive(home)
      }
    }
  }
}
