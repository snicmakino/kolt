package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import kolt.config.KoltPaths
import kolt.infra.CopyFailed
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.ProcessError
import kolt.infra.Sha256Error
import kolt.infra.computeSha256
import kolt.infra.ensureDirectoryRecursive
import kolt.infra.removeDirectoryRecursive
import kolt.resolve.Coordinate
import kolt.resolve.LockEntry
import kolt.resolve.Lockfile
import kolt.resolve.ResolverDeps
import kolt.usertool.ToolEntry
import kolt.usertool.ToolFsDeps
import kolt.usertool.ensureTool
import kolt.usertool.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import libarchive.ARCHIVE_OK
import libarchive.archive_entry_free
import libarchive.archive_entry_new
import libarchive.archive_entry_set_filetype
import libarchive.archive_entry_set_pathname
import libarchive.archive_entry_set_size
import libarchive.archive_write_close
import libarchive.archive_write_data
import libarchive.archive_write_free
import libarchive.archive_write_header
import libarchive.archive_write_new
import libarchive.archive_write_open_filename
import libarchive.archive_write_set_format_zip
import platform.posix.S_IFREG
import platform.posix.mkdtemp

/**
 * End-to-end test that wires `parseToolArgs` -> `lookupToolEntry` -> `ensureTool` -> `launch`
 * together against a real fixture jar (built in-process via libarchive) and a stubbed
 * `resolveJavaBin` / `exec` so the launch mechanics from `ToolLauncherTest` connect to the dispatch
 * mechanics from `ToolCommandsTest`.
 *
 * Design choice (per task 7.1 implementation note): the launch step is stubbed via the existing
 * [launch] injection points rather than spawning `kolt.kexe`. Spawning a subprocess from a unit
 * test is heavyweight on WSL2 9p and the design intent ("argv passthrough + exit code propagation")
 * is fully covered without real fork/exec. The fixture jar is a real libarchive-built zip, so the
 * MANIFEST.MF read path inside `launch` is exercised exactly as in production.
 */
class ToolCommandsE2ETest {

