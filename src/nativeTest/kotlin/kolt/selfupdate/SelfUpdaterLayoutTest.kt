package kolt.selfupdate

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.downloadFile
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.removeDirectoryRecursive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.close
import platform.posix.getpid
import platform.posix.open
import platform.posix.symlink

@OptIn(ExperimentalForeignApi::class)
class SelfUpdaterLayoutTest {

  private fun tempHome(): String = "/tmp/kolt_selfupdate_layout_${getpid()}"

  private fun touch(path: String) {
    val fd = open(path, 0x40 /* O_CREAT */ or 0x1 /* O_WRONLY */, 0b111100100u /* 0644 */)
    if (fd < 0) fail("could not create $path")
    close(fd)
  }

  private fun releases(): GithubReleasesClient =
    GithubReleasesClient(
      downloader = ::downloadFile,
      userAgent = "kolt/test",
      tempPathFactory = { "/tmp/kolt_selfupdate_layout_unused_${getpid()}" },
    )

  private fun updater(
    home: String,
    currentVersion: String = "0.20.0",
    canWrite: (String) -> Boolean = { true },
    uname: () -> Pair<String, String> = { "Linux" to "x86_64" },
  ): SelfUpdater =
    SelfUpdater(
      releases = releases(),
      home = home,
      currentVersion = currentVersion,
      canWrite = canWrite,
      uname = uname,
    )

  // The installer layout: ~/.local/bin/kolt is a symlink whose target lives
  // under ~/.local/share/kolt/<ver>/bin/kolt. detectLayout must accept it and
  // hand back the three resolved paths the rest of update() relies on.
  @Test
  fun detectLayoutAcceptsInstallerSymlink() {
    val home = tempHome()
    removeDirectoryRecursive(home)
    try {
      ensureDirectoryRecursive("$home/.local/bin").getOrElse { fail("mkdir bin failed") }
      ensureDirectoryRecursive("$home/.local/share/kolt/0.20.0/bin").getOrElse {
        fail("mkdir share failed")
      }
      val realBin = "$home/.local/share/kolt/0.20.0/bin/kolt"
      touch(realBin)
      val link = "$home/.local/bin/kolt"
      if (symlink(realBin, link) != 0) fail("setup symlink failed")

      val layout =
        updater(home).detectLayout().get() ?: fail("detectLayout should accept installer layout")

      assertEquals("$home/.local/bin/kolt", layout.binSymlink)
      assertEquals("$home/.local/share/kolt/0.20.0", layout.currentInstallDir)
      assertEquals("$home/.local/share/kolt", layout.shareRoot)
    } finally {
      removeDirectoryRecursive(home)
    }
  }

  // A plain regular file at ~/.local/bin/kolt is not the installer layout.
  @Test
  fun detectLayoutRejectsRegularFile() {
    val home = tempHome()
    removeDirectoryRecursive(home)
    try {
      ensureDirectoryRecursive("$home/.local/bin").getOrElse { fail("mkdir bin failed") }
      touch("$home/.local/bin/kolt")

      val err = updater(home).detectLayout().getError() ?: fail("expected Layout error")
      assertIs<SelfUpdateError.Layout>(err)
      assertTrue(
        err.detail.contains("install.sh"),
        "guidance must mention install.sh, got: ${err.detail}",
      )
    } finally {
      removeDirectoryRecursive(home)
    }
  }

  // A dangling symlink (target missing) is not the installer layout.
  @Test
  fun detectLayoutRejectsDanglingSymlink() {
    val home = tempHome()
    removeDirectoryRecursive(home)
    try {
      ensureDirectoryRecursive("$home/.local/bin").getOrElse { fail("mkdir bin failed") }
      val link = "$home/.local/bin/kolt"
      if (symlink("$home/.local/share/kolt/0.20.0/bin/kolt", link) != 0) {
        fail("setup symlink failed")
      }

      val err = updater(home).detectLayout().getError() ?: fail("expected Layout error")
      assertIs<SelfUpdateError.Layout>(err)
    } finally {
      removeDirectoryRecursive(home)
    }
  }

