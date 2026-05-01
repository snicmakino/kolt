@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package kolt.daemon.ic

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrElse
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class BtaIncrementalLockFailureTest {

  private val btaImplJars: List<Path> = systemClasspath("kolt.ic.btaImplClasspath")
  private val fixtureClasspath: List<Path> = systemClasspath("kolt.ic.fixtureClasspath")

  @Test
  fun `LOCK file creation failure fails compile with InternalError and skips subsequent writes`() {
    val workRoot = Files.createTempDirectory("bta-lock-fail-")
    val sourceFile =
      workRoot.resolve("Main.kt").also {
        it.writeText(
          """
                package fixture
                object Main { fun greeting(): String = "hi" }
                """
            .trimIndent()
        )
      }
    val outputDir = workRoot.resolve("classes").apply { createDirectories() }
    val workingDir = workRoot.resolve("ic").apply { createDirectories() }
    // Precreate LOCK as a directory so `FileChannel.open(lockPath,
    // CREATE, READ, WRITE)` cannot open it — EISDIR on Linux surfaces
    // as `FileSystemException` from the JDK.
    workingDir.resolve("LOCK").createDirectories()

    val metrics = RecordingMetricsSink()
    val compiler =
      BtaIncrementalCompiler.create(btaImplJars = btaImplJars, metrics = metrics).getOrElse {
        fail("failed to load BTA toolchain: $it")
      }

    val err =
      compiler
        .compile(
          IcRequest(
            projectId = "lock-failure-smoke",
            projectRoot = workRoot,
            sources = listOf(sourceFile),
            classpath = fixtureClasspath,
            outputDir = outputDir,
            workingDir = workingDir,
          )
        )
        .getError() ?: fail("expected Err when LOCK file cannot be created")

    assertTrue(err is IcError.InternalError, "expected InternalError, got $err")
    val counterNames = metrics.events.map { it.first }.toSet()
    assertTrue(
      "reaper.lock_failed" in counterNames,
      "expected reaper.lock_failed metric, got: $counterNames",
    )
    // If ensureLock failed but compile continued, breadcrumb would leak here.
    assertTrue(
      !Files.exists(workingDir.resolve("project.path")),
      "breadcrumb must not be written when ensureLock failed",
    )
  }

  @Test
  fun `LOCK held by another owner lets compile proceed and records lock_conflict`() {
    val workRoot = Files.createTempDirectory("bta-lock-conflict-")
    val sourceFile =
      workRoot.resolve("Main.kt").also {
        it.writeText(
          """
                package fixture
                object Main { fun greeting(): String = "hi" }
                """
            .trimIndent()
        )
      }
    val outputDir = workRoot.resolve("classes").apply { createDirectories() }
    val workingDir = workRoot.resolve("ic").apply { createDirectories() }
    val lockPath = workingDir.resolve("LOCK")

    // Holding an exclusive FileLock from another channel in the same JVM
    // makes the adapter's subsequent `tryLock` raise
    // `OverlappingFileLockException` (a different process would instead
    // get `null`). Both converge at `if (lock == null)`, so this one
    // fixture covers both the same-JVM and cross-process paths.
    FileChannel.open(
        lockPath,
        StandardOpenOption.CREATE,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
      )
      .use { holder ->
        holder.lock().use {
          val metrics = RecordingMetricsSink()
          val compiler =
            BtaIncrementalCompiler.create(btaImplJars = btaImplJars, metrics = metrics).getOrElse {
              fail("failed to load BTA toolchain: $it")
            }

          compiler
            .compile(
              IcRequest(
                projectId = "lock-conflict-smoke",
                projectRoot = workRoot,
                sources = listOf(sourceFile),
                classpath = fixtureClasspath,
                outputDir = outputDir,
                workingDir = workingDir,
              )
            )
            .getOrElse { fail("expected success despite lock conflict, got Err($it)") }

          val counterNames = metrics.events.map { it.first }.toSet()
          assertTrue(
            "reaper.lock_conflict" in counterNames,
            "expected reaper.lock_conflict metric, got: $counterNames",
          )
          assertEquals(
            false,
            "reaper.lock_failed" in counterNames,
            "must not record lock_failed when LOCK file already exists, got: $counterNames",
          )
        }
      }
  }

  private class RecordingMetricsSink : IcMetricsSink {
    val events: MutableList<Pair<String, Long>> = mutableListOf()

    override fun record(name: String, value: Long) {
      events += name to value
    }
  }

  private fun systemClasspath(key: String): List<Path> {
    val raw =
      System.getProperty(key)
        ?: error(
          "$key system property not set — check kolt-jvm-compiler-daemon/kolt.toml [test.sys_props]"
        )
    return raw.split(File.pathSeparator).filter { it.isNotBlank() }.map { Path.of(it) }
  }
}
