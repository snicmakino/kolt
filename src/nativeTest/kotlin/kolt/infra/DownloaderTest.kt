package kolt.infra

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.remove
import platform.posix.rmdir
import platform.posix.time
import platform.posix.time_tVar
import platform.posix.utimbuf
import platform.posix.utime

@OptIn(ExperimentalForeignApi::class)
class DownloaderTest {

  @Test
  fun downloadSmallFileFromMavenCentral() {
    val destPath = "/tmp/kolt_test_download.xml"
    remove(destPath)
    try {
      val url = "https://repo1.maven.org/maven2/junit/junit/maven-metadata.xml"
      val result = downloadFile(url, destPath)
      assertNotNull(result.get())
      assertTrue(fileExists(destPath))
      val content = assertNotNull(readFileAsString(destPath).get())
      assertTrue(content.contains("<artifactId>junit</artifactId>"))
    } finally {
      remove(destPath)
    }
  }

  @Test
  fun downloadToNonExistentDirectoryReturnsErr() {
    val url = "https://repo1.maven.org/maven2/junit/junit/maven-metadata.xml"
    val result = downloadFile(url, "/nonexistent_root/subdir/file.xml")
    assertIs<DownloadError>(result.getError())
  }

  @Test
  fun downloadNonExistentUrlReturnsErr() {
    val destPath = "/tmp/kolt_test_download_404.txt"
    try {
      val url = "https://repo1.maven.org/maven2/nonexistent/nonexistent/0.0.0/nonexistent-0.0.0.jar"
      val result = downloadFile(url, destPath)
      assertIs<DownloadError>(result.getError())
    } finally {
      remove(destPath)
    }
  }

  // Req 2.1, 2.2: a successful download must materialise via a temp+rename
  // sequence so the final path is either absent or fully written. After Ok,
  // no `*.tmp.*` siblings may remain in the parent directory.
  @Test
  fun successfulDownloadLeavesNoTempSibling() {
    val dir = "/tmp/kolt_dl_test_success_${currentPid()}"
    cleanDirectory(dir)
    mkdir(dir, 0b111111101u) // 0755
    val destPath = "$dir/maven-metadata.xml"
    try {
      val url = "https://repo1.maven.org/maven2/junit/junit/maven-metadata.xml"
      val result = downloadFile(url, destPath)
      assertNotNull(result.get())
      assertTrue(fileExists(destPath), "destPath must exist after successful download")
      val siblings = listDirEntries(dir)
      val temps = siblings.filter { it.contains(".tmp.") }
      assertTrue(temps.isEmpty(), "expected no .tmp.* siblings, found $temps")
    } finally {
      cleanDirectory(dir)
      rmdir(dir)
    }
  }

  // Req 2.3: HTTP failure must not change destPath and must not leave any
  // intermediate `.tmp.<pid>` file behind.
  @Test
  fun httpFailurePreservesDestPathAndLeavesNoTemp() {
    val dir = "/tmp/kolt_dl_test_404_${currentPid()}"
    cleanDirectory(dir)
    mkdir(dir, 0b111111101u)
    val destPath = "$dir/preserved.jar"
    val sentinel = "PRE-EXISTING-CONTENT\n"
    try {
      writeFileAsString(destPath, sentinel)
      val url = "https://repo1.maven.org/maven2/nonexistent/nonexistent/0.0.0/nonexistent-0.0.0.jar"
      val result = downloadFile(url, destPath)
      assertIs<DownloadError>(result.getError())
      assertTrue(fileExists(destPath), "destPath must remain after failed download")
      val content = assertNotNull(readFileAsString(destPath).get())
      assertEquals(sentinel, content, "destPath content must be unchanged on failure")
      val siblings = listDirEntries(dir)
      val temps = siblings.filter { it.contains(".tmp.") }
      assertTrue(temps.isEmpty(), "expected no .tmp.* leftover, found $temps")
    } finally {
      cleanDirectory(dir)
      rmdir(dir)
    }
  }

