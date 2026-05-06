@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.computeSha256
import kolt.infra.eprintln
import kolt.infra.executeCommand
import kolt.infra.fileExists
import kolt.infra.isRegularFile
import kolt.infra.readFileAsString
import kolt.infra.removeDirectoryRecursive
import kolt.infra.sha256Hex
import kolt.infra.writeFileAsString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.PATH_MAX
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.closedir
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.mkdtemp
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.stat

const val FIXTURE_KOTLIN_VERSION = "2.3.20"

class MultiShapeDaemonTestCoverageIT {

  private val createdDirs = mutableListOf<String>()

  @AfterTest
  fun cleanup() {
    for (dir in createdDirs) {
      if (fileExists(dir)) {
        removeDirectoryRecursive(dir)
      }
    }
    createdDirs.clear()
  }

  private fun ensureGateOrSkip(): Boolean {
    val raw = getenv(GATE_ENV)?.toKString()
    if (raw.isNullOrEmpty()) {
      if (!skipNoticePrinted) {
        skipNoticePrinted = true
        eprintln(
          "MultiShapeDaemonTestCoverageIT: skipped (set $GATE_ENV to a daemon thin jar to enable)"
        )
      }
      return false
    }
    if (!isRegularFile(raw)) {
      fail("$GATE_ENV must point to an existing thin jar file: $raw")
    }
    return true
  }

  @Test
  fun runsDaemonRoutedTestOnJvmProjectWithoutPlugins() {
    if (!ensureGateOrSkip()) return
    val fixture = scaffoldNoPluginFixture().also(createdDirs::add)
    val fixtureName = "no-plugin"

    val buildExit = runKoltCommand(fixture, "build", "b")
    val buildStdout = readOptional(fixture, "b.stdout") ?: ""
    val buildStderr = readOptional(fixture, "b.stderr") ?: ""
    assertEquals(
      0,
      buildExit,
      "kolt build must exit 0 for $fixtureName fixture; stdout=$buildStdout stderr=$buildStderr",
    )

    val mainClassesBefore = snapshotMainClassFiles(fixture)
    assertTrue(
      mainClassesBefore.isNotEmpty(),
      "kolt build must produce at least one .class under build/classes/ for $fixtureName " +
        "fixture; stdout=$buildStdout stderr=$buildStderr",
    )

    val testExit = runKoltCommand(fixture, "test", "t")
    val testStdout = readOptional(fixture, "t.stdout") ?: ""
    val testStderr = readOptional(fixture, "t.stderr") ?: ""
    assertEquals(
      0,
      testExit,
      "kolt test must exit 0 for $fixtureName fixture; stdout=$testStdout stderr=$testStderr",
    )

    assertMainClassesSurvive(fixture, fixtureName, mainClassesBefore)
    assertIcSegmentsPopulated(fixture, fixtureName)
    assertShrunkSnapshotsPopulated(fixtureName)
  }

  @Test
  fun runsDaemonRoutedTestOnJvmProjectWithSerializationPlugin() {
    if (!ensureGateOrSkip()) return
    val fixture = scaffoldSerializationFixture().also(createdDirs::add)
    val fixtureName = "serialization"

    val buildExit = runKoltCommand(fixture, "build", "b")
    val buildStdout = readOptional(fixture, "b.stdout") ?: ""
    val buildStderr = readOptional(fixture, "b.stderr") ?: ""
    assertEquals(
      0,
      buildExit,
      "kolt build must exit 0 for $fixtureName fixture; stdout=$buildStdout stderr=$buildStderr",
    )

    val mainClassesBefore = snapshotMainClassFiles(fixture)
    assertTrue(
      mainClassesBefore.isNotEmpty(),
      "kolt build must produce at least one .class under build/classes/ for $fixtureName " +
        "fixture; stdout=$buildStdout stderr=$buildStderr",
    )

    val testExit = runKoltCommand(fixture, "test", "t")
    val testStdout = readOptional(fixture, "t.stdout") ?: ""
    val testStderr = readOptional(fixture, "t.stderr") ?: ""
    assertEquals(
      0,
      testExit,
      "kolt test must exit 0 for $fixtureName fixture; stdout=$testStdout stderr=$testStderr",
    )

    assertMainClassesSurvive(fixture, fixtureName, mainClassesBefore)
    assertIcSegmentsPopulated(fixture, fixtureName)
    assertShrunkSnapshotsPopulated(fixtureName)
  }

