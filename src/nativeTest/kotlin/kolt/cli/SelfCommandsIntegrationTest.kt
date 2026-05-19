package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.DownloadError
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kolt.selfupdate.CheckOutcome
import kolt.selfupdate.Downloader
import kolt.selfupdate.GithubReleasesClient
import kolt.selfupdate.SelfUpdateError
import kolt.selfupdate.SelfUpdater
import kolt.selfupdate.UpdateOutcome
import kolt.selfupdate.testfixture.GithubReleasesFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getpid
import platform.posix.remove
import platform.posix.symlink

// Per-task review missed the no-op double print because SelfCommandsTest stubs
// SelfRunner and SelfUpdaterUpdateTest injects its own `out` and never goes
// through doSelf. This integration test closes that seam: it drives the REAL
// SelfUpdater through doSelf on the no-op path with SelfUpdater's `out` and
// doSelf's `out` pointed at the same sink (faithful to production, where both
// default to ::println / stdout), so a re-print across the
// SelfUpdater <-> SelfCommands boundary is observable as a count > 1.
//
// Transport is an in-memory fake Downloader (NOT libcurl / LoopbackHttpServer):
// the only seam under test is whether the no-op line crosses the
// SelfUpdater -> SelfCommands boundary once or twice. Real network + a server
// worker thread are incidental to that seam and would leak libcurl/pthread
// state into a later forking test, so they are deliberately excluded.
@OptIn(ExperimentalForeignApi::class)
class SelfCommandsIntegrationTest {

  private fun tempPath(): String = "/tmp/kolt_selfcmd_it_${getpid()}.json"

  private fun tempHome(): String = "/tmp/kolt_selfcmd_it_home_${getpid()}"

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

  // In-memory metadata transport: writes the canned releases/latest JSON to the
  // destination instead of doing an HTTP GET. tag_name == currentVersion, so
  // SelfUpdater.update() takes the no-op branch before any asset download is
  // attempted — this Downloader is therefore only ever asked for the metadata
  // URL, and never touches the network or a server thread.
  private fun metadataOnlyDownloader(json: String): Downloader = Downloader { _, destPath, _ ->
    writeFileAsString(destPath, json).getOrElse {
      return@Downloader Err(DownloadError.WriteFailed(destPath))
    }
    Ok(Unit)
  }

  // Drive doSelf(["update"]) against a REAL SelfUpdater whose GithubReleases
  // client uses an in-memory fake Downloader serving canned JSON with
  // tag_name == the current version, so update() takes the no-op branch.
  // SelfUpdater's `out` and doSelf's `out` share one sink (production wires
  // both to stdout). Before the SelfCommands fix the message appears twice
  // (SelfUpdater emits it, SelfCommands.runUpdate re-emits it for NoOp);
  // after the fix it appears exactly once.
  @Test
  fun noOpUpdateThroughDoSelfPrintsAlreadyLatestExactlyOnce() {
    remove(tempPath())
    val home = tempHome()
    removeDirectoryRecursive(home)
    val captured = mutableListOf<String>()
    val errSink = mutableListOf<String>()
    val json =
      GithubReleasesFixture.releasesLatestJson(
        version = "0.20.0",
        assetBaseUrl = "https://example.test/dl",
      )
    try {
      installInstallerLayout(home, "0.20.0")

      val client =
        GithubReleasesClient(
          downloader = metadataOnlyDownloader(json),
          userAgent = "kolt/test",
          tempPathFactory = ::tempPath,
        )
      val realUpdater =
        SelfUpdater(
          releases = client,
          home = home,
          currentVersion = "0.20.0",
          uname = { "Linux" to "x86_64" },
          out = { captured.add(it) },
          releasesUrl = "https://example.test/releases/latest",
        )
      val realRunner: SelfRunner =
        object : SelfRunner {
          override fun check(): Result<CheckOutcome, SelfUpdateError> = realUpdater.check()

          override fun update(): Result<UpdateOutcome, SelfUpdateError> = realUpdater.update()
        }

      val result =
        doSelf(
          listOf("update"),
          out = { captured.add(it) },
          err = { errSink.add(it) },
          runnerFactory = { Ok(realRunner) },
        )

      assertNull(result.getError(), "a no-op update through doSelf must exit 0")
      val occurrences = captured.count { it.contains("Already at latest version (0.20.0)") }
      assertEquals(
        1,
        occurrences,
        "the already-latest line must be printed exactly once across the " +
          "SelfUpdater/SelfCommands seam, got $occurrences in: $captured",
      )
    } finally {
      remove(tempPath())
      removeDirectoryRecursive(home)
    }
  }
}
