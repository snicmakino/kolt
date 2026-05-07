package kolt.usertool

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.config.KoltPaths
import kolt.infra.ProcessError
import kolt.infra.removeDirectoryRecursive
import kolt.infra.writeFileAsString
import kolt.resolve.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

class ToolLauncherTest {

  // ----- readMainClassFromJar -----

  @Test
  fun readsMainClassFromValidJar() {
    val tempDir = createTempDir("kolt_jarread_ok_")
    try {
      val jarPath = "$tempDir/app.jar"
      writeZip(jarPath, mapOf("META-INF/MANIFEST.MF" to "Main-Class: com.example.Main\n"))

      val mainClass = readMainClassFromJar(jarPath).get()
      assertEquals("com.example.Main", mainClass)
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun readsMainClassWithCrlfLineEndings() {
    val tempDir = createTempDir("kolt_jarread_crlf_")
    try {
      val jarPath = "$tempDir/app.jar"
      writeZip(
        jarPath,
        mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\nMain-Class: a.B\r\n"),
      )
      assertEquals("a.B", readMainClassFromJar(jarPath).get())
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun readsMainClassFromContinuationLines() {
    val tempDir = createTempDir("kolt_jarread_fold_")
    try {
      val jarPath = "$tempDir/app.jar"
      // RFC line-folding: a line starting with SP continues the previous line.
      writeZip(jarPath, mapOf("META-INF/MANIFEST.MF" to "Main-Class: com.exa\n mple.Main\n"))

      assertEquals("com.example.Main", readMainClassFromJar(jarPath).get())
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun returnsMainClassMissingWhenManifestAbsent() {
    val tempDir = createTempDir("kolt_jarread_nomanifest_")
    try {
      val jarPath = "$tempDir/app.jar"
      writeZip(jarPath, mapOf("payload.txt" to "no manifest here"))

      val err = readMainClassFromJar(jarPath).getError()
      val missing = assertIs<ToolLaunchError.MainClassMissing>(err)
      assertEquals(jarPath, missing.jarPath)
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun returnsMainClassMissingWhenManifestPresentButHasNoMainClassLine() {
    val tempDir = createTempDir("kolt_jarread_nomain_")
    try {
      val jarPath = "$tempDir/app.jar"
      writeZip(jarPath, mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n"))

      val err = readMainClassFromJar(jarPath).getError()
      assertIs<ToolLaunchError.MainClassMissing>(err)
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun returnsNotRunnableJarForPlainTextMasqueradingAsJar() {
    val tempDir = createTempDir("kolt_jarread_text_")
    try {
      val jarPath = "$tempDir/fake.jar"
      writeFileAsString(jarPath, "this is not a zip\n")

      val err = readMainClassFromJar(jarPath).getError()
      val notRunnable = assertIs<ToolLaunchError.NotRunnableJar>(err)
      assertEquals(jarPath, notRunnable.jarPath)
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun returnsNotRunnableJarForNonexistentPath() {
    val err = readMainClassFromJar("/nonexistent/path/does-not-exist.jar").getError()
    assertIs<ToolLaunchError.NotRunnableJar>(err)
  }

  // ----- extractMainClass: pure helper, exercised independently of libarchive -----

  @Test
  fun extractMainClassReturnsNullWhenAbsent() {
    assertNull(extractMainClass("Manifest-Version: 1.0\n"))
  }

  @Test
  fun extractMainClassReturnsNullForEmptyValue() {
    assertNull(extractMainClass("Main-Class: \n"))
  }

  @Test
  fun extractMainClassHandlesTabContinuation() {
    val joined = extractMainClass("Main-Class: com.exa\n\tmple.Main\n")
    assertNotNull(joined)
    assertEquals("com.example.Main", joined)
  }

  // ----- launch -----

  @Test
  fun launchPropagatesZeroExitCode() {
    val tempDir = createTempDir("kolt_launch_zero_")
    try {
      val jarPath = "$tempDir/app.jar"
      writeZip(jarPath, mapOf("META-INF/MANIFEST.MF" to "Main-Class: a.B\n"))
      val capturedCommands = mutableListOf<List<String>>()
      val result =
        launch(
          alias = "stub",
          jarHandle = handleFor(jarPath),
          args = listOf("--foo", "bar"),
          paths = KoltPaths(home = "/home/u"),
          env = emptyMap(),
          resolveJavaBin = { Ok("/fake/jdk/java") },
          exec = { cmd, _ ->
            capturedCommands.add(cmd)
            Ok(0)
          },
        )
      assertEquals(0, result.get())
      assertEquals(
        listOf(listOf("/fake/jdk/java", "-jar", jarPath, "--foo", "bar")),
        capturedCommands,
      )
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun launchPropagatesNonZeroExitCodeAsOk() {
    val tempDir = createTempDir("kolt_launch_42_")
    try {
      val jarPath = "$tempDir/app.jar"
      writeZip(jarPath, mapOf("META-INF/MANIFEST.MF" to "Main-Class: a.B\n"))
      val result =
        launch(
          alias = "stub",
          jarHandle = handleFor(jarPath),
          args = emptyList(),
          paths = KoltPaths(home = "/home/u"),
          env = emptyMap(),
          resolveJavaBin = { Ok("/fake/jdk/java") },
          exec = { _, _ -> Err(ProcessError.NonZeroExit(42)) },
        )
      assertEquals(42, result.get())
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun launchEmitsVerboseLineWhenKoltVerboseSet() {
    val tempDir = createTempDir("kolt_launch_verbose_")
    try {
      val jarPath = "$tempDir/app.jar"
      writeZip(jarPath, mapOf("META-INF/MANIFEST.MF" to "Main-Class: a.B\n"))
      val logged = mutableListOf<String>()
      launch(
        alias = "ktlint",
        jarHandle = handleFor(jarPath),
        args = emptyList(),
        paths = KoltPaths(home = "/home/u"),
        env = mapOf("KOLT_VERBOSE" to "1"),
        resolveJavaBin = { Ok("/jdk25/bin/java") },
        exec = { _, _ -> Ok(0) },
        log = { logged.add(it) },
      )
      assertEquals(1, logged.size, "expected single verbose line, got $logged")
      assertEquals("tool=ktlint jdk=/jdk25/bin/java jar=$jarPath", logged.single())
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun launchSuppressesVerboseLineWhenKoltVerboseUnset() {
    val tempDir = createTempDir("kolt_launch_quiet_")
    try {
      val jarPath = "$tempDir/app.jar"
      writeZip(jarPath, mapOf("META-INF/MANIFEST.MF" to "Main-Class: a.B\n"))
      val logged = mutableListOf<String>()
      launch(
        alias = "x",
        jarHandle = handleFor(jarPath),
        args = emptyList(),
        paths = KoltPaths(home = "/home/u"),
        env = emptyMap(),
        resolveJavaBin = { Ok("/jdk/java") },
        exec = { _, _ -> Ok(0) },
        log = { logged.add(it) },
      )
      assertTrue(logged.isEmpty(), "verbose log unexpectedly emitted: $logged")
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun launchReturnsJdkUnavailableWhenResolveFails() {
    val tempDir = createTempDir("kolt_launch_nojdk_")
    try {
      val jarPath = "$tempDir/app.jar"
      writeZip(jarPath, mapOf("META-INF/MANIFEST.MF" to "Main-Class: a.B\n"))
      val result =
        launch(
          alias = "stub",
          jarHandle = handleFor(jarPath),
          args = emptyList(),
          paths = KoltPaths(home = "/home/u"),
          env = emptyMap(),
          resolveJavaBin = { Err("install failed: disk full") },
          exec = { _, _ -> error("exec must not be called when JDK is unavailable") },
        )
      val err = assertIs<ToolLaunchError.JdkUnavailable>(result.getError())
      assertEquals("install failed: disk full", err.cause)
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun launchLiftsManifestMissingErrorWithAlias() {
    val tempDir = createTempDir("kolt_launch_nomain_")
    try {
      val jarPath = "$tempDir/app.jar"
      writeZip(jarPath, mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n"))
      var execCalled = false
      val result =
        launch(
          alias = "ktlint",
          jarHandle = handleFor(jarPath),
          args = emptyList(),
          paths = KoltPaths(home = "/home/u"),
          env = emptyMap(),
          resolveJavaBin = { Ok("/jdk/java") },
          exec = { _, _ ->
            execCalled = true
            Ok(0)
          },
        )
      val err = assertIs<ToolLaunchError.MainClassMissing>(result.getError())
      assertEquals("ktlint", err.alias)
      assertFalse(execCalled, "exec must not run when MANIFEST has no Main-Class")
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun launchLiftsNotRunnableJarErrorWithAlias() {
    val tempDir = createTempDir("kolt_launch_text_")
    try {
      val jarPath = "$tempDir/fake.jar"
      writeFileAsString(jarPath, "not a zip\n")
      val result =
        launch(
          alias = "detekt",
          jarHandle = handleFor(jarPath),
          args = emptyList(),
          paths = KoltPaths(home = "/home/u"),
          env = emptyMap(),
          resolveJavaBin = { Ok("/jdk/java") },
          exec = { _, _ -> Ok(0) },
        )
      val err = assertIs<ToolLaunchError.NotRunnableJar>(result.getError())
      assertEquals("detekt", err.alias)
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  @Test
  fun launchPassesEnvVerbatimToExec() {
    val tempDir = createTempDir("kolt_launch_env_")
    try {
      val jarPath = "$tempDir/app.jar"
      writeZip(jarPath, mapOf("META-INF/MANIFEST.MF" to "Main-Class: a.B\n"))
      val expectedEnv = mapOf("FOO" to "bar", "KOLT_VERBOSE" to "1")
      val seenEnvs = mutableListOf<Map<String, String>>()
      launch(
        alias = "x",
        jarHandle = handleFor(jarPath),
        args = emptyList(),
        paths = KoltPaths(home = "/home/u"),
        env = expectedEnv,
        resolveJavaBin = { Ok("/jdk/java") },
        exec = { _, env ->
          seenEnvs.add(env)
          Ok(0)
        },
        log = { /* swallow verbose */ },
      )
      assertEquals(listOf(expectedEnv), seenEnvs)
    } finally {
      removeDirectoryRecursive(tempDir)
    }
  }

  // ----- helpers -----

  private fun handleFor(jarPath: String): ToolJarHandle =
    ToolJarHandle(
      jarPath = jarPath,
      resolvedCoords = Coordinate("g", "a", "1.0"),
      classifier = null,
      lockfileChanged = false,
    )

  @OptIn(ExperimentalForeignApi::class)
  private fun writeZip(path: String, entries: Map<String, String>) {
    val writer = archive_write_new() ?: error("archive_write_new returned null")
    try {
      check(archive_write_set_format_zip(writer) == ARCHIVE_OK) {
        "archive_write_set_format_zip failed"
      }
      check(archive_write_open_filename(writer, path) == ARCHIVE_OK) {
        "archive_write_open_filename failed"
      }
      for ((name, content) in entries) {
        val entry = archive_entry_new() ?: error("archive_entry_new returned null")
        try {
          archive_entry_set_pathname(entry, name)
          val bytes = content.encodeToByteArray()
          archive_entry_set_size(entry, bytes.size.toLong())
          archive_entry_set_filetype(entry, S_IFREG.convert())
          check(archive_write_header(writer, entry) == ARCHIVE_OK) { "archive_write_header failed" }
          if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
              val written = archive_write_data(writer, pinned.addressOf(0), bytes.size.convert())
              check(written == bytes.size.toLong()) {
                "archive_write_data short write: $written / ${bytes.size}"
              }
            }
          }
        } finally {
          archive_entry_free(entry)
        }
      }
    } finally {
      archive_write_close(writer)
      archive_write_free(writer)
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
}