  // Daemon restart must not wipe shared shrunk-snapshot or per-jar cache
  // entries for an active project — IcReaper's current-version branch skips
  // the cache subdirs (per IcStateLayout.CACHE_SUBDIRS_AT_VERSION_LEVEL).
  // Inode + mtime + content equality across a restart proves the file was
  // neither deleted-and-recreated nor partially rewritten.
  @Test
  fun cacheSurvivesDaemonRestart() {
    if (!ensureGateOrSkip()) return
    val fixture = scaffoldNoPluginFixture().also(createdDirs::add)
    val fixtureName = "no-plugin-restart"

    val firstBuildExit = runKoltCommand(fixture, "build", "b1")
    assertEquals(
      0,
      firstBuildExit,
      "first kolt build must exit 0 for $fixtureName fixture; " +
        "stdout=${readOptional(fixture, "b1.stdout") ?: ""} " +
        "stderr=${readOptional(fixture, "b1.stderr") ?: ""}",
    )
    val firstTestExit = runKoltCommand(fixture, "test", "t1")
    assertEquals(
      0,
      firstTestExit,
      "first kolt test must exit 0 for $fixtureName fixture; " +
        "stdout=${readOptional(fixture, "t1.stdout") ?: ""} " +
        "stderr=${readOptional(fixture, "t1.stderr") ?: ""}",
    )

    val perJarBefore = pickAnyCacheFile(perJarSnapshotsDir(), ".snapshot")
    assertNotNull(
      perJarBefore,
      "expected at least one *.snapshot in ${perJarSnapshotsDir()} after first build",
    )
    val shrunkBefore = pickAnyCacheFile(shrunkSnapshotsDir(), ".bin")
    assertNotNull(
      shrunkBefore,
      "expected at least one *.bin in ${shrunkSnapshotsDir()} after first build",
    )
    val perJarFingerprintBefore = fingerprint(perJarBefore)
    val shrunkFingerprintBefore = fingerprint(shrunkBefore)

    val stopExit = runKoltCommand(fixture, "daemon stop", "stop")
    assertEquals(
      0,
      stopExit,
      "kolt daemon stop must exit 0 for $fixtureName fixture; " +
        "stdout=${readOptional(fixture, "stop.stdout") ?: ""} " +
        "stderr=${readOptional(fixture, "stop.stderr") ?: ""}",
    )

    val secondBuildExit = runKoltCommand(fixture, "build", "b2")
    assertEquals(
      0,
      secondBuildExit,
      "second kolt build must exit 0 for $fixtureName fixture; " +
        "stdout=${readOptional(fixture, "b2.stdout") ?: ""} " +
        "stderr=${readOptional(fixture, "b2.stderr") ?: ""}",
    )

    assertTrue(
      isRegularFile(perJarBefore),
      "per-jar cache file disappeared across daemon restart: $perJarBefore",
    )
    assertTrue(
      isRegularFile(shrunkBefore),
      "shrunk-snapshot cache file disappeared across daemon restart: $shrunkBefore",
    )
    assertEquals(
      perJarFingerprintBefore,
      fingerprint(perJarBefore),
      "per-jar cache file changed across daemon restart (inode/mtime/sha256): $perJarBefore",
    )
    assertEquals(
      shrunkFingerprintBefore,
      fingerprint(shrunkBefore),
      "shrunk-snapshot cache file changed across daemon restart (inode/mtime/sha256): $shrunkBefore",
    )
  }

