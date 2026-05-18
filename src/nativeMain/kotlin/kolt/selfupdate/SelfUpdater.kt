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
import kolt.infra.fileExists
import kolt.infra.readSelfExe
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
import platform.posix.lstat
import platform.posix.readlink
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