  @Test
  fun toolRunDispatchPassesArgsVerbatimAndPropagatesExitCode() {
    val tempDir = createTempDir("kolt_tool_e2e_")
    try {
      // Build a runnable-looking jar fixture: a zip with META-INF/MANIFEST.MF declaring a
      // Main-Class.
      // The jar is not actually executed (exec is stubbed) so the Main-Class string only needs to
      // satisfy the launcher's MANIFEST validation.
      val coords = Coordinate("com.example", "argv-echo", "1.0.0")
      val paths = KoltPaths(home = tempDir)
      val toolsJarPath = paths.toolsBundleJarPath("echo", "1.0.0", "argv-echo-1.0.0.jar")
      ensureDirectoryRecursive(toolsJarPath.substringBeforeLast('/'))
      writeRunnableJar(toolsJarPath, mainClass = "fixture.ArgvEcho")

      // Parse the user-facing argv: `kolt tool run echo arg1 --foo bar`.
      val invocation =
        assertNotNull(parseToolArgs(listOf("run", "echo", "arg1", "--foo", "bar")).get())
      assertEquals(
        ToolInvocation.Run(alias = "echo", args = listOf("arg1", "--foo", "bar")),
        invocation,
      )
      val run = invocation as ToolInvocation.Run

      // Lockfile carries a matching pin so ensureTool short-circuits to the cache hit path (no
      // resolver / network involvement). Computing the actual sha256 of the fixture means the cache
      // hit branch validates against a real digest.
      val sha = assertNotNull(computeSha256(toolsJarPath).get())
      val lockfile =
        Lockfile(
          version = 4,
          kotlin = "2.1.0",
          jvmTarget = "17",
          dependencies = emptyMap(),
          classpathBundles = emptyMap(),
          toolsBundles =
            mapOf(
              "echo" to mapOf("com.example:argv-echo" to LockEntry(version = "1.0.0", sha256 = sha))
            ),
        )

      // Real cache-hit path through ensureTool. Exercises lockfile pin matching + sha verify on the
      // cached jar; deps below provide just enough surface to satisfy ResolverDeps + ToolFsDeps.
      val deps = E2EDeps(toolsJarPath, sha)
      val handle =
        assertNotNull(
          ensureTool(
              alias = "echo",
              entry = ToolEntry(coords = coords, classifier = null),
              paths = paths,
              lockfile = lockfile,
              netDeps = deps,
              repos = listOf("https://central/"),
            )
            .get()
        )
      assertEquals(toolsJarPath, handle.jarPath)
      assertEquals(false, handle.lockfileChanged, "warm cache must not require lockfile rewrite")
      assertTrue(deps.downloads.isEmpty(), "cache hit must skip network: ${deps.downloads}")

      // Launch with stubbed exec: capture the command line argv that would have been forked. The
      // launcher injects `[javaBin, "-jar", jarPath, *args]`, which is exactly what proves verbatim
      // argv passthrough end-to-end.
      val capturedArgv = mutableListOf<List<String>>()
      val toolExitCode = 42
      val exit =
        launch(
            alias = run.alias,
            jarHandle = handle,
            args = run.args,
            paths = paths,
            env = emptyMap(),
            resolveJavaBin = { Ok("/fake/jdk/bin/java") },
            exec = { cmd, _ ->
              capturedArgv.add(cmd)
              // Simulate the tool exiting with a non-zero status so we can assert kolt
              // propagates the value verbatim instead of overlaying.
              Err(ProcessError.NonZeroExit(toolExitCode))
            },
          )
          .get()

      // 1) Argv reaching the tool is verbatim. The leading three tokens are the JDK trampoline; the
      //    tail is the user-visible argv. R2.1.
      assertEquals(
        listOf(listOf("/fake/jdk/bin/java", "-jar", toolsJarPath, "arg1", "--foo", "bar")),
        capturedArgv,
      )
      // 2) The tool's exit code propagates as Ok, not Err. R2.2.
      assertEquals(toolExitCode, exit)
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun toolRunDispatchPropagatesZeroExitForSuccessfulTool() {
    val tempDir = createTempDir("kolt_tool_e2e_zero_")
    try {
      val paths = KoltPaths(home = tempDir)
      val toolsJarPath = paths.toolsBundleJarPath("ok", "0.1.0", "ok-0.1.0.jar")
      ensureDirectoryRecursive(toolsJarPath.substringBeforeLast('/'))
      writeRunnableJar(toolsJarPath, mainClass = "fixture.Ok")

      val sha = assertNotNull(computeSha256(toolsJarPath).get())
      val lockfile =
        Lockfile(
          version = 4,
          kotlin = "2.1.0",
          jvmTarget = "17",
          dependencies = emptyMap(),
          classpathBundles = emptyMap(),
          toolsBundles =
            mapOf("ok" to mapOf("com.example:ok" to LockEntry(version = "0.1.0", sha256 = sha))),
        )
      val deps = E2EDeps(toolsJarPath, sha)
      val handle =
        assertNotNull(
          ensureTool(
              alias = "ok",
              entry = ToolEntry(Coordinate("com.example", "ok", "0.1.0"), classifier = null),
              paths = paths,
              lockfile = lockfile,
              netDeps = deps,
              repos = listOf("https://central/"),
            )
            .get()
        )

      val exit =
        launch(
            alias = "ok",
            jarHandle = handle,
            args = emptyList(),
            paths = paths,
            env = emptyMap(),
            resolveJavaBin = { Ok("/fake/jdk/bin/java") },
            exec = { _, _ -> Ok(0) },
          )
          .get()
      assertEquals(0, exit)
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  // ----- Fixture / IO helpers (libarchive only) -----

  @OptIn(ExperimentalForeignApi::class)
  private fun writeRunnableJar(path: String, mainClass: String) {
    val writer = archive_write_new() ?: error("archive_write_new returned null")
    try {
      check(archive_write_set_format_zip(writer) == ARCHIVE_OK) { "set_format_zip failed" }
      check(archive_write_open_filename(writer, path) == ARCHIVE_OK) { "open_filename failed" }
      val manifest = "Manifest-Version: 1.0\nMain-Class: $mainClass\n"
      writeEntry(writer, "META-INF/MANIFEST.MF", manifest)
      // A non-class payload distinguishes this from a manifest-only zip — the launcher does not
      // care, but a real runnable jar would carry classes; we keep the fixture closer to reality.
      writeEntry(writer, "fixture/ArgvEcho.class", "stub-classfile-bytes")
    } finally {
      archive_write_close(writer)
      archive_write_free(writer)
    }
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun writeEntry(writer: CPointer<cnames.structs.archive>, name: String, content: String) {
    val entry = archive_entry_new() ?: error("archive_entry_new returned null")
    try {
      archive_entry_set_pathname(entry, name)
      val bytes = content.encodeToByteArray()
      archive_entry_set_size(entry, bytes.size.toLong())
      archive_entry_set_filetype(entry, S_IFREG.convert())
      check(archive_write_header(writer, entry) == ARCHIVE_OK) { "write_header failed" }
      if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned ->
          val n = archive_write_data(writer, pinned.addressOf(0), bytes.size.convert())
          check(n == bytes.size.toLong()) { "short write: $n / ${bytes.size}" }
        }
      }
    } finally {
      archive_entry_free(entry)
    }
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun createTempDir(prefix: String): String {
    val template = "/tmp/${prefix}XXXXXX"
    val buf = template.encodeToByteArray().copyOf(template.length + 1)
    buf.usePinned { pinned ->
      val result = mkdtemp(pinned.addressOf(0)) ?: error("mkdtemp failed")
      return result.toKString()
    }
  }

  /**
   * Minimal `ResolverDeps + ToolFsDeps` impl that promises a single cached file with a known
   * sha256. The cache-hit path of `ensureTool` calls `fileExists` then `computeSha256` on the
   * tools-bundle jar path, so this is the entire surface we need to mock.
   */
  private class E2EDeps(private val jarPath: String, private val sha: String) :
    ResolverDeps, ToolFsDeps {

    val downloads = mutableListOf<String>()

    override fun fileExists(path: String): Boolean = path == jarPath

    override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> =
      kolt.infra.ensureDirectoryRecursive(path)

    override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
      downloads.add(url)
      return Ok(Unit)
    }

    override fun computeSha256(filePath: String): Result<String, Sha256Error> =
      if (filePath == jarPath) Ok(sha) else kolt.infra.computeSha256(filePath)

    override fun readFileContent(path: String): Result<String, OpenFailed> =
      kolt.infra.readFileAsString(path)

    override fun copyFile(src: String, dest: String): Result<Unit, CopyFailed> =
      kolt.infra.copyFile(src, dest)
  }
}