  // A cache-hit compile must produce byte-identical .class files vs a
  // cache-miss compile on the same sources — silent corruption guard for
  // the shrunk-snapshot reuse path. Same fixture is used so source paths
  // embedded in debug attributes (SourceFile, LineNumberTable) match
  // between runs. Between runs, the project's per-scope BTA workingDir is
  // wiped (along with build/) but the global shrunk-snapshots cache is
  // preserved, so the second compile is a clean full BTA invocation that
  // hits the global cache via classpath-key lookup.
  @Test
  fun compiledClassesAreByteIdenticalAcrossCacheHitAndMiss() {
    if (!ensureGateOrSkip()) return
    val fixture = scaffoldNoPluginFixture().also(createdDirs::add)
    val fixtureName = "no-plugin-byte-identity"

    wipeShrunkSnapshotsDir()
    wipeProjectIcDir(fixture)

    runFixtureBuildAndTest(fixture, fixtureName, prefix = "miss")
    val missDigests = digestAllClassFiles(fixture)
    assertTrue(
      missDigests.isNotEmpty(),
      "cache-miss build produced no .class files under $fixture/build/classes",
    )

    // Wipe both the local build dir and the daemon's per-project IC state so
    // the next build forces a full BTA compile (not a no-op short-circuit),
    // while the global shrunk-snapshot cache survives to deliver a hit on
    // the classpath-key lookup.
    removeDirectoryRecursive("$fixture/build").getOrElse {
      fail("could not wipe $fixture/build between runs: ${it.path}")
    }
    wipeProjectIcDir(fixture)

    runFixtureBuildAndTest(fixture, fixtureName, prefix = "hit")
    val hitDigests = digestAllClassFiles(fixture)

    assertEquals(
      missDigests,
      hitDigests,
      "compiled .class set diverged between cache-miss and cache-hit runs " +
        "for $fixtureName fixture; cache-hit may be silently corrupting output",
    )
  }

  private fun runFixtureBuildAndTest(fixture: String, fixtureName: String, prefix: String) {
    val buildExit = runKoltCommand(fixture, "build", "$prefix-b")
    assertEquals(
      0,
      buildExit,
      "$prefix kolt build must exit 0 for $fixtureName fixture; " +
        "stdout=${readOptional(fixture, "$prefix-b.stdout") ?: ""} " +
        "stderr=${readOptional(fixture, "$prefix-b.stderr") ?: ""}",
    )
    val testExit = runKoltCommand(fixture, "test", "$prefix-t")
    assertEquals(
      0,
      testExit,
      "$prefix kolt test must exit 0 for $fixtureName fixture; " +
        "stdout=${readOptional(fixture, "$prefix-t.stdout") ?: ""} " +
        "stderr=${readOptional(fixture, "$prefix-t.stderr") ?: ""}",
    )
  }

  companion object {
    // Per-class flag so the skip notice prints exactly once per test JVM
    // lifetime even when multiple `@Test` methods land on an unset gate.
    // Mirrors `JvmTestSysPropIT`'s companion-scoped `skipNoticePrinted`.
    private var skipNoticePrinted = false
  }
}

private const val GATE_ENV = "KOLT_DAEMON_JAR"

private fun locateKoltKexe(): String {
  val cwd =
    currentWorkingDir()
      ?: error("MultiShapeDaemonTestCoverageIT: getcwd() failed; cannot locate kolt.kexe")
  val candidates = listOf("$cwd/build/debug/kolt.kexe", "$cwd/build/release/kolt.kexe")
  return candidates.firstOrNull { fileExists(it) }
    ?: error(
      "MultiShapeDaemonTestCoverageIT: kolt.kexe not built; run `kolt build` first. " +
        "Looked under: $candidates"
    )
}

private fun currentWorkingDir(): String? = memScoped {
  val buf = allocArray<ByteVar>(PATH_MAX)
  getcwd(buf, PATH_MAX.toULong())?.toKString()
}

private fun createTempDir(prefix: String): String {
  val template = "/tmp/${prefix}XXXXXX"
  val buf = template.encodeToByteArray().copyOf(template.length + 1)
  buf.usePinned { pinned ->
    val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed for prefix '$prefix'")
    return result.toKString()
  }
}

private fun scaffoldNoPluginFixture(): String {
  val dir = createTempDir("kolt-it-multishape-noplugin-")
  writeFileAsString("$dir/kolt.toml", NO_PLUGIN_TOML).getOrElse {
    error("write kolt.toml: ${it.path}")
  }
  val mainDir = "$dir/src/main/kotlin"
  val testDir = "$dir/src/test/kotlin"
  executeCommand(listOf("mkdir", "-p", mainDir)).getOrElse { error("mkdir main: $it") }
  executeCommand(listOf("mkdir", "-p", testDir)).getOrElse { error("mkdir test: $it") }
  writeFileAsString("$mainDir/Main.kt", NO_PLUGIN_MAIN).getOrElse {
    error("write Main.kt: ${it.path}")
  }
  writeFileAsString("$testDir/MainTest.kt", NO_PLUGIN_TEST).getOrElse {
    error("write MainTest.kt: ${it.path}")
  }
  return dir
}

