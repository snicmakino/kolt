package kolt.infra

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.S_IXUSR
import platform.posix.lstat
import platform.posix.mkdtemp
import platform.posix.readlink
import platform.posix.stat

@OptIn(ExperimentalForeignApi::class)
class ArchiveExtractionTest {

  @Test
  fun extractsZipWithRegularFileExecutableSubdirAndInternalSymlink() {
    val archive = fixturePath("happy.zip")
    val tempDir = createTempDir("kolt_extract_zip_")
    try {
      val result = extractArchive(archive, tempDir)
      if (result.getError() != null) fail("extractArchive failed: ${result.getError()}")

      assertTrue(
        isRegularFile("$tempDir/regular.txt"),
        "regular.txt should exist as a regular file",
      )
      assertEquals("regular content\n", readSmallFile("$tempDir/regular.txt"))

      assertTrue(isRegularFile("$tempDir/executable.sh"), "executable.sh should exist")
      assertTrue(
        hasExecutableBit("$tempDir/executable.sh"),
        "executable.sh should have user-exec bit",
      )

      assertTrue(isDirectory("$tempDir/subdir"), "subdir/ should be a directory")
      assertTrue(isRegularFile("$tempDir/subdir/nested.txt"), "subdir/nested.txt should exist")

      assertTrue(isSymlink("$tempDir/link-to-regular"), "link-to-regular should be a symlink")
      assertEquals("regular.txt", readSymlinkTarget("$tempDir/link-to-regular"))
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun extractsTarGzWithRegularFileExecutableSubdirAndInternalSymlink() {
    val archive = fixturePath("happy.tar.gz")
    val tempDir = createTempDir("kolt_extract_tar_")
    try {
      val result = extractArchive(archive, tempDir)
      if (result.getError() != null) fail("extractArchive failed: ${result.getError()}")

      assertTrue(
        isRegularFile("$tempDir/regular.txt"),
        "regular.txt should exist as a regular file",
      )
      assertEquals("regular content\n", readSmallFile("$tempDir/regular.txt"))

      assertTrue(isRegularFile("$tempDir/executable.sh"), "executable.sh should exist")
      assertTrue(
        hasExecutableBit("$tempDir/executable.sh"),
        "executable.sh should have user-exec bit",
      )

      assertTrue(isDirectory("$tempDir/subdir"), "subdir/ should be a directory")
      assertTrue(isRegularFile("$tempDir/subdir/nested.txt"), "subdir/nested.txt should exist")

      assertTrue(isSymlink("$tempDir/link-to-regular"), "link-to-regular should be a symlink")
      assertEquals("regular.txt", readSymlinkTarget("$tempDir/link-to-regular"))
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun returnsArchiveNotFoundWhenArchivePathDoesNotExist() {
    val tempDir = createTempDir("kolt_extract_missing_")
    try {
      val result = extractArchive("/nonexistent/path/to/archive.zip", tempDir)
      assertIs<ExtractError.ArchiveNotFound>(result.getError())
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun rejectsPathTraversalEntryWithSecurityViolation() {
    val archive = fixturePath("path-traversal.zip")
    val (grandparent, destDir) = createNestedDestDir("kolt_extract_traversal_")
    try {
      val result = extractArchive(archive, destDir)

      val error = result.getError()
      assertIs<ExtractError.SecurityViolation>(error)
      assertTrue(error.message.isNotEmpty(), "SecurityViolation message should be non-empty")

      assertTrue(
        !fileExists("$grandparent/escape.txt"),
        "../escape.txt must not be written outside destDir",
      )
      assertEquals(
        emptyList(),
        listFiles(destDir).get() ?: error("listFiles failed"),
        "destDir must be empty after rejected extraction",
      )
    } finally {
      removeDirectoryRecursive(grandparent)
    }
  }

  @Test
  fun rejectsAbsolutePathEntryWithSecurityViolation() {
    val archive = fixturePath("absolute-path.zip")
    val (grandparent, destDir) = createNestedDestDir("kolt_extract_absolute_")
    try {
      val result = extractArchive(archive, destDir)

      val error = result.getError()
      assertIs<ExtractError.SecurityViolation>(error)
      assertTrue(error.message.isNotEmpty(), "SecurityViolation message should be non-empty")

      assertEquals(
        emptyList(),
        listFiles(destDir).get() ?: error("listFiles failed"),
        "destDir must be empty after rejected extraction",
      )
    } finally {
      removeDirectoryRecursive(grandparent)
    }
  }

  @Test
  fun rejectsExternalSymlinkEntryWithSecurityViolation() {
    val archive = fixturePath("external-symlink.tar.gz")
    val (grandparent, destDir) = createNestedDestDir("kolt_extract_extsymlink_")
    try {
      val result = extractArchive(archive, destDir)

      val error = result.getError()
      assertIs<ExtractError.SecurityViolation>(error)
      assertTrue(error.message.isNotEmpty(), "SecurityViolation message should be non-empty")

      assertTrue(
        !fileExists("$grandparent/outside"),
        "external symlink target must not be reachable above destDir",
      )
      assertTrue(!isSymlink("$destDir/escape-link"), "external symlink must not be written")
    } finally {
      removeDirectoryRecursive(grandparent)
    }
  }

  @Test
  fun returnsReadFailedForCorruptArchive() {
    val archive = fixturePath("corrupt.zip")
    val tempDir = createTempDir("kolt_extract_corrupt_")
    try {
      val result = extractArchive(archive, tempDir)

      val error = result.getError()
      assertIs<ExtractError.ReadFailed>(error)
      assertTrue(error.message.isNotEmpty(), "ReadFailed message should be non-empty")
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  private fun createNestedDestDir(prefix: String): Pair<String, String> {
    val grandparent = createTempDir(prefix)
    val dest = "$grandparent/dest"
    val r = ensureDirectoryRecursive(dest)
    if (r.getError() != null) error("ensureDirectoryRecursive failed: ${r.getError()}")
    return grandparent to dest
  }

  private fun fixturePath(name: String): String {
    val root = projectRoot()
    return "$root/src/nativeTest/resources/archive-fixtures/$name"
  }

  private fun projectRoot(): String {
    var current = currentWorkingDirectory() ?: fail("could not resolve cwd")
    while (current.isNotEmpty() && current != "/") {
      if (fileExists("$current/.git")) return current
      val cut = current.lastIndexOf('/')
      if (cut <= 0) break
      current = current.substring(0, cut)
    }
    fail("could not locate project root (no .git ancestor) from cwd")
  }

  private fun createTempDir(prefix: String): String {
    val template = "/tmp/${prefix}XXXXXX"
    val buf = template.encodeToByteArray().copyOf(template.length + 1)
    buf.usePinned { pinned ->
      val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
      return result.toKString()
    }
  }

  private fun hasExecutableBit(path: String): Boolean = memScoped {
    val statBuf = alloc<stat>()
    if (stat(path, statBuf.ptr) != 0) return false
    (statBuf.st_mode.toInt() and S_IXUSR) != 0
  }

  private fun isSymlink(path: String): Boolean = memScoped {
    val statBuf = alloc<stat>()
    if (lstat(path, statBuf.ptr) != 0) return false
    (statBuf.st_mode.toInt() and S_IFMT) == S_IFLNK
  }

  private fun readSymlinkTarget(path: String): String = memScoped {
    val bufSize = 4096
    val buf = allocArray<ByteVar>(bufSize)
    val n = readlink(path, buf, (bufSize - 1).convert())
    if (n < 0) error("readlink failed for $path")
    buf[n.toInt()] = 0
    buf.toKString()
  }

  private fun readSmallFile(path: String): String {
    val r = readFileAsString(path)
    return r.get() ?: error("readFileAsString failed: ${r.getError()}")
  }
}
