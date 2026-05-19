package kolt.selfupdate.testfixture

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.infra.computeSha256
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.executeCommand
import kolt.infra.writeFileAsString
import kotlin.test.fail
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.chmod
import platform.posix.mkdtemp

// Test fixture for self-update tests: produces the two artifacts the real
// GitHub release path consumes — a canned `releases/latest` JSON body and an
// on-disk `kolt-<ver>-linux-x64.tar.gz` plus its adjacent standard-format
// `.sha256`. The tarball is built by shelling out to `tar` (test-only,
// mirroring scripts/regen-archive-fixtures.sh) so no libarchive write cinterop
// surface is introduced. The produced archive is extractable by the existing
// `extractArchive` and the `.sha256` matches `computeSha256` of the tarball.
@OptIn(ExperimentalForeignApi::class)
object GithubReleasesFixture {

  data class Release(
    val version: String,
    val workDir: String,
    val tarballName: String,
    val tarballPath: String,
    val sha256Path: String,
    val sha256Hex: String,
  )

  // Canned `releases/latest` JSON parametrized by version and asset base URL.
  // Carries `tag_name` plus the tarball and `.sha256` assets, each with `name`
  // and `browser_download_url`, so it can be served via LoopbackHttpServer.
  fun releasesLatestJson(version: String, assetBaseUrl: String): String {
    val base = assetBaseUrl.trimEnd('/')
    val tarball = tarballName(version)
    val sha = "$tarball.sha256"
    return """
      {
        "tag_name": "v$version",
        "name": "v$version",
        "draft": false,
        "prerelease": false,
        "assets": [
          {
            "name": "$tarball",
            "browser_download_url": "$base/$tarball"
          },
          {
            "name": "$sha",
            "browser_download_url": "$base/$sha"
          }
        ]
      }
    """
      .trimIndent()
  }

  // Builds `kolt-<ver>-linux-x64.tar.gz` (containing at least `bin/kolt`) and
  // the adjacent `<tarball>.sha256` in standard `sha256sum` format
  // (`<64-hex>  <filename>\n`) under a fresh temp directory. Caller owns
  // cleanup of `Release.workDir`.
  fun buildRelease(version: String): Release {
    val workDir = createTempDir("kolt_release_fixture_")
    val payloadDir = "$workDir/payload"
    if (ensureDirectoryRecursive("$payloadDir/bin").getError() != null) {
      fail("could not create payload dir under $payloadDir")
    }
    val fakeBinary = "$payloadDir/bin/kolt"
    if (writeFileAsString(fakeBinary, "#!/bin/sh\necho fake kolt $version\n").getError() != null) {
      fail("could not write fake binary at $fakeBinary")
    }
    chmod(fakeBinary, FAKE_BINARY_MODE)

    val tarball = tarballName(version)
    val tarballPath = "$workDir/$tarball"
    val tarResult =
      executeCommand(
        listOf(
          "tar",
          "-C",
          payloadDir,
          "--sort=name",
          "--mtime=@0",
          "--owner=0",
          "--group=0",
          "--numeric-owner",
          "--format=ustar",
          "-czf",
          tarballPath,
          "bin",
        )
      )
    if (tarResult.getError() != null) {
      fail("tar failed building $tarballPath: ${tarResult.getError()}")
    }

    val hex = computeSha256(tarballPath).get() ?: fail("computeSha256 failed for $tarballPath")
    val sha256Path = "$tarballPath.sha256"
    if (writeFileAsString(sha256Path, "$hex  $tarball\n").getError() != null) {
      fail("could not write $sha256Path")
    }

    return Release(
      version = version,
      workDir = workDir,
      tarballName = tarball,
      tarballPath = tarballPath,
      sha256Path = sha256Path,
      sha256Hex = hex,
    )
  }

  fun tarballName(version: String): String = "kolt-$version-linux-x64.tar.gz"

  private const val FAKE_BINARY_MODE: UInt = 0b111101101u // 0755

  private fun createTempDir(prefix: String): String {
    val template = "/tmp/${prefix}XXXXXX"
    val buf = template.encodeToByteArray().copyOf(template.length + 1)
    buf.usePinned { pinned ->
      val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
      return result.toKString()
    }
  }
}
