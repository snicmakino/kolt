package kolt.cli

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.executeCommand
import kolt.infra.fileExists
import kolt.infra.readFileAsString
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.PATH_MAX
import platform.posix.chdir
import platform.posix.getcwd
import platform.posix.mkdtemp
import platform.posix.setenv
import platform.posix.unsetenv

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
    writeFileAsString(".git", "gitdir: ../other/.git/worktrees/w1\n").getOrElse {
      error("seed .git file failed")
    }

    doInit(listOf("my-app")).getOrElse { error("doInit failed: exit=$it") }

    val content = readFileAsString(".git").getOrElse { error("read failed") }
    assertEquals("gitdir: ../other/.git/worktrees/w1\n", content)
  }

  @Test
  fun libFlagWritesLibKtNotMainKt() {
    doInit(listOf("mylib", "--lib")).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(fileExists("src/Lib.kt"), "src/Lib.kt must exist for --lib")
    assertFalse(fileExists("src/Main.kt"), "src/Main.kt must not exist for --lib")
  }

  @Test
  fun libFlagWritesLibTestNotMainTest() {
    doInit(listOf("mylib", "--lib")).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(fileExists("test/LibTest.kt"), "test/LibTest.kt must exist for --lib")
    assertFalse(fileExists("test/MainTest.kt"), "test/MainTest.kt must not exist for --lib")
  }

  @Test
  fun libFlagOmitsMainFromKoltToml() {
    doInit(listOf("mylib", "--lib")).getOrElse { error("doInit failed: exit=$it") }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("kind = \"lib\""), "kolt.toml must declare kind = \"lib\"")
    assertFalse(
      toml.lineSequence().any { it.trimStart().startsWith("main") },
      "kolt.toml must not declare main for --lib",
    )
  }

  @Test
  fun appFlagAcceptedExplicitly() {
    doInit(listOf("myapp", "--app")).getOrElse { error("doInit failed: exit=$it") }

    assertTrue(fileExists("src/Main.kt"))
    assertTrue(fileExists("test/MainTest.kt"))
  }

  @Test
  fun unknownFlagFails() {
    val exit = doInit(listOf("myapp", "--bogus")).getError()
    assertEquals(EXIT_CONFIG_ERROR, exit)
  }

  @Test
  fun mutuallyExclusiveKindFlagsFail() {
    val exit = doInit(listOf("myapp", "--lib", "--app")).getError()
    assertEquals(EXIT_CONFIG_ERROR, exit)
  }

  @Test
  fun rejectsWhenKoltTomlAlreadyExists() {
    writeFileAsString("kolt.toml", "name = \"seed\"\n").getOrElse { error("seed failed") }

    val exit = doInit(listOf("my-app")).getError()

    assertEquals(EXIT_CONFIG_ERROR, exit)
    val content = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertEquals("name = \"seed\"\n", content, "existing kolt.toml must not be overwritten")
  }

  @Test
  fun infersProjectNameFromCwdWhenArgsEmpty() {
    doInit(emptyList()).getOrElse { error("doInit failed: exit=$it") }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    val expectedName = tmpDir.substringAfterLast('/')
    assertTrue(
      toml.contains("name = \"$expectedName\""),
      "expected name = \"$expectedName\" in toml: $toml",
    )
  }

  @Test
  fun targetFlagWritesNativeTargetIntoToml() {
    doInit(listOf("myapp", "--target", "linuxX64")).getOrElse { error("doInit failed: exit=$it") }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"linuxX64\""))
    assertFalse(
      toml.lineSequence().any { it.trimStart().startsWith("jvm_target") },
      "jvm_target must be omitted for non-jvm targets",
    )
  }

  @Test
  fun targetFlagEqualsFormAccepted() {
    doInit(listOf("myapp", "--target=linuxX64")).getOrElse { error("doInit failed: exit=$it") }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"linuxX64\""))
  }

  @Test
  fun targetFlagWithoutValueFails() {
    val exit = doInit(listOf("myapp", "--target")).getError()
    assertEquals(EXIT_CONFIG_ERROR, exit)
  }

  @Test
  fun targetFlagWithInvalidValueFails() {
    val exit = doInit(listOf("myapp", "--target", "wasm")).getError()
    assertEquals(EXIT_CONFIG_ERROR, exit)
  }

  @Test
  fun targetFlagDuplicateConflictingValueFails() {
    val exit = doInit(listOf("myapp", "--target", "jvm", "--target", "linuxX64")).getError()
    assertEquals(EXIT_CONFIG_ERROR, exit)
  }

  @Test
  fun targetFlagDuplicateSameValueAccepted() {
    doInit(listOf("myapp", "--target", "jvm", "--target", "jvm")).getOrElse {
      error("doInit failed: exit=$it")
    }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"jvm\""))
  }

  @Test
  fun targetFlagEqualsFormJvmHappyPath() {
    doInit(listOf("myapp", "--target=jvm")).getOrElse { error("doInit failed: exit=$it") }

    val toml = readFileAsString("kolt.toml").getOrElse { error("read failed") }
    assertTrue(toml.contains("target = \"jvm\""))
    assertTrue(toml.contains("jvm_target = "))
  }

  @Test
  fun targetFlagEqualsFormEmptyValueFails() {
    val exit = doInit(listOf("myapp", "--target=")).getError()
    assertEquals(EXIT_CONFIG_ERROR, exit)
  }

  @Test
  fun targetFlagFollowedByAnotherFlagFails() {
    val exit = doInit(listOf("myapp", "--target", "--lib")).getError()
    assertEquals(EXIT_CONFIG_ERROR, exit)
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