private fun scaffoldSerializationFixture(): String {
  val dir = createTempDir("kolt-it-multishape-serialization-")
  writeFileAsString("$dir/kolt.toml", SERIALIZATION_TOML).getOrElse {
    error("write kolt.toml: ${it.path}")
  }
  val mainDir = "$dir/src/main/kotlin"
  val testDir = "$dir/src/test/kotlin"
  executeCommand(listOf("mkdir", "-p", mainDir)).getOrElse { error("mkdir main: $it") }
  executeCommand(listOf("mkdir", "-p", testDir)).getOrElse { error("mkdir test: $it") }
  writeFileAsString("$mainDir/Payload.kt", SERIALIZATION_MAIN).getOrElse {
    error("write Payload.kt: ${it.path}")
  }
  writeFileAsString("$testDir/PayloadTest.kt", SERIALIZATION_TEST).getOrElse {
    error("write PayloadTest.kt: ${it.path}")
  }
  return dir
}

// Bash harness mirrors JvmTestSysPropIT: capture exit code via `echo $? >` so
// `executeCommand`'s Result wrapper does not collapse non-zero kolt exits onto
// the bash exit channel before we can read stdout/stderr.
private fun runKoltCommand(fixtureDir: String, koltSubcommand: String, fileStem: String): Int {
  val kolt = locateKoltKexe()
  val daemonJar =
    getenv(GATE_ENV)?.toKString()
      ?: error("$GATE_ENV must be set when runKoltCommand runs (gate already passed)")
  val script =
    """
        set -u
        cd "$fixtureDir"
        "$kolt" $koltSubcommand > $fileStem.stdout 2> $fileStem.stderr
        echo $D? > $fileStem.exit
        """
      .trimIndent()
  executeCommand(listOf("bash", "-c", script), extraEnv = mapOf(GATE_ENV to daemonJar)).getOrElse {
    error("harness bash failed for `kolt $koltSubcommand` in $fixtureDir: $it")
  }
  val raw =
    readFileAsString("$fixtureDir/$fileStem.exit").getOrElse {
      error("missing $fixtureDir/$fileStem.exit — harness did not record an exit code")
    }
  return raw.trim().toIntOrNull()
    ?: error("could not parse exit code from $fixtureDir/$fileStem.exit: '$raw'")
}

private fun readOptional(dir: String, name: String): String? {
  val path = "$dir/$name"
  if (!fileExists(path)) return null
  return readFileAsString(path).getOrElse {
    return null
  }
}

