package kolt.selfupdate

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.config.KOLT_VERSION
import kolt.infra.SelfExeError
import kolt.infra.SymlinkError
import kolt.infra.canWrite as fsCanWrite
import kolt.infra.computeSha256
import kolt.infra.downloadFile
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.extractArchive
import kolt.infra.fileExists
import kolt.infra.readFileAsString
import kolt.infra.readSelfExe
import kolt.infra.removeDirectoryRecursive
import kolt.infra.replaceSymlinkAtomically
import kolt.resolve.compareVersions
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.PATH_MAX
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.getpid
import platform.posix.lstat
import platform.posix.readlink
import platform.posix.rename
import platform.posix.stat
import platform.posix.uname
import platform.posix.utsname

// Single source of truth for the (sysname, machine) pair the platform gate
// checks. Defaults to `uname(2)`; the seam exists so a macOS / linuxArm64
// port can swap the assertion and tests can drive the non-Linux branch.
@OptIn(ExperimentalForeignApi::class)
private fun hostUname(): Pair<String, String> = memScoped {
  val buf = alloc<utsname>()
  if (uname(buf.ptr) != 0) return@memScoped "unknown" to "unknown"
  buf.sysname.toKString() to buf.machine.toKString()
}

class SelfUpdater(
  private val releases: GithubReleasesClient,
  private val home: String,
  private val currentVersion: String = KOLT_VERSION,
  private val readSelfExe: () -> Result<String, SelfExeError> = ::readSelfExe,
  private val canWrite: (String) -> Boolean = ::fsCanWrite,
  // Asset transport seam: the staged tarball / .sha256 fetch goes through
  // this so tests can serve the binary archive from disk without a real
  // HTTPS round-trip. Production wires the same libcurl GET as everything else.
  private val downloader: Downloader = Downloader(::downloadFile),
  private val replaceSymlink: (String, String) -> Result<Unit, SymlinkError> =
    ::replaceSymlinkAtomically,
  private val uname: () -> Pair<String, String> = ::hostUname,
  private val out: (String) -> Unit = ::println,
  // Seam mirroring GithubReleasesClient.fetchLatest's own `url` default so
  // loopback-server tests can drive check() without a real GitHub round-trip.
  private val releasesUrl: String = GITHUB_RELEASES_LATEST_URL,
) {
  // --check answers "is a newer stable release out?" with zero filesystem
  // side effects: platform gate first, then fetch + tag-validate + semver
  // compare. No layout probe, no staging, no symlink — those belong to the
  // write-bearing update() path only.
  fun check(): Result<CheckOutcome, SelfUpdateError> {
    ensureLinuxX64().getError()?.let {
      return Err(it)
    }

    val release =
      releases.fetchLatest(releasesUrl).getOrElse {
        return Err(it)
      }
    val latest =
      releases.validateTag(release.tagName).getOrElse {
        return Err(it)
      }

    return if (compareVersions(latest, currentVersion) > 0) {
      Ok(CheckOutcome.UpdateAvailable(current = currentVersion, latest = latest))
    } else {
      Ok(CheckOutcome.AlreadyLatest(current = currentVersion))
    }
  }

  // The write-bearing path. The platform gate is first so an unsupported
  // host is rejected before any network or disk work; the layout +
  // writability gates run next so a non-installer layout is rejected
  // without touching GitHub or disk. Only once a strictly newer stable
  // release is confirmed does the staged download/verify/extract/swap run.
  fun update(): Result<UpdateOutcome, SelfUpdateError> {
    ensureLinuxX64().getError()?.let {
      return Err(it)
    }
    val layout =
      detectLayout().getOrElse {
        return Err(it)
      }
    verifyWritable(layout).getError()?.let {
      return Err(it)
    }

    out("fetching release metadata")
    val release =
      releases.fetchLatest(releasesUrl).getOrElse {
        return Err(it)
      }
    val latest =
      releases.validateTag(release.tagName).getOrElse {
        return Err(it)
      }

    if (compareVersions(latest, currentVersion) <= 0) {
      out("Already at latest version ($currentVersion)")
      return Ok(UpdateOutcome.NoOp(current = currentVersion))
    }

    return runStaged(layout, release, latest)
  }

  // Download → verify → extract → place → swap, all inside this process's
  // own `.staging-<pid>/`. The own-pid staging dir is recreated
  // unconditionally so an interrupted prior run leaves no stale payload;
  // sibling version dirs and other pids' staging dirs are never touched
  // (dead-pid sweep handled separately). The new payload lands at
  // `<shareRoot>/<new>/` only after the checksum matches and extraction
  // succeeds; the swap is a single rename so a concurrent or interrupted
  // run is observed as old-or-new, never partial.
  @OptIn(ExperimentalForeignApi::class)
  private fun runStaged(
    layout: Layout,
    release: LatestRelease,
    newVersion: String,
  ): Result<UpdateOutcome, SelfUpdateError> {
    val tarballName = "kolt-$newVersion-linux-x64.tar.gz"
    val sha256Name = "$tarballName.sha256"

    val tarballUrl =
      releases.assetUrl(release, tarballName).getOrElse {
        return Err(it)
      }
    val sha256Url =
      releases.assetUrl(release, sha256Name).getOrElse {
        return Err(it)
      }

    val staging = "${layout.shareRoot}/.staging-${getpid()}"
    removeDirectoryRecursive(staging)
    ensureDirectoryRecursive(staging).getError()?.let {
      return Err(SelfUpdateError.Extract("could not create staging dir $staging"))
    }

    out("downloading tarball")
    val tarballPath = "$staging/$tarballName"
    val sha256Path = "$staging/$sha256Name"
    downloader.downloadFile(tarballUrl, tarballPath, null).getError()?.let {
      return Err(SelfUpdateError.Asset(tarballName, "download failed"))
    }
    downloader.downloadFile(sha256Url, sha256Path, null).getError()?.let {
      return Err(SelfUpdateError.Asset(sha256Name, "download failed"))
    }

    out("verifying checksum")
    val computed =
      computeSha256(tarballPath).getOrElse {
        return Err(SelfUpdateError.Asset(tarballName, "could not hash downloaded tarball"))
      }
    val expected =
      readSha256(sha256Path)
        ?: return Err(SelfUpdateError.Asset(sha256Name, "could not read expected checksum"))
    if (!computed.equals(expected, ignoreCase = true)) {
      return Err(
        SelfUpdateError.Asset(tarballName, "checksum mismatch: expected $expected, got $computed")
      )
    }

    out("extracting")
    val extractDir = "$staging/extract"
    ensureDirectoryRecursive(extractDir).getError()?.let {
      return Err(SelfUpdateError.Extract("could not create extract dir $extractDir"))
    }
    extractArchive(tarballPath, extractDir).getError()?.let {
      return Err(SelfUpdateError.Extract(it.message))
    }

    // The release tarball wraps its content in a `kolt-<ver>-linux-x64/`
    // directory (assemble-dist.sh / install.sh contract); when that wrapper
    // is absent the extraction root itself is the payload.
    val wrapper = "$extractDir/kolt-$newVersion-linux-x64"
    val payloadDir = if (fileExists(wrapper)) wrapper else extractDir
    val newInstallDir = "${layout.shareRoot}/$newVersion"
    removeDirectoryRecursive(newInstallDir)
    if (rename(payloadDir, newInstallDir) != 0) {
      return Err(SelfUpdateError.Extract("could not place new version at $newInstallDir"))
    }

    out("switching to new version")
    replaceSymlink(layout.binSymlink, "$newInstallDir/bin/kolt").getError()?.let {
      return Err(
        SelfUpdateError.Layout(
          detectedPath = layout.binSymlink,
          detail = "could not swap the bin symlink to the new version",
        )
      )
    }

    removeDirectoryRecursive(staging)
    return Ok(UpdateOutcome.Switched(from = currentVersion, to = newVersion))
  }

  // Standard `sha256sum` format: `<64-hex>  <filename>`. Take the first
  // whitespace-split field of the first non-blank line.
  private fun readSha256(path: String): String? {
    val body =
      readFileAsString(path).getOrElse {
        return null
      }
    val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
    return firstLine.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }
  }

  // Wide assertion: linuxX64 is the only build target today. The `amd64`
  // alias is accepted because some kernels report it instead of `x86_64`.
  internal fun ensureLinuxX64(): Result<Unit, SelfUpdateError> {
    val (sysname, machine) = uname()
    val supported = sysname == "Linux" && (machine == "x86_64" || machine == "amd64")
    return if (supported) {
      Ok(Unit)
    } else {
      Err(SelfUpdateError.Platform(sysname = sysname, machine = machine))
    }
  }

  // The installer owns `~/.local/bin/kolt` as a symlink into
  // `~/.local/share/kolt/<X.Y.Z>/bin/kolt`. Every deviation — regular file,
  // dangling link, target escaping the share root — collapses to one
  // `Layout` error; the caller only needs "re-run install.sh", not a taxonomy.
  internal fun detectLayout(): Result<Layout, SelfUpdateError> {
    val shareRoot = "$home/.local/share/kolt"
    val binSymlink = "$home/.local/bin/kolt"
    val guidance = "install.sh で入れ直してください"

    if (!isSymlink(binSymlink)) {
      return Err(
        SelfUpdateError.Layout(
          detectedPath = binSymlink,
          detail = "$binSymlink is not an installer-managed symlink; $guidance",
        )
      )
    }

    val target =
      symlinkTarget(binSymlink)
        ?: return Err(
          SelfUpdateError.Layout(
            detectedPath = binSymlink,
            detail = "could not read the symlink target of $binSymlink; $guidance",
          )
        )

    // The target must be the installer's real binary path and must still
    // exist — a dangling link points nowhere installable.
    val prefix = "$shareRoot/"
    val suffix = "/bin/kolt"
    val matchesLayout = target.startsWith(prefix) && target.endsWith(suffix) && fileExists(target)
    if (!matchesLayout) {
      return Err(
        SelfUpdateError.Layout(
          detectedPath = target,
          detail = "$binSymlink does not point under $shareRoot/<version>/bin/kolt; $guidance",
        )
      )
    }

    val currentInstallDir = target.removeSuffix(suffix)
    return Ok(
      Layout(binSymlink = binSymlink, currentInstallDir = currentInstallDir, shareRoot = shareRoot)
    )
  }

  // Refuse before any network or extraction work if the user cannot write
  // the share root (where the new version dir lands) or the bin symlink
  // (which `rename(2)` swaps).
  internal fun verifyWritable(layout: Layout): Result<Unit, SelfUpdateError> {
    if (!canWrite(layout.shareRoot)) {
      return Err(
        SelfUpdateError.Layout(
          detectedPath = layout.shareRoot,
          detail = "no write permission on ${layout.shareRoot}",
        )
      )
    }
    if (!canWrite(layout.binSymlink)) {
      return Err(
        SelfUpdateError.Layout(
          detectedPath = layout.binSymlink,
          detail = "no write permission on ${layout.binSymlink}",
        )
      )
    }
    return Ok(Unit)
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun isSymlink(path: String): Boolean = memScoped {
    val st = alloc<stat>()
    if (lstat(path, st.ptr) != 0) return@memScoped false
    (st.st_mode.toInt() and S_IFMT) == S_IFLNK
  }

  // readlink does not NUL-terminate; reserve one byte and add it.
  @OptIn(ExperimentalForeignApi::class)
  private fun symlinkTarget(path: String): String? = memScoped {
    val buf = allocArray<ByteVar>(PATH_MAX)
    val n = readlink(path, buf, (PATH_MAX - 1).convert())
    if (n < 0) return@memScoped null
    buf[n] = 0
    buf.toKString()
  }

  data class Layout(
    val binSymlink: String, // ~/.local/bin/kolt
    val currentInstallDir: String, // ~/.local/share/kolt/<ver>/
    val shareRoot: String, // ~/.local/share/kolt/
  )
}

sealed interface CheckOutcome {
  val current: String

  data class UpdateAvailable(override val current: String, val latest: String) : CheckOutcome

  data class AlreadyLatest(override val current: String) : CheckOutcome
}

sealed interface UpdateOutcome {
  data class Switched(val from: String, val to: String) : UpdateOutcome

  data class NoOp(val current: String) : UpdateOutcome
}
