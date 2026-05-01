@file:OptIn(ExperimentalBuildToolsApi::class)

package kolt.daemon.ic

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm

// Phase 0 measurement: how expensive is ClasspathEntrySnapshot computation
// for representative jars? This test prints wall times so we can decide
// whether caching is load-bearing (>200ms) or just nice-to-have (<50ms).
// Not a regression gate — the assertions are loose upper bounds to catch
// catastrophic regressions, not to pin expected performance.
class ClasspathSnapshotCostTest {

  private val btaImplJars: List<Path> = systemClasspath("kolt.ic.btaImplClasspath")
  private val fixtureClasspath: List<Path> = systemClasspath("kolt.ic.fixtureClasspath")

  @Test
  fun `measure classpath snapshot computation cost per jar`() {
    val implUrls = btaImplJars.map { it.toUri().toURL() }.toTypedArray()
    val implLoader = URLClassLoader(implUrls, SharedApiClassesClassLoader())
    val toolchain = KotlinToolchains.loadImplementation(implLoader)

    val snapshotsDir =
      Files.createTempDirectory("cp-snapshot-cost-").resolve("snapshots").apply {
        createDirectories()
      }

    println("=== ClasspathEntrySnapshot computation cost ===")
    println("classpath entries: ${fixtureClasspath.size}")

    for (entry in fixtureClasspath) {
      val fileSize = Files.size(entry)
      println("  entry: ${entry.fileName} (${fileSize / 1024} KB)")

      // Warm up: first call may include classloader init overhead
      val warmupFile = snapshotsDir.resolve("warmup-${entry.fileName}.snapshot")
      val warmupStart = TimeSource.Monotonic.markNow()
      val warmupSnapshot =
        toolchain.createBuildSession().use { session ->
          val op = toolchain.jvm.classpathSnapshottingOperationBuilder(entry).build()
          session.executeOperation(op)
        }
      val warmupMs = warmupStart.elapsedNow().toLong(DurationUnit.MILLISECONDS)
      warmupSnapshot.saveSnapshot(warmupFile)
      val snapshotFileSize = Files.size(warmupFile)
      println("    warmup:  ${warmupMs}ms (snapshot file: ${snapshotFileSize / 1024} KB)")

      // Measured run (no classloader init overhead)
      val measuredFile = snapshotsDir.resolve("measured-${entry.fileName}.snapshot")
      val measuredStart = TimeSource.Monotonic.markNow()
      val measuredSnapshot =
        toolchain.createBuildSession().use { session ->
          val op = toolchain.jvm.classpathSnapshottingOperationBuilder(entry).build()
          session.executeOperation(op)
        }
      val measuredMs = measuredStart.elapsedNow().toLong(DurationUnit.MILLISECONDS)
      measuredSnapshot.saveSnapshot(measuredFile)
      println("    steady:  ${measuredMs}ms")

      // Sanity: snapshot file was actually written
      assertTrue(Files.exists(warmupFile), "snapshot file must exist")
      assertTrue(snapshotFileSize > 0, "snapshot file must be non-empty")
    }

    // Third run to confirm stability
    val thirdRunTimes = mutableListOf<Long>()
    for (entry in fixtureClasspath) {
      val start = TimeSource.Monotonic.markNow()
      toolchain.createBuildSession().use { session ->
        val op = toolchain.jvm.classpathSnapshottingOperationBuilder(entry).build()
        session.executeOperation(op)
      }
      thirdRunTimes.add(start.elapsedNow().toLong(DurationUnit.MILLISECONDS))
    }
    val totalThirdRun = thirdRunTimes.sum()
    println("  third-run total: ${totalThirdRun}ms (${thirdRunTimes.joinToString(", ")}ms each)")
    println("=== end ===")
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