private fun snapshotMainClassFiles(fixtureDir: String): Set<String> {
  val classesRoot = "$fixtureDir/build/classes"
  if (!fileExists(classesRoot)) return emptySet()
  val collected = mutableSetOf<String>()
  collectClassFiles(classesRoot, collected)
  return collected
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun collectClassFiles(root: String, into: MutableSet<String>) {
  val dir = opendir(root) ?: return
  try {
    while (true) {
      val entry = readdir(dir) ?: break
      val name = entry.pointed.d_name.toKString()
      if (name == "." || name == "..") continue
      val child = "$root/$name"
      if (isDir(child)) {
        collectClassFiles(child, into)
      } else if (name.endsWith(".class")) {
        into.add(child)
      }
    }
  } finally {
    closedir(dir)
  }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun isDir(path: String): Boolean = memScoped {
  val statBuf = alloc<stat>()
  if (platform.posix.lstat(path, statBuf.ptr) != 0) return false
  (statBuf.st_mode.toInt() and S_IFMT) == S_IFDIR
}

private fun assertMainClassesSurvive(fixtureDir: String, fixtureName: String, before: Set<String>) {
  for (path in before) {
    assertTrue(
      isRegularFile(path),
      "main .class artifact was deleted during kolt test for fixture $fixtureName " +
        "(under $fixtureDir): $path",
    )
  }
}

// Daemon IC layout (kolt.build.daemon.IcStateCleanup §`daemonIcProjectIdOf`):
// `<icRoot>/<kotlinVersion>/<sha256Hex(absProjectPath).take(32)>/<scope>/bta/`.
// The production helper has `internal` visibility, so reimplement locally with
// the same algorithm. A drift in either side breaks this assertion loud.
private fun expectedProjectId(absProjectPath: String): String =
  sha256Hex(absProjectPath.encodeToByteArray()).take(32)

private fun assertIcSegmentsPopulated(fixtureDir: String, fixtureName: String) {
  val home =
    getenv("HOME")?.toKString()
      ?: fail("HOME env var not set; cannot resolve daemon IC root for fixture $fixtureName")
  val icRoot = "$home/.kolt/daemon/ic"
  val projectId = expectedProjectId(fixtureDir)
  val versionDir = "$icRoot/$FIXTURE_KOTLIN_VERSION/$projectId"
  val mainBta = "$versionDir/main/bta"
  val testBta = "$versionDir/test/bta"

  assertTrue(
    fileExists(mainBta),
    "missing IC segment 'main/bta' for fixture $fixtureName at $mainBta",
  )
  assertTrue(
    isDir(mainBta) && hasAtLeastOneEntry(mainBta),
    "IC segment 'main/bta' is empty for fixture $fixtureName at $mainBta",
  )
  assertTrue(
    fileExists(testBta),
    "missing IC segment 'test/bta' for fixture $fixtureName at $testBta",
  )
  assertTrue(
    isDir(testBta) && hasAtLeastOneEntry(testBta),
    "IC segment 'test/bta' is empty for fixture $fixtureName at $testBta",
  )

  // Req 2.3: main/ and test/ must be siblings under the same <projectId>.
  assertEquals(
    versionDir,
    parentOf(parentOf(mainBta)),
    "main/bta parent chain must climb to <projectId> dir for fixture $fixtureName",
  )
  assertEquals(
    versionDir,
    parentOf(parentOf(testBta)),
    "test/bta parent chain must climb to <projectId> dir for fixture $fixtureName",
  )
}

private fun shrunkSnapshotsDir(): String {
  val home =
    getenv("HOME")?.toKString() ?: error("HOME env var not set; cannot resolve daemon IC root")
  return "$home/.kolt/daemon/ic/$FIXTURE_KOTLIN_VERSION/shrunk-snapshots"
}

private fun perJarSnapshotsDir(): String {
  val home =
    getenv("HOME")?.toKString() ?: error("HOME env var not set; cannot resolve daemon IC root")
  return "$home/.kolt/daemon/ic/$FIXTURE_KOTLIN_VERSION/classpath-snapshots"
}

private fun assertShrunkSnapshotsPopulated(fixtureName: String) {
  val dir = shrunkSnapshotsDir()
  assertTrue(
    fileExists(dir) && isDir(dir),
    "shrunk-snapshots dir missing for fixture $fixtureName at $dir",
  )
  assertTrue(
    listFilesWithSuffix(dir, ".bin").isNotEmpty(),
    "shrunk-snapshots dir contains no *.bin files for fixture $fixtureName at $dir",
  )
}

private fun wipeShrunkSnapshotsDir() {
  val dir = shrunkSnapshotsDir()
  if (fileExists(dir)) {
    removeDirectoryRecursive(dir).getOrElse {
      error("could not wipe shrunk-snapshots dir: ${it.path}")
    }
  }
}

private fun wipeProjectIcDir(fixtureDir: String) {
  val home =
    getenv("HOME")?.toKString() ?: error("HOME env var not set; cannot resolve daemon IC root")
  val projectIcDir =
    "$home/.kolt/daemon/ic/$FIXTURE_KOTLIN_VERSION/${expectedProjectId(fixtureDir)}"
  if (fileExists(projectIcDir)) {
    removeDirectoryRecursive(projectIcDir).getOrElse {
      error("could not wipe project IC dir $projectIcDir: ${it.path}")
    }
  }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun listFilesWithSuffix(directory: String, suffix: String): List<String> {
  if (!fileExists(directory)) return emptyList()
  val dir = opendir(directory) ?: return emptyList()
  val out = mutableListOf<String>()
  try {
    while (true) {
      val entry = readdir(dir) ?: break
      val name = entry.pointed.d_name.toKString()
      if (name == "." || name == "..") continue
      if (!name.endsWith(suffix)) continue
      val path = "$directory/$name"
      if (isRegularFile(path)) out.add(path)
    }
  } finally {
    closedir(dir)
  }
  out.sort()
  return out
}

private fun pickAnyCacheFile(directory: String, suffix: String): String? =
  listFilesWithSuffix(directory, suffix).firstOrNull()

// inode + mtime (sec) + sha256 captures every meaningful change a daemon
// restart could introduce: deletion-and-recreation flips inode, in-place
// rewrite at the same second flips sha256, mtime catches the gap between.
private data class FileFingerprint(val inode: ULong, val mtimeSec: Long, val sha256: String)

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun fingerprint(path: String): FileFingerprint = memScoped {
  val statBuf = alloc<stat>()
  if (stat(path, statBuf.ptr) != 0) {
    fail("stat($path) failed while computing fingerprint")
  }
  val ino = statBuf.st_ino.toULong()
  val mtime = statBuf.st_mtim.tv_sec
  val digest = computeSha256(path).getOrElse { fail("sha256 read failed for $path: ${it.path}") }
  FileFingerprint(ino, mtime, digest)
}

private fun digestAllClassFiles(fixtureDir: String): Map<String, String> {
  val classesRoot = "$fixtureDir/build/classes"
  val absPaths = mutableSetOf<String>()
  if (fileExists(classesRoot)) collectClassFiles(classesRoot, absPaths)
  val rootPrefix = "$classesRoot/"
  val out = mutableMapOf<String, String>()
  for (abs in absPaths.sorted()) {
    val rel = if (abs.startsWith(rootPrefix)) abs.substring(rootPrefix.length) else abs
    out[rel] = computeSha256(abs).getOrElse { fail("sha256 read failed for $abs: ${it.path}") }
  }
  return out
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun hasAtLeastOneEntry(directory: String): Boolean {
  val dir = opendir(directory) ?: return false
  try {
    while (true) {
      val entry = readdir(dir) ?: return false
      val name = entry.pointed.d_name.toKString()
      if (name != "." && name != "..") return true
    }
  } finally {
    closedir(dir)
  }
}

private fun parentOf(path: String): String {
  val idx = path.lastIndexOf('/')
  if (idx <= 0) return "/"
  return path.substring(0, idx)
}

// Single literal "$" token for use inside the bash heredoc raw strings.
// Kotlin would otherwise interpret `$?` as a template lookup.
private const val D = "$"

private val NO_PLUGIN_TOML =
  """
        name = "multishape-noplugin"
        version = "0.0.1"
        kind = "lib"

        [kotlin]
        version = "$FIXTURE_KOTLIN_VERSION"

        [build]
        target = "jvm"
        jvm_target = "21"
        sources = ["src/main/kotlin"]
        test_sources = ["src/test/kotlin"]
        """
    .trimIndent()

private val NO_PLUGIN_MAIN =
  """
        package multishape.noplugin

        fun greet(): String = "hello-no-plugin"
        """
    .trimIndent()

private val NO_PLUGIN_TEST =
  """
        package multishape.noplugin

        import kotlin.test.Test
        import kotlin.test.assertEquals

        class MainTest {
            @Test
            fun greetReturnsExpectedValue() {
                assertEquals("hello-no-plugin", greet())
            }
        }
        """
    .trimIndent()

private val SERIALIZATION_TOML =
  """
        name = "multishape-serialization"
        version = "0.0.1"
        kind = "lib"

        [kotlin]
        version = "$FIXTURE_KOTLIN_VERSION"

        [kotlin.plugins]
        serialization = true

        [build]
        target = "jvm"
        jvm_target = "21"
        sources = ["src/main/kotlin"]
        test_sources = ["src/test/kotlin"]

        [dependencies]
        "org.jetbrains.kotlinx:kotlinx-serialization-json" = "1.7.3"
        """
    .trimIndent()

private val SERIALIZATION_MAIN =
  """
        package multishape.serialization

        import kotlinx.serialization.Serializable

        @Serializable
        data class Payload(val id: Int, val name: String)
        """
    .trimIndent()

private val SERIALIZATION_TEST =
  """
        package multishape.serialization

        import kotlin.test.Test
        import kotlin.test.assertEquals
        import kotlinx.serialization.encodeToString
        import kotlinx.serialization.json.Json

        class PayloadTest {
            @Test
            fun roundTripsThroughJson() {
                val original = Payload(id = 42, name = "answer")
                val encoded = Json.encodeToString(original)
                val decoded = Json.decodeFromString<Payload>(encoded)
                assertEquals(original, decoded)
            }
        }
        """
    .trimIndent()
