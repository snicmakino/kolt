package kolt.selfupdate

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getError
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
class SelfUpdaterUpdateChecksumMismatchTest {

  private fun tempPath(): String = "/tmp/kolt_selfupdate_mismatch_${getpid()}.json"

  private fun tempHome(): String = "/tmp/kolt_selfupdate_mismatch_home_${getpid()}"

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

  // Same on-disk asset transport as the happy path, but the served `.sha256`
  // hex is overwritten with a value that does not match the tarball so the
  // verify step's mismatch branch is exercised end-to-end.
  private fun mismatchDownloader(
    release: GithubReleasesFixture.Release,
    bogusSha256Path: String,
  ): Downloader = Downloader { url, destPath, _ ->
    val src =
      when {
        url.endsWith(".sha256") -> bogusSha256Path
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

  private fun readSymlink(path: String): String = memScoped {
    val buf = allocArray<ByteVar>(PATH_MAX)
    val n = readlink(path, buf, (PATH_MAX - 1).toULong())
    if (n < 0) fail("readlink failed for $path")
    buf[n] = 0
    buf.toKString()
  }

  // Req 4.3 / 7.2 / 7.3: when the `.sha256` does not match the tarball the
  // update aborts with Asset(name) carrying the offending asset, no
  // `<shareRoot>/<new>/` dir is created, and the bin symlink still points at
  // the old version (no Switched outcome).
  @Test
  fun checksumMismatchAbortsWithoutInstallOrSymlinkChange() {
    remove(tempPath())
    val home = tempHome()
    removeDirectoryRecursive(home)
    val shareRoot = "$home/.local/share/kolt"
    val release = GithubReleasesFixture.buildRelease(version = "0.21.0")
    val tarball = GithubReleasesFixture.tarballName("0.21.0")
    val bogusSha256Path = "${release.workDir}/bogus.sha256"
    writeFileAsString(bogusSha256Path, "${"0".repeat(64)}  $tarball\n").getOrElse {
      fail("could not write bogus sha256")
    }
    val json =
      GithubReleasesFixture.releasesLatestJson(
        version = "0.21.0",
        assetBaseUrl = "https://example.test/dl",
      )
    startOk(json).use { server ->
      try {
        installInstallerLayout(home, "0.20.0")
        val originalTarget = readSymlink("$home/.local/bin/kolt")

        val result = updater(server, home, mismatchDownloader(release, bogusSha256Path)).update()

        val error =
          result.getError()
            ?: fail("update must fail on checksum mismatch, got Ok: ${result.getOrElse { null }}")
        val asset = assertIs<SelfUpdateError.Asset>(error)
        assertEquals(tarball, asset.name, "the Asset error must name the offending tarball")

        assertFalse(
          fileExists("$shareRoot/0.21.0"),
          "no new version dir may be created when the checksum mismatches (Req 4.3)",
        )
        assertEquals(
          originalTarget,
          readSymlink("$home/.local/bin/kolt"),
          "the bin symlink must still point at the old version on extract/verify failure (Req 7.3)",
        )
      } finally {
        remove(tempPath())
        removeDirectoryRecursive(home)
        removeDirectoryRecursive(release.workDir)
      }
    }
  }
}
