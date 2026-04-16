package kolt.infra

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.remove
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class FileSystemTest {

    @Test
    fun readExistingFileReturnsOk() {
        val path = "/tmp/kolt_test_read.txt"
        writeTestFile(path, "hello kolt")
        try {
            val result = readFileAsString(path)

            val content = assertNotNull(result.get())
            assertEquals("hello kolt", content)
        } finally {
            remove(path)
        }
    }

    @Test
    fun readEmptyFileReturnsOk() {
        val path = "/tmp/kolt_test_empty.txt"
        writeTestFile(path, "")
        try {
            val result = readFileAsString(path)

            val content = assertNotNull(result.get())
            assertEquals("", content)
        } finally {
            remove(path)
        }
    }

    @Test
    fun readNonExistentFileReturnsErr() {
        val result = readFileAsString("/tmp/kolt_nonexistent_file.txt")

        assertNull(result.get())
        assertIs<OpenFailed>(result.getError())
    }

    @Test
    fun fileExistsReturnsTrueForExistingFile() {
        val path = "/tmp/kolt_test_exists.txt"
        writeTestFile(path, "x")
        try {
            assertTrue(fileExists(path))
        } finally {
            remove(path)
        }
    }

    @Test
    fun fileExistsReturnsFalseForNonExistentFile() {
        assertFalse(fileExists("/tmp/kolt_nonexistent_file.txt"))
    }

    @Test
    fun ensureDirectoryCreatesNewDirectory() {
        val path = "/tmp/kolt_test_ensure_dir"
        remove(path)
        try {
            val result = ensureDirectory(path)

            assertNotNull(result.get())
            assertTrue(fileExists(path))
        } finally {
            platform.posix.rmdir(path)
        }
    }

    @Test
    fun ensureDirectorySucceedsWhenAlreadyExists() {
        val path = "/tmp/kolt_test_ensure_dir_exists"
        platform.posix.mkdir(path, 0b111111101u)
        try {
            val result = ensureDirectory(path)

            assertNotNull(result.get())
        } finally {
            platform.posix.rmdir(path)
        }
    }

    @Test
    fun ensureDirectoryReturnsErrOnInvalidPath() {
        val result = ensureDirectory("/nonexistent_root/subdir")

        assertNull(result.get())
        assertIs<MkdirFailed>(result.getError())
    }

    @Test
    fun writeFileAsStringCreatesFile() {
        val path = "/tmp/kolt_test_write.txt"
        remove(path)
        try {
            val result = writeFileAsString(path, "hello write")
            assertNotNull(result.get())
            assertEquals("hello write", assertNotNull(readFileAsString(path).get()))
        } finally {
            remove(path)
        }
    }

    @Test
    fun writeFileAsStringOverwritesExisting() {
        val path = "/tmp/kolt_test_write_overwrite.txt"
        writeTestFile(path, "old content")
        try {
            writeFileAsString(path, "new content")
            assertEquals("new content", assertNotNull(readFileAsString(path).get()))
        } finally {
            remove(path)
        }
    }

    @Test
    fun writeFileAsStringReturnsErrOnInvalidPath() {
        val result = writeFileAsString("/nonexistent_root/file.txt", "data")
        assertIs<WriteFailed>(result.getError())
    }

    @Test
    fun ensureDirectoryRecursiveCreatesNestedDirs() {
        val base = "/tmp/kolt_test_recursive"
        val path = "$base/a/b/c"
        try {
            val result = ensureDirectoryRecursive(path)
            assertNotNull(result.get())
            assertTrue(fileExists(path))
        } finally {
            platform.posix.rmdir("$base/a/b/c")
            platform.posix.rmdir("$base/a/b")
            platform.posix.rmdir("$base/a")
            platform.posix.rmdir(base)
        }
    }

    @Test
    fun ensureDirectoryRecursiveSucceedsWhenAlreadyExists() {
        val path = "/tmp/kolt_test_recursive_exists"
        platform.posix.mkdir(path, 0b111111101u)
        try {
            val result = ensureDirectoryRecursive(path)
            assertNotNull(result.get())
        } finally {
            platform.posix.rmdir(path)
        }
    }

    @Test
    fun homeDirectoryReturnsOk() {
        val result = homeDirectory()
        val home = assertNotNull(result.get())
        assertTrue(home.isNotEmpty())
        assertTrue(fileExists(home))
    }

    @Test
    fun fileMtimeReturnsValueForExistingFile() {
        val path = "/tmp/kolt_test_mtime.txt"
        writeTestFile(path, "mtime test")
        try {
            val mtime = fileMtime(path)
            assertNotNull(mtime)
            assertTrue(mtime > 0L)
        } finally {
            remove(path)
        }
    }

    @Test
    fun fileMtimeReturnsNullForNonExistentFile() {
        assertNull(fileMtime("/tmp/kolt_nonexistent_mtime.txt"))
    }

    @Test
    fun newestMtimeReturnsNewestFileInDirectory() {
        val dir = "/tmp/kolt_test_newest_mtime"
        platform.posix.mkdir(dir, 0b111111101u)
        writeTestFile("$dir/a.kt", "a")
        // ext4 has 1-second mtime granularity; sleep to ensure distinct mtimes
        platform.posix.sleep(1u)
        writeTestFile("$dir/b.kt", "b")
        try {
            val newest = newestMtime(listOf(dir))
            val bMtime = fileMtime("$dir/b.kt")
            assertNotNull(newest)
            assertNotNull(bMtime)
            assertEquals(bMtime, newest)
        } finally {
            remove("$dir/a.kt")
            remove("$dir/b.kt")
            platform.posix.rmdir(dir)
        }
    }

    @Test
    fun newestMtimeReturnsZeroForEmptyDirectory() {
        val dir = "/tmp/kolt_test_newest_empty"
        platform.posix.mkdir(dir, 0b111111101u)
        try {
            val newest = newestMtime(listOf(dir))
            assertEquals(0L, newest)
        } finally {
            platform.posix.rmdir(dir)
        }
    }

    @Test
    fun newestMtimeAllReturnsNewestFileInDirectory() {
        val dir = "/tmp/kolt_test_newest_mtime_all"
        platform.posix.mkdir(dir, 0b111111101u)
        writeTestFile("$dir/a.txt", "a")
        // ext4 has 1-second mtime granularity; sleep to ensure distinct mtimes
        platform.posix.sleep(1u)
        writeTestFile("$dir/b.class", "b")
        try {
            val newest = newestMtimeAll(dir)
            val bMtime = fileMtime("$dir/b.class")
            assertNotNull(bMtime)
            assertEquals(bMtime, newest)
        } finally {
            remove("$dir/a.txt")
            remove("$dir/b.class")
            platform.posix.rmdir(dir)
        }
    }

    @Test
    fun newestMtimeAllReturnsZeroForEmptyDirectory() {
        val dir = "/tmp/kolt_test_newest_all_empty"
        platform.posix.mkdir(dir, 0b111111101u)
        try {
            assertEquals(0L, newestMtimeAll(dir))
        } finally {
            platform.posix.rmdir(dir)
        }
    }

    @Test
    fun newestMtimeAllReturnsZeroForNonExistentDirectory() {
        assertEquals(0L, newestMtimeAll("/tmp/kolt_nonexistent_dir_mtime_all"))
    }

    @Test
    fun newestMtimeAllRecursesIntoSubdirectories() {
        val dir = "/tmp/kolt_test_newest_all_recursive"
        val sub = "$dir/sub"
        platform.posix.mkdir(dir, 0b111111101u)
        platform.posix.mkdir(sub, 0b111111101u)
        writeTestFile("$dir/a.txt", "a")
        // ext4 has 1-second mtime granularity; sleep to ensure distinct mtimes
        platform.posix.sleep(1u)
        writeTestFile("$sub/b.class", "b")
        try {
            val newest = newestMtimeAll(dir)
            val bMtime = fileMtime("$sub/b.class")
            assertNotNull(bMtime)
            assertEquals(bMtime, newest)
        } finally {
            remove("$dir/a.txt")
            remove("$sub/b.class")
            platform.posix.rmdir(sub)
            platform.posix.rmdir(dir)
        }
    }

    @Test
    fun expandKotlinSourcesWalksDirectoriesIntoKtFiles() {
        // Regression guard for issue #117: the daemon compile path
        // must receive individual .kt files, not directories, because
        // BTA's jvmCompilationOperationBuilder rejects directory
        // entries with `Is a directory`. Before this fix, a typical
        // `sources = ["src"]` in kolt.toml silently crashed the
        // daemon-backed compile under default-on B-2b rollout.
        val root = "/tmp/kolt_expand_sources_walk"
        val sub = "$root/sub"
        platform.posix.mkdir(root, 0b111111101u)
        platform.posix.mkdir(sub, 0b111111101u)
        writeTestFile("$root/A.kt", "package p; fun a() = 0")
        writeTestFile("$sub/B.kt", "package p.q; fun b() = 0")
        writeTestFile("$root/notes.md", "ignored")
        try {
            val files = assertNotNull(expandKotlinSources(listOf(root)).get())
            assertEquals(listOf("$root/A.kt", "$sub/B.kt"), files)
        } finally {
            remove("$root/A.kt")
            remove("$sub/B.kt")
            remove("$root/notes.md")
            platform.posix.rmdir(sub)
            platform.posix.rmdir(root)
        }
    }

    @Test
    fun expandKotlinSourcesKeepsIndividualFilesAsIs() {
        val root = "/tmp/kolt_expand_sources_individual"
        platform.posix.mkdir(root, 0b111111101u)
        writeTestFile("$root/Main.kt", "fun main() {}")
        try {
            val files = assertNotNull(expandKotlinSources(listOf("$root/Main.kt")).get())
            assertEquals(listOf("$root/Main.kt"), files)
        } finally {
            remove("$root/Main.kt")
            platform.posix.rmdir(root)
        }
    }

    @Test
    fun expandKotlinSourcesMergesDirsAndFilesPreservingCallerOrder() {
        val dir = "/tmp/kolt_expand_sources_merge_dir"
        val loose = "/tmp/kolt_expand_sources_merge_loose.kt"
        platform.posix.mkdir(dir, 0b111111101u)
        writeTestFile("$dir/Inside.kt", "fun inside() {}")
        writeTestFile(loose, "fun loose() {}")
        try {
            val files = assertNotNull(expandKotlinSources(listOf(dir, loose)).get())
            assertEquals(listOf("$dir/Inside.kt", loose), files)
        } finally {
            remove("$dir/Inside.kt")
            platform.posix.rmdir(dir)
            remove(loose)
        }
    }

    @Test
    fun expandKotlinSourcesPassesThroughNonExistentEntriesUnchanged() {
        val missing = "/tmp/kolt_expand_sources_missing_${platform.posix.getpid()}.kt"
        val files = assertNotNull(expandKotlinSources(listOf(missing)).get())
        assertEquals(listOf(missing), files)
    }

    @Test
    fun copyDirectoryContentsCopiesFilesToDest() {
        val src = "/tmp/kolt_copy_src_basic"
        val dest = "/tmp/kolt_copy_dest_basic"
        platform.posix.mkdir(src, 0b111111101u)
        platform.posix.mkdir(dest, 0b111111101u)
        writeTestFile("$src/hello.txt", "hello")
        try {
            val result = copyDirectoryContents(src, dest)

            assertNotNull(result.get())
            assertTrue(fileExists("$dest/hello.txt"))
            assertEquals("hello", assertNotNull(readFileAsString("$dest/hello.txt").get()))
        } finally {
            remove("$src/hello.txt")
            remove("$dest/hello.txt")
            platform.posix.rmdir(src)
            platform.posix.rmdir(dest)
        }
    }

    @Test
    fun copyDirectoryContentsPreservesRelativePaths() {
        val src = "/tmp/kolt_copy_src_nested"
        val sub = "$src/config"
        val dest = "/tmp/kolt_copy_dest_nested"
        platform.posix.mkdir(src, 0b111111101u)
        platform.posix.mkdir(sub, 0b111111101u)
        platform.posix.mkdir(dest, 0b111111101u)
        writeTestFile("$sub/app.properties", "key=value")
        try {
            val result = copyDirectoryContents(src, dest)

            assertNotNull(result.get())
            assertTrue(fileExists("$dest/config/app.properties"))
            assertEquals("key=value", assertNotNull(readFileAsString("$dest/config/app.properties").get()))
        } finally {
            remove("$sub/app.properties")
            remove("$dest/config/app.properties")
            platform.posix.rmdir(sub)
            platform.posix.rmdir("$dest/config")
            platform.posix.rmdir(src)
            platform.posix.rmdir(dest)
        }
    }

    @Test
    fun copyDirectoryContentsCopiesMultipleFiles() {
        val src = "/tmp/kolt_copy_src_multi"
        val dest = "/tmp/kolt_copy_dest_multi"
        platform.posix.mkdir(src, 0b111111101u)
        platform.posix.mkdir(dest, 0b111111101u)
        writeTestFile("$src/a.txt", "aaa")
        writeTestFile("$src/b.conf", "bbb")
        try {
            val result = copyDirectoryContents(src, dest)

            assertNotNull(result.get())
            assertTrue(fileExists("$dest/a.txt"))
            assertTrue(fileExists("$dest/b.conf"))
            assertEquals("aaa", assertNotNull(readFileAsString("$dest/a.txt").get()))
            assertEquals("bbb", assertNotNull(readFileAsString("$dest/b.conf").get()))
        } finally {
            remove("$src/a.txt")
            remove("$src/b.conf")
            remove("$dest/a.txt")
            remove("$dest/b.conf")
            platform.posix.rmdir(src)
            platform.posix.rmdir(dest)
        }
    }

    @Test
    fun copyDirectoryContentsOverwritesExistingFile() {
        val src = "/tmp/kolt_copy_src_overwrite"
        val dest = "/tmp/kolt_copy_dest_overwrite"
        platform.posix.mkdir(src, 0b111111101u)
        platform.posix.mkdir(dest, 0b111111101u)
        writeTestFile("$src/data.txt", "new content")
        writeTestFile("$dest/data.txt", "old content")
        try {
            copyDirectoryContents(src, dest)

            assertEquals("new content", assertNotNull(readFileAsString("$dest/data.txt").get()))
        } finally {
            remove("$src/data.txt")
            remove("$dest/data.txt")
            platform.posix.rmdir(src)
            platform.posix.rmdir(dest)
        }
    }

    @Test
    fun copyDirectoryContentsForNonExistentSrcReturnsErr() {
        val result = copyDirectoryContents(
            src = "/tmp/kolt_copy_nonexistent_src",
            dest = "/tmp/kolt_copy_dest_err"
        )

        assertNull(result.get())
        assertIs<CopyFailed>(result.getError())
    }

    @Test
    fun copyDirectoryContentsForEmptySrcSucceeds() {
        val src = "/tmp/kolt_copy_src_empty"
        val dest = "/tmp/kolt_copy_dest_empty"
        platform.posix.mkdir(src, 0b111111101u)
        platform.posix.mkdir(dest, 0b111111101u)
        try {
            val result = copyDirectoryContents(src, dest)

            assertNotNull(result.get())
        } finally {
            platform.posix.rmdir(src)
            platform.posix.rmdir(dest)
        }
    }

    @Test
    fun listSubdirectoriesReturnsSortedNames() {
        val base = "/tmp/kolt_list_subdirs_sorted"
        platform.posix.mkdir(base, 0b111111101u)
        platform.posix.mkdir("$base/beta", 0b111111101u)
        platform.posix.mkdir("$base/alpha", 0b111111101u)
        platform.posix.mkdir("$base/gamma", 0b111111101u)
        try {
            val result = listSubdirectories(base)

            assertEquals(listOf("alpha", "beta", "gamma"), result.get())
        } finally {
            platform.posix.rmdir("$base/alpha")
            platform.posix.rmdir("$base/beta")
            platform.posix.rmdir("$base/gamma")
            platform.posix.rmdir(base)
        }
    }

    @Test
    fun listSubdirectoriesReturnsEmptyForEmptyDir() {
        val base = "/tmp/kolt_list_subdirs_empty"
        platform.posix.mkdir(base, 0b111111101u)
        try {
            val result = listSubdirectories(base)

            assertEquals(emptyList(), result.get())
        } finally {
            platform.posix.rmdir(base)
        }
    }

    @Test
    fun listSubdirectoriesReturnsErrForNonExistentDir() {
        val result = listSubdirectories("/tmp/kolt_list_subdirs_nonexistent")

        assertNull(result.get())
        assertIs<ListFilesFailed>(result.getError())
    }

    @Test
    fun listSubdirectoriesExcludesFiles() {
        val base = "/tmp/kolt_list_subdirs_mixed"
        platform.posix.mkdir(base, 0b111111101u)
        platform.posix.mkdir("$base/subdir", 0b111111101u)
        writeTestFile("$base/file.txt", "content")
        try {
            val result = listSubdirectories(base)

            assertEquals(listOf("subdir"), result.get())
        } finally {
            remove("$base/file.txt")
            platform.posix.rmdir("$base/subdir")
            platform.posix.rmdir(base)
        }
    }

    @Test
    fun listJarFilesReturnsSortedJarPathsOnly() {
        val base = "/tmp/kolt_list_jars_sorted"
        platform.posix.mkdir(base, 0b111111101u)
        writeTestFile("$base/zeta.jar", "z")
        writeTestFile("$base/alpha.jar", "a")
        writeTestFile("$base/beta.jar", "b")
        writeTestFile("$base/notes.txt", "ignored")
        platform.posix.mkdir("$base/nested.jar", 0b111111101u)
        try {
            val result = listJarFiles(base)
            assertEquals(
                listOf("$base/alpha.jar", "$base/beta.jar", "$base/zeta.jar"),
                result.get(),
            )
        } finally {
            remove("$base/zeta.jar")
            remove("$base/alpha.jar")
            remove("$base/beta.jar")
            remove("$base/notes.txt")
            platform.posix.rmdir("$base/nested.jar")
            platform.posix.rmdir(base)
        }
    }

    @Test
    fun listJarFilesReturnsEmptyWhenNoJars() {
        val base = "/tmp/kolt_list_jars_empty"
        platform.posix.mkdir(base, 0b111111101u)
        writeTestFile("$base/readme.md", "hi")
        try {
            val result = listJarFiles(base)
            assertEquals(emptyList(), result.get())
        } finally {
            remove("$base/readme.md")
            platform.posix.rmdir(base)
        }
    }

    @Test
    fun listJarFilesReturnsErrForNonExistentDir() {
        val result = listJarFiles("/tmp/kolt_list_jars_nonexistent")
        assertNull(result.get())
        assertIs<ListFilesFailed>(result.getError())
    }

    @Test
    fun absolutisePreservesAbsolutePath() {
        assertEquals("/a/b/c", absolutise("/a/b/c", "/ignored"))
    }

    @Test
    fun absolutisePrependsCwdToRelativePath() {
        assertEquals("/proj/src/Main.kt", absolutise("src/Main.kt", "/proj"))
    }

    @Test
    fun absolutiseCollapsesTrailingSlashOnCwd() {
        assertEquals("/proj/build/classes", absolutise("build/classes", "/proj/"))
    }

    @Test
    fun absolutiseDoesNotResolveDotSegments() {
        // Intentional: absolutise is a simple join, not a canonicaliser.
        assertEquals("/proj/./a", absolutise("./a", "/proj"))
    }

    @Test
    fun listJarFilesFollowsSymlinkToRegularFileAndRejectsSymlinkToDirectory() {
        val base = "/tmp/kolt_list_jars_symlinks"
        val realDir = "/tmp/kolt_list_jars_symlinks_target"
        platform.posix.mkdir(base, 0b111111101u)
        platform.posix.mkdir(realDir, 0b111111101u)
        writeTestFile("$realDir/real.jar", "r")
        platform.posix.symlink("$realDir/real.jar", "$base/linked.jar")
        platform.posix.symlink(realDir, "$base/dirlink.jar")
        try {
            val result = listJarFiles(base)
            assertEquals(listOf("$base/linked.jar"), result.get())
        } finally {
            remove("$base/linked.jar")
            remove("$base/dirlink.jar")
            remove("$realDir/real.jar")
            platform.posix.rmdir(realDir)
            platform.posix.rmdir(base)
        }
    }

    private fun writeTestFile(path: String, content: String) {
        val fp = platform.posix.fopen(path, "w") ?: error("could not create test file: $path")
        if (content.isNotEmpty()) {
            platform.posix.fputs(content, fp)
        }
        platform.posix.fclose(fp)
    }
}
