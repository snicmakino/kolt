package kolt.cli

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import kolt.build.BUILD_DIR
import kolt.build.outputRuntimeClasspathPath
import kolt.build.runCommand
import kolt.infra.deleteFile
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.fileExists
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kolt.testConfig
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
import platform.posix.mkdir
import platform.posix.mkdtemp

/**
 * Req 2.8 / ADR 0027 §5 regression: `kolt run` (JVM app path) must assemble the runtime classpath
 * in-process from the resolver's return value and must not read `build/<name>-runtime.classpath`.
 * The manifest is the hand-off to external launchers (assemble-dist.sh); the in-process consumer
 * already has the resolver's typed output.
 *
 * The observable pin: the pure command builder `runCommand` — the single hop between `doRun` and
 * the `java` subprocess — returns the same argv whether the manifest is absent, present with stale
 * content, or present with mismatching content. Any regression that starts opening the manifest on
 * the run path would change the output here.
 *
 * Exercising `runCommand` directly (instead of end-to-end `doRun`) keeps the test hermetic: no JDK,
 * no fork, no subprocess. The coverage is equivalent because `doRun`'s JVM arm hands its
 * `classpath` parameter straight into `runCommand` (BuildCommands.kt:532) — there is no other point
 * on the run path where a manifest read could be hiding.
 */
@OptIn(ExperimentalForeignApi::class)
class KoltRunManifestIndependenceTest {

  private var originalCwd: String = ""
  private var tmpDir: String = ""

  @BeforeTest
  fun setUp() {
    originalCwd = memScoped {
      val buf = allocArray<ByteVar>(PATH_MAX)
      getcwd(buf, PATH_MAX.toULong())?.toKString() ?: error("getcwd failed")
    }
    tmpDir = createTempDir("kolt-run-manifest-indep-")
    check(chdir(tmpDir) == 0) { "chdir to $tmpDir failed" }
    check(mkdir(BUILD_DIR, 0b111111101u) == 0) { "mkdir $BUILD_DIR failed" }
  }

  @AfterTest
  fun tearDown() {
    chdir(originalCwd)
    if (tmpDir.isNotEmpty() && fileExists(tmpDir)) {
      removeDirectoryRecursive(tmpDir)
    }
  }

  // Req 2.8 / ADR 0027 §5: with the manifest absent, `runCommand` still
  // assembles a well-formed `-cp` arg from its in-process `classpath`
  // parameter. The manifest path is not referenced, not opened, and not
  // materialised by the call.
  @Test
  fun runCommandSucceedsWhenManifestIsAbsent() {
    val config = testConfig(name = "myapp", target = "jvm").copy(kind = "app")
    val manifestPath = outputRuntimeClasspathPath(config)
    assertFalse(
      fileExists(manifestPath),
      "precondition: no manifest should exist before runCommand is called",
    )
    val resolverClasspath = "/cache/org.jetbrains/kotlin-stdlib/2.3.20/kotlin-stdlib-2.3.20.jar"

    val cmd = runCommand(config, main = "com.example.main", classpath = resolverClasspath)

    assertEquals(
      listOf("java", "-cp", "build/classes:$resolverClasspath", "com.example.MainKt"),
      cmd.args,
      "runCommand must build -cp from the in-process classpath parameter, not the manifest file",
    )
    assertFalse(
      cmd.args.any { it.contains(manifestPath) },
      "runCommand argv must not reference the manifest path",
    )
    assertFalse(
      fileExists(manifestPath),
      "runCommand must not create or materialise the manifest file",
    )
  }

  // Req 2.8: `kolt build` emits the manifest, `kolt run` never reads it.
  // Deleting the manifest after `kolt build` leaves `runCommand` unchanged —
  // the run path depends only on the in-process `classpath` string.
  @Test
  fun runCommandIsUnaffectedByDeletingThePostBuildManifest() {
    val config = testConfig(name = "myapp", target = "jvm").copy(kind = "app")
    val manifestPath = outputRuntimeClasspathPath(config)
    val resolverClasspath =
      "/cache/org.jetbrains/kotlin-stdlib/2.3.20/kotlin-stdlib-2.3.20.jar:" +
        "/cache/com.akuleshov7/ktoml-core-jvm/0.7.1/ktoml-core-jvm-0.7.1.jar"

    // Simulate the post-`kolt build` state, then delete the manifest as
    // step (a) of the Req 2.8 procedure ("after `kolt build` remove
    // `build/<name>-runtime.classpath`"). `kolt run` must still succeed.
    ensureDirectoryRecursive(manifestPath.substringBeforeLast('/')).getOrElse {
      error("ensure failed: $it")
    }
    writeFileAsString(manifestPath, "/cache/stale-but-never-read.jar").getError()?.let {
      error("seed failed: $it")
    }
    assertTrue(fileExists(manifestPath), "precondition: seeded manifest must exist")

    val withManifest = runCommand(config, main = "com.example.main", classpath = resolverClasspath)

    deleteFile(manifestPath)
    assertFalse(fileExists(manifestPath), "precondition: manifest must be gone after delete")

    val withoutManifest =
      runCommand(config, main = "com.example.main", classpath = resolverClasspath)

    assertEquals(
      withManifest.args,
      withoutManifest.args,
      "runCommand output must be byte-for-byte identical regardless of manifest presence",
    )
    assertEquals(
      listOf("java", "-cp", "build/classes:$resolverClasspath", "com.example.MainKt"),
      withoutManifest.args,
      "runCommand must assemble -cp from the in-process classpath, not from the (absent) manifest",
    )
  }

  // Req 2.8 / ADR 0027 §5 defence-in-depth: if the manifest contains
  // paths that disagree with the resolver's in-process output, `kolt run`
  // uses the resolver value. A regression that started reading the
  // manifest would surface here as the resolver string being replaced
  // by the manifest's (wrong) contents.
  @Test
  fun runCommandIgnoresStaleManifestContentOnDisk() {
    val config = testConfig(name = "myapp", target = "jvm").copy(kind = "app")
    val manifestPath = outputRuntimeClasspathPath(config)
    // Seed a manifest that disagrees with what the resolver returns in
    // memory. If `runCommand` (or anything on the run path) opened the
    // manifest, the `-cp` arg would include these stale entries.
    val staleContent = "/cache/STALE/wrong-1.jar\n" + "/cache/STALE/wrong-2.jar"
    ensureDirectoryRecursive(manifestPath.substringBeforeLast('/')).getOrElse {
      error("ensure failed: $it")
    }
    writeFileAsString(manifestPath, staleContent).getError()?.let { error("seed failed: $it") }
    val resolverClasspath = "/cache/org.jetbrains/kotlin-stdlib/2.3.20/kotlin-stdlib-2.3.20.jar"

    val cmd = runCommand(config, main = "com.example.main", classpath = resolverClasspath)

    assertEquals(
      listOf("java", "-cp", "build/classes:$resolverClasspath", "com.example.MainKt"),
      cmd.args,
      "runCommand must use the resolver's in-process classpath, ignoring stale manifest content",
    )
    assertFalse(
      cmd.args.any { it.contains("STALE") },
      "runCommand argv must not carry any entry from the stale on-disk manifest",
    )
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