  // A symlink whose target escapes ~/.local/share/kolt/ is not the installer
  // layout — a root-owned /usr/local/bin/kolt-style target must be refused.
  @Test
  fun detectLayoutRejectsTargetOutsideShareRoot() {
    val home = tempHome()
    removeDirectoryRecursive(home)
    try {
      ensureDirectoryRecursive("$home/.local/bin").getOrElse { fail("mkdir bin failed") }
      ensureDirectoryRecursive("$home/elsewhere").getOrElse { fail("mkdir elsewhere failed") }
      val realBin = "$home/elsewhere/kolt"
      touch(realBin)
      val link = "$home/.local/bin/kolt"
      if (symlink(realBin, link) != 0) fail("setup symlink failed")

      val err = updater(home).detectLayout().getError() ?: fail("expected Layout error")
      assertIs<SelfUpdateError.Layout>(err)
      assertTrue(
        err.detectedPath.contains("elsewhere"),
        "error should carry the detected real path, got: ${err.detectedPath}",
      )
    } finally {
      removeDirectoryRecursive(home)
    }
  }

  // verifyWritable refuses when the share root or the bin symlink is not
  // writable by the current user (canWrite seam returns false).
  @Test
  fun verifyWritableRejectsUnwritableShareRoot() {
    val home = tempHome()
    val shareRoot = "$home/.local/share/kolt"
    val layout =
      SelfUpdater.Layout(
        binSymlink = "$home/.local/bin/kolt",
        currentInstallDir = "$shareRoot/0.20.0",
        shareRoot = shareRoot,
      )

    val err =
      updater(home, canWrite = { it != shareRoot }).verifyWritable(layout).getError()
        ?: fail("expected Layout error for unwritable share root")
    assertIs<SelfUpdateError.Layout>(err)
    assertTrue(
      err.detectedPath.contains(shareRoot),
      "error should name the unwritable path, got: ${err.detectedPath}",
    )
  }

  @Test
  fun verifyWritableAcceptsWritableLayout() {
    val home = tempHome()
    val shareRoot = "$home/.local/share/kolt"
    val layout =
      SelfUpdater.Layout(
        binSymlink = "$home/.local/bin/kolt",
        currentInstallDir = "$shareRoot/0.20.0",
        shareRoot = shareRoot,
      )

    val ok = updater(home, canWrite = { true }).verifyWritable(layout)
    assertEquals(Unit, ok.get(), "all-writable layout should pass verifyWritable")
  }

  // Platform gate: non-Linux sysname or non-x86_64 machine is refused with the
  // detected platform identifiers; Linux/x86_64 passes.
  @Test
  fun ensureLinuxX64RejectsNonLinux() {
    val err =
      updater(tempHome(), uname = { "Darwin" to "arm64" }).ensureLinuxX64().getError()
        ?: fail("expected Platform error on non-Linux")
    assertIs<SelfUpdateError.Platform>(err)
    assertEquals("Darwin", err.sysname)
    assertEquals("arm64", err.machine)
  }

  @Test
  fun ensureLinuxX64RejectsNonX86() {
    val err =
      updater(tempHome(), uname = { "Linux" to "aarch64" }).ensureLinuxX64().getError()
        ?: fail("expected Platform error on non-x86_64")
    assertIs<SelfUpdateError.Platform>(err)
    assertEquals("aarch64", err.machine)
  }

  @Test
  fun ensureLinuxX64AcceptsLinuxX86() {
    val ok = updater(tempHome(), uname = { "Linux" to "x86_64" }).ensureLinuxX64()
    assertEquals(Unit, ok.get(), "Linux/x86_64 should pass the platform gate")
  }

  @Test
  fun ensureLinuxX64AcceptsLinuxAmd64() {
    val ok = updater(tempHome(), uname = { "Linux" to "amd64" }).ensureLinuxX64()
    assertEquals(Unit, ok.get(), "Linux/amd64 alias should pass the platform gate")
  }
}
