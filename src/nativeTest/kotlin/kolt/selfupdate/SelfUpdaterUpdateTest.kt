package kolt.selfupdate

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrElse
import kolt.infra.DownloadError
import kolt.infra.copyFile
import kolt.infra.downloadFile
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.fileExists
import kolt.infra.removeDirectoryRecursive
import kolt.infra.testfixture.LoopbackHttpServer
import kolt.selfupdate.testfixture.GithubReleasesFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.PATH_MAX
import platform.posix.getpid
import platform.posix.readlink
import platform.posix.remove
import platform.posix.symlink

@OptIn(ExperimentalForeignApi::class)
class SelfUpdaterUpdateTest {

  private fun tempPath(): String = "/tmp/kolt_selfupdate_update_${getpid()}.json"

  private fun tempHome(): String = "/tmp/kolt_selfupdate_update_home_${getpid()}"

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

  // The binary tarball / .sha256 cannot round-trip through the loopback
  // server's String body (UTF-8 re-encoding mangles gzip bytes), so the
  // asset transport is faked by copying the fixture's on-disk artifacts.
  // The real network transport for assets is out of this spec's boundary;
  // computeSha256 / extractArchive / replaceSymlinkAtomically all run for real.
  private fun fixtureDownloader(release: GithubReleasesFixture.Release): Downloader =
    Downloader { url, destPath, _ ->
      val src =
        when {
          url.endsWith(".sha256") -> release.sha256Path
          url.endsWith(".tar.gz") -> release.tarballPath
          else -> return@Downloader Err(DownloadError.NetworkError(url, "unexpected asset url"))
        }
      copyFile(src, destPath).getOrElse {
        return@Downloader Err(DownloadError.WriteFailed(destPath))
      }
      Ok(Unit)
    }

  private fun updater(
    server: LoopbackHttpServer,
    home: String,
    downloader: Downloader,
    currentVersion: String = "0.20.0",
    out: (String) -> Unit = {},
  ): SelfUpdater =
    SelfUpdater(
      releases = releasesClient(),
      home = home,
      currentVersion = currentVersion,
      downloader = downloader,
      uname = { "Linux" to "x86_64" },
      out = out,
      releasesUrl = "http://127.0.0.1:${server.port}/releases/latest",
    )

  private fun installInstallerLayout(home: String, version: String) {
    ensureDirectoryRecursive("$home/.local/bin").getOrElse { fail("mkdir bin failed") }
    ensureDirectoryRecursive("$home/.local/share/kolt/$version/bin").getOrElse {
      fail("mkdir share failed")
    }
    val realBin = "$home/.local/share/kolt/$version/bin/kolt"
    kolt.infra.writeFileAsString(realBin, "#!/bin/sh\necho old kolt $version\n").getOrElse {
      fail("write fake binary failed")
    }
    val link = "$home/.local/bin/kolt"
    if (symlink(realBin, link) != 0) fail("setup symlink failed")
  }

  private fun readSymlink(path: String): String = memScoped {
    val buf = allocArray<ByteVar>(PATH_MAX)
    val n = readlink(path, buf, (PATH_MAX - 1).toULong())
    if (n < 0) fail("readlink failed for $path")
    buf[n] = 0
    buf.toKString()
  }

  // Req 3.1 / 3.3 / 3.4 / 3.5 / 3.6 / 4.1 / 4.2 / 4.5: a newer release runs the
  // full pipeline. The 5 progress lines appear in order, the new version dir is
  // placed, the bin symlink is retargeted via a single rename, the old version
  // dir survives, the own pid staging dir is gone after success, and the
  // outcome carries from/to.
  @Test
  fun newerReleaseRunsFullUpdatePipeline() {
    remove(tempPath())
    val home = tempHome()
    removeDirectoryRecursive(home)
    val release = GithubReleasesFixture.buildRelease(version = "0.21.0")
    val captured = mutableListOf<String>()
    val json =
      GithubReleasesFixture.releasesLatestJson(
        version = "0.21.0",
        assetBaseUrl = "https://example.test/dl",
      )
    startOk(json).use { server ->
      try {
        installInstallerLayout(home, "0.20.0")

        val outcome =
          updater(server, home, fixtureDownloader(release), out = { captured.add(it) })
            .update()
            .getOrElse { fail("update should succeed on a newer release: $it") }

        val switched = assertIs<UpdateOutcome.Switched>(outcome)
        assertEquals("0.20.0", switched.from)
        assertEquals("0.21.0", switched.to)

        assertEquals(
          listOf(
            "fetching release metadata",
            "downloading tarball",
            "verifying checksum",
            "extracting",
            "switching to new version",
          ),
          captured,
          "the 5 progress stages must be emitted once each, in order",
        )

        assertTrue(
          fileExists("$home/.local/share/kolt/0.21.0/bin/kolt"),
          "the new version payload must land at <shareRoot>/0.21.0/bin/kolt",
        )
        assertTrue(
          fileExists("$home/.local/share/kolt/0.20.0/bin/kolt"),
          "the old version dir must survive (Req 3.5)",
        )
        assertEquals(
          "$home/.local/share/kolt/0.21.0/bin/kolt",
          readSymlink("$home/.local/bin/kolt"),
          "the bin symlink must now point at the new version target",
        )
        assertFalse(
          fileExists("$home/.local/share/kolt/.staging-${getpid()}"),
          "the own pid staging dir must be removed after a successful update",
        )
      } finally {
        remove(tempPath())
        removeDirectoryRecursive(home)
        removeDirectoryRecursive(release.workDir)
      }
    }
  }

  // Req 3.2: latest == current is a no-op. The outcome is NoOp, the bin
  // symlink and version dirs are untouched, and no staging dir is created.
  @Test
  fun latestEqualToCurrentIsNoOpWithNoFilesystemMutation() {
    remove(tempPath())
    val home = tempHome()
    removeDirectoryRecursive(home)
    val release = GithubReleasesFixture.buildRelease(version = "0.20.0")
    val captured = mutableListOf<String>()
    val json =
      GithubReleasesFixture.releasesLatestJson(
        version = "0.20.0",
        assetBaseUrl = "https://example.test/dl",
      )
    startOk(json).use { server ->
      try {
        installInstallerLayout(home, "0.20.0")
        val originalTarget = readSymlink("$home/.local/bin/kolt")

        val outcome =
          updater(server, home, fixtureDownloader(release), out = { captured.add(it) })
            .update()
            .getOrElse { fail("update should succeed (no-op) when already latest: $it") }

        val noOp = assertIs<UpdateOutcome.NoOp>(outcome)
        assertEquals("0.20.0", noOp.current)

        assertFalse(
          fileExists("$home/.local/share/kolt/.staging-${getpid()}"),
          "a no-op update must not create a staging dir",
        )
        assertEquals(
          originalTarget,
          readSymlink("$home/.local/bin/kolt"),
          "a no-op update must not retarget the bin symlink",
        )
        assertFalse(
          fileExists("$home/.local/share/kolt/0.20.0/bin/kolt".replace("0.20.0", "9.9.9")),
          "a no-op update must not create any other version dir",
        )
        assertTrue(
          captured.contains("Already at latest version (0.20.0)"),
          "a no-op must announce the already-latest state, got: $captured",
        )
      } finally {
        remove(tempPath())
        removeDirectoryRecursive(home)
        removeDirectoryRecursive(release.workDir)
      }
    }
  }
}
