package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.executeCommand
import kolt.infra.fileExists
import kolt.infra.readFileAsString
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.PATH_MAX
import platform.posix.chdir
import platform.posix.getcwd
import platform.posix.mkdtemp
import platform.posix.setenv
import platform.posix.unsetenv
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class DoInitTest {
    private var originalCwd: String = ""
    private var tmpDir: String = ""

    @BeforeTest
    fun setUp() {
        originalCwd = memScoped {
            val buf = allocArray<ByteVar>(PATH_MAX)
            getcwd(buf, PATH_MAX.toULong())?.toKString() ?: error("getcwd failed")
        }
        tmpDir = createTempDir("kolt-init-")
        check(chdir(tmpDir) == 0) { "chdir to $tmpDir failed" }
        // Pins git's upward .git search to tmpDir's parent so the
        // "outside any repo" tests see a clean state even when the tmp
        // root happens to sit under a repo. Ceiling is the parent
        // (not tmpDir itself) so the `skipsGitInitInsideExistingWorktree`
        // test can still reach a `.git` we seed in tmpDir.
        setenv("GIT_CEILING_DIRECTORIES", tmpDir.substringBeforeLast('/'), 1)
    }

    @AfterTest
    fun tearDown() {
        unsetenv("GIT_CEILING_DIRECTORIES")
        chdir(originalCwd)
        if (tmpDir.isNotEmpty() && fileExists(tmpDir)) {
            removeDirectoryRecursive(tmpDir)
        }
    }

    @Test
    fun writesGitignoreWhenAbsent() {
        doInit(listOf("my-app")).getOrElse { error("doInit failed: exit=$it") }

        assertTrue(fileExists(".gitignore"))
    }

    @Test
    fun leavesExistingGitignoreUntouched() {
        writeFileAsString(".gitignore", "custom\n").getOrElse { error("seed failed") }

        doInit(listOf("my-app")).getOrElse { error("doInit failed: exit=$it") }

        val content = readFileAsString(".gitignore").getOrElse { error("read failed") }
        assertEquals("custom\n", content)
    }

    @Test
    fun runsGitInitWhenNoGitDir() {
        doInit(listOf("my-app")).getOrElse { error("doInit failed: exit=$it") }

        assertTrue(fileExists(".git"), ".git/ must exist after kolt init")
    }

    @Test
    fun leavesExistingGitRepoUntouched() {
        ensureDirectoryRecursive(".git").getOrElse { error("seed .git failed") }
        writeFileAsString(".git/marker", "keep me").getOrElse { error("seed marker failed") }

        doInit(listOf("my-app")).getOrElse { error("doInit failed: exit=$it") }

        assertTrue(fileExists(".git/marker"), "existing .git/ must be left alone")
        // Seed was a bare dir, not a real repo — `git init` would populate
        // HEAD / objects / refs. Their absence proves init was skipped.
        assertFalse(fileExists(".git/HEAD"), "git init must not run over existing .git/")
    }

    // Submodule / worktree gitlink — `.git` is a regular file pointing at a
    // real gitdir elsewhere. `access(F_OK)` treats it as existing, so init
    // is skipped. Pin the invariant.
    @Test
    fun leavesGitGitlinkFileUntouched() {
        writeFileAsString(".git", "gitdir: ../other/.git/worktrees/w1\n")
            .getOrElse { error("seed .git file failed") }

        doInit(listOf("my-app")).getOrElse { error("doInit failed: exit=$it") }

        val content = readFileAsString(".git").getOrElse { error("read failed") }
        assertEquals("gitdir: ../other/.git/worktrees/w1\n", content)
    }

    @Test
    fun skipsGitInitInsideExistingWorktree() {
        // Make tmpDir a real repo, then run doInit from a subdirectory.
        executeCommand(listOf("git", "init", "-q")).getOrElse { error("parent git init failed") }
        ensureDirectoryRecursive("sub").getOrElse { error("mkdir sub failed") }
        check(chdir("sub") == 0) { "chdir to sub failed" }

        doInit(listOf("my-app")).getOrElse { error("doInit failed: exit=$it") }

        assertFalse(fileExists(".git"), "nested .git must not be created inside an existing worktree")
    }

    private fun createTempDir(prefix: String): String {
        val template = "/tmp/${prefix}XXXXXX"
        val buf = template.encodeToByteArray().copyOf(template.length + 1)
        buf.usePinned { pinned ->
            val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
            return result.toKString()
        }
    }
}
