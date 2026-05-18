package kolt.selfupdate

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import kolt.infra.DownloadError
import kolt.infra.copyFile
import kolt.infra.downloadFile
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.fileExists
import kolt.infra.removeDirectoryRecursive
import kolt.infra.testfixture.LoopbackHttpServer
import kolt.infra.writeFileAsString
import kolt.selfupdate.testfixture.GithubReleasesFixture
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getpid
import platform.posix.getppid
import platform.posix.remove
import platform.posix.symlink

@OptIn(ExperimentalForeignApi::class)
class SelfUpdaterStagingIsolationTest {

  private fun tempPath(): String = "/tmp/kolt_selfupdate_staging_${getpid()}.json"

  private fun tempHome(): String = "/tmp/kolt_selfupdate_staging_home_${getpid()}"

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
  ): SelfUpdater =
    SelfUpdater(
      releases = releasesClient(),
      home = home,
      currentVersion = "0.20.0",
      downloader = downloader,
      uname = { "Linux" to "x86_64" },
      out = {},
      releasesUrl = "http://127.0.0.1:${server.port}/releases/latest",
    )

  private fun installInstallerLayout(home: String, version: String) {
    ensureDirectoryRecursive("$home/.local/bin").getOrElse { fail("mkdir bin failed") }
    ensureDirectoryRecursive("$home/.local/share/kolt/$version/bin").getOrElse {
      fail("mkdir share failed")
    }
    val realBin = "$home/.local/share/kolt/$version/bin/kolt"
    writeFileAsString(realBin, "#!/bin/sh\necho old kolt $version\n").getOrElse {
      fail("write fake binary failed")
    }
    val link = "$home/.local/bin/kolt"
    if (symlink(realBin, link) != 0) fail("setup symlink failed")
  }

  private fun seedStagingDir(shareRoot: String, pid: Int) {
    val dir = "$shareRoot/.staging-$pid"
    ensureDirectoryRecursive(dir).getOrElse { fail("seed staging dir failed for $dir") }
    writeFileAsString("$dir/junk", "stale\n").getOrElse { fail("seed junk failed for $dir") }
  }

  // Req 4.5: a `.staging-<pid>` left by a process that is no longer alive must
  // be best-effort swept on the next run, while a `.staging-<pid>` belonging to
  // a live process (here: this test process's own pid is alive, used as a
  // stand-in for a concurrent updater) must NOT be touched. The own-pid staging
  // dir is recreated and removed after a successful switch, and the update
  // still succeeds (Switched) — the sweep does not derail the pipeline.
  @Test
  fun deadPidStagingSweptLivePidStagingUntouched() {
    remove(tempPath())
    val home = tempHome()
    removeDirectoryRecursive(home)
    val shareRoot = "$home/.local/share/kolt"
    val release = GithubReleasesFixture.buildRelease(version = "0.21.0")
    val json =
      GithubReleasesFixture.releasesLatestJson(
        version = "0.21.0",
        assetBaseUrl = "https://example.test/dl",
      )
    // 99999 is verified-dead in the test/CI environment. The parent (test
    // runner) pid is unambiguously alive and is distinct from this process's
    // own pid, so it stands in for a concurrent updater whose staging dir the
    // sweep must spare without colliding with the own-pid staging the pipeline
    // recreates-then-removes.
    val deadPid = 99999
    val liveOtherPid = getppid()
    startOk(json).use { server ->
      try {
        installInstallerLayout(home, "0.20.0")
        seedStagingDir(shareRoot, deadPid)
        seedStagingDir(shareRoot, liveOtherPid)

        val outcome =
          updater(server, home, fixtureDownloader(release)).update().getOrElse {
            fail("update should still succeed while sweeping dead-pid staging: $it")
          }

        assertIs<UpdateOutcome.Switched>(outcome)

        assertFalse(
          fileExists("$shareRoot/.staging-$deadPid"),
          "a dead pid's .staging dir must be best-effort swept on update",
        )
        assertTrue(
          fileExists("$shareRoot/.staging-$liveOtherPid"),
          "a live pid's .staging dir must never be touched by the sweep",
        )
        assertFalse(
          fileExists("$shareRoot/.staging-${getpid()}"),
          "the own pid staging dir must be removed after a successful update",
        )
        assertTrue(
          fileExists("$shareRoot/0.21.0/bin/kolt"),
          "the new version payload must still land despite the sweep",
        )
      } finally {
        remove(tempPath())
        removeDirectoryRecursive(home)
        removeDirectoryRecursive(release.workDir)
      }
    }
  }
}