  // Per design.md §324 (Downloader 改修) the SHA-256 verification stays in
  // the caller (TransitiveResolver / PluginJarFetcher). Downloader's contract
  // is "atomic write" only. We therefore exercise the rename-failure analogue
  // — a download whose URL fails after the temp file has been opened — and
  // simultaneously verify the per-pid temp naming convention by checking
  // that no leftover sibling files remain.
  @Test
  fun networkErrorRemovesTempAndDoesNotTouchDestPath() {
    val dir = "/tmp/kolt_dl_test_net_${currentPid()}"
    cleanDirectory(dir)
    mkdir(dir, 0b111111101u)
    val destPath = "$dir/should-not-appear.jar"
    try {
      // Unresolvable host triggers libcurl network error after fopen has
      // already created the temp file — exercising the failure cleanup
      // branch without depending on internal SHA verification.
      val url = "https://kolt-test-nonexistent-host.invalid/file.jar"
      val result = downloadFile(url, destPath)
      assertIs<DownloadError>(result.getError())
      assertFalse(fileExists(destPath), "destPath must not appear on network error")
      val siblings = listDirEntries(dir)
      val temps = siblings.filter { it.contains(".tmp.") }
      assertTrue(temps.isEmpty(), "expected no .tmp.* leftover, found $temps")
    } finally {
      cleanDirectory(dir)
      rmdir(dir)
    }
  }

  // Req 2.3 housekeeping: stale `.tmp.<pid>` files left by a crashed prior
  // process must be swept on next download (mtime > 24h ago). Recent temps
  // belonging to potentially live siblings must be left alone.
  @Test
  fun staleTempIsSweptOnDownload() {
    val dir = "/tmp/kolt_dl_test_sweep_${currentPid()}"
    cleanDirectory(dir)
    mkdir(dir, 0b111111101u)
    val destPath = "$dir/maven-metadata.xml"
    val staleTemp = "$dir/maven-metadata.xml.tmp.999999"
    val recentTemp = "$dir/maven-metadata.xml.tmp.999998"
    try {
      writeFileAsString(staleTemp, "stale-bytes")
      writeFileAsString(recentTemp, "recent-bytes")
      // Backdate stale temp to 48 hours ago; leave recent temp at "now".
      val twoDaysAgoSec: Long = -2L * 24L * 3600L
      backdateMtime(staleTemp, secondsFromNow = twoDaysAgoSec)

      val url = "https://repo1.maven.org/maven2/junit/junit/maven-metadata.xml"
      val result = downloadFile(url, destPath)
      assertNotNull(result.get())

      assertFalse(fileExists(staleTemp), "stale temp must be swept")
      assertTrue(fileExists(recentTemp), "recent temp must be left alone")
      assertTrue(fileExists(destPath), "destPath must materialise")
    } finally {
      cleanDirectory(dir)
      rmdir(dir)
    }
  }
}

@OptIn(ExperimentalForeignApi::class) private fun currentPid(): Int = platform.posix.getpid()

@OptIn(ExperimentalForeignApi::class)
private fun listDirEntries(path: String): List<String> {
  val dir = opendir(path) ?: return emptyList()
  val out = mutableListOf<String>()
  try {
    while (true) {
      val entry = readdir(dir) ?: break
      val name = entry.pointed.d_name.toKString()
      if (name == "." || name == "..") continue
      out.add(name)
    }
  } finally {
    closedir(dir)
  }
  return out
}

@OptIn(ExperimentalForeignApi::class)
private fun cleanDirectory(path: String) {
  if (!fileExists(path)) return
  for (name in listDirEntries(path)) {
    remove("$path/$name")
  }
}

// utime adjusts both atime and mtime to (now + offsetSeconds). 1-second
// granularity is fine for the 24h sweep test.
@OptIn(ExperimentalForeignApi::class)
private fun backdateMtime(path: String, secondsFromNow: Long) {
  memScoped {
    val nowVar = alloc<time_tVar>()
    val now = time(nowVar.ptr)
    val target = now + secondsFromNow
    val buf = alloc<utimbuf>()
    buf.actime = target
    buf.modtime = target
    utime(path, buf.ptr)
  }
}
