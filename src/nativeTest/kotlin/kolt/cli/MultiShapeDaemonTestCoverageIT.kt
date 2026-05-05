@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package kolt.cli

import com.github.michaelbull.result.getOrElse
import kolt.infra.eprintln
import kolt.infra.executeCommand
import kolt.infra.fileExists
import kolt.infra.isRegularFile
import kolt.infra.readFileAsString
import kolt.infra.sha256Hex
import kolt.infra.writeFileAsString
import kotlin.test.Test
import kotlin.test.assertEquals
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

  @Test
  fun runsDaemonRoutedTestOnJvmProjectWithoutPlugins() {
    if (!ensureGateOrSkip()) return
    val fixture = scaffoldNoPluginFixture()
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
  }
}

private const val GATE_ENV = "KOLT_DAEMON_JAR"

// Mirrors JvmTestSysPropIT's `printOnceSkipNotice` pattern: keep a file-local
// flag so the skip notice prints exactly once per test JVM lifetime, even when
// multiple `@Test` methods in this class land on an unset gate.
private var skipNoticePrinted = false

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
  if (!fileExists(raw)) {
    fail("$GATE_ENV points to non-existent path: $raw")
  }
  return true
}

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

private fun assertMainClassesSurvive(
  @Suppress("UNUSED_PARAMETER") fixtureDir: String,
  fixtureName: String,
  before: Set<String>,
) {
  for (path in before) {
    assertTrue(
      isRegularFile(path),
      "main .class artifact was deleted during kolt test for fixture $fixtureName: $path",
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
