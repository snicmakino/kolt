@file:OptIn(ExperimentalBuildToolsApi::class)

package kolt.daemon.ic

import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClasspathSnapshotCacheTest {

    private val btaImplJars: List<Path> = systemClasspath("kolt.ic.btaImplClasspath")
    private val fixtureClasspath: List<Path> = systemClasspath("kolt.ic.fixtureClasspath")

    private fun loadToolchain(): KotlinToolchains {
        val urls = btaImplJars.map { it.toUri().toURL() }.toTypedArray()
        val loader = URLClassLoader(urls, SharedApiClassesClassLoader())
        return KotlinToolchains.loadImplementation(loader)
    }

    @Test
    fun `second call for same classpath returns cached snapshots without recomputation`() {
        val toolchain = loadToolchain()
        val snapshotsDir = Files.createTempDirectory("cp-cache-hit-")
            .resolve("snapshots").apply { createDirectories() }

        val metrics = RecordingMetricsSink()
        val cache = ClasspathSnapshotCache(toolchain, snapshotsDir, metrics)

        // First call: all misses
        val first = cache.getOrComputeSnapshots(fixtureClasspath)
        assertEquals(fixtureClasspath.size, first.size)
        first.forEach { assertTrue(Files.exists(it), "snapshot file must exist: $it") }

        val firstMetrics = metrics.snapshotAndReset()
        val firstMisses = firstMetrics.count { it.first == ClasspathSnapshotCache.METRIC_MISS }
        assertEquals(fixtureClasspath.size, firstMisses, "first call should miss for every entry")

        // Second call: all hits
        val second = cache.getOrComputeSnapshots(fixtureClasspath)
        assertEquals(first, second, "cached result must equal first result")

        val secondMetrics = metrics.snapshotAndReset()
        val secondHits = secondMetrics.count { it.first == ClasspathSnapshotCache.METRIC_HIT }
        val secondMisses = secondMetrics.count { it.first == ClasspathSnapshotCache.METRIC_MISS }
        assertEquals(fixtureClasspath.size, secondHits, "second call should hit for every entry")
        assertEquals(0, secondMisses, "second call should have zero misses")
    }

    @Test
    fun `cache miss when file mtime changes`() {
        val toolchain = loadToolchain()
        val workDir = Files.createTempDirectory("cp-cache-mtime-")
        val snapshotsDir = workDir.resolve("snapshots").apply { createDirectories() }

        // Create a small jar to control mtime
        val jarFile = workDir.resolve("test.jar")
        Files.copy(fixtureClasspath.last(), jarFile)

        val metrics = RecordingMetricsSink()
        val cache = ClasspathSnapshotCache(toolchain, snapshotsDir, metrics)

        val classpath = listOf(jarFile)

        // First call: miss
        cache.getOrComputeSnapshots(classpath)
        metrics.snapshotAndReset()

        // Touch mtime
        val newMtime = java.nio.file.attribute.FileTime.fromMillis(
            Files.getLastModifiedTime(jarFile).toMillis() + 2000,
        )
        Files.setLastModifiedTime(jarFile, newMtime)

        // Second call: miss (mtime changed)
        cache.getOrComputeSnapshots(classpath)
        val secondMetrics = metrics.snapshotAndReset()
        val misses = secondMetrics.count { it.first == ClasspathSnapshotCache.METRIC_MISS }
        assertEquals(1, misses, "mtime change should cause cache miss")
    }

    @Test
    fun `file-backed recovery after in-memory cache is lost`() {
        val toolchain = loadToolchain()
        val snapshotsDir = Files.createTempDirectory("cp-cache-recovery-")
            .resolve("snapshots").apply { createDirectories() }

        val metrics1 = RecordingMetricsSink()
        val cache1 = ClasspathSnapshotCache(toolchain, snapshotsDir, metrics1)

        // Populate cache and snapshot files
        val first = cache1.getOrComputeSnapshots(fixtureClasspath)

        // Create a NEW cache instance (simulates daemon restart), same snapshotsDir
        val metrics2 = RecordingMetricsSink()
        val cache2 = ClasspathSnapshotCache(toolchain, snapshotsDir, metrics2)

        val second = cache2.getOrComputeSnapshots(fixtureClasspath)
        assertEquals(first, second, "file-backed recovery must return same paths")

        val secondMetrics = metrics2.snapshotAndReset()
        val hits = secondMetrics.count { it.first == ClasspathSnapshotCache.METRIC_HIT }
        assertEquals(fixtureClasspath.size, hits, "file-backed recovery should count as hits")
    }

    @Test
    fun `deleted snapshot file triggers recomputation`() {
        val toolchain = loadToolchain()
        val snapshotsDir = Files.createTempDirectory("cp-cache-deleted-")
            .resolve("snapshots").apply { createDirectories() }

        val metrics = RecordingMetricsSink()
        val cache = ClasspathSnapshotCache(toolchain, snapshotsDir, metrics)

        val first = cache.getOrComputeSnapshots(fixtureClasspath)
        metrics.snapshotAndReset()

        // Delete the first snapshot file (simulates self-heal wipe)
        Files.delete(first[0])

        val second = cache.getOrComputeSnapshots(fixtureClasspath)
        assertTrue(Files.exists(second[0]), "snapshot must be recomputed after deletion")

        val postDeleteMetrics = metrics.snapshotAndReset()
        val misses = postDeleteMetrics.count { it.first == ClasspathSnapshotCache.METRIC_MISS }
        assertTrue(misses >= 1, "at least one miss expected after file deletion")
    }

    @Test
    fun `non-existent classpath entry causes all-or-nothing fallback to empty list`() {
        val toolchain = loadToolchain()
        val snapshotsDir = Files.createTempDirectory("cp-cache-aon-")
            .resolve("snapshots").apply { createDirectories() }

        val metrics = RecordingMetricsSink()
        val cache = ClasspathSnapshotCache(toolchain, snapshotsDir, metrics)

        val classpath = fixtureClasspath + Path.of("/nonexistent/bogus.jar")

        val result = cache.getOrComputeSnapshots(classpath)
        assertEquals(emptyList<Path>(), result, "any failure must fall back to empty list")

        val events = metrics.snapshotAndReset()
        val errors = events.count { it.first == ClasspathSnapshotCache.METRIC_ERROR }
        assertEquals(0, errors, "non-existent entry returns null, not an error")
    }

    private class RecordingMetricsSink : IcMetricsSink {
        private val events: MutableList<Pair<String, Long>> = mutableListOf()
        override fun record(name: String, value: Long) {
            events += name to value
        }
        fun snapshotAndReset(): List<Pair<String, Long>> {
            val snap = events.toList()
            events.clear()
            return snap
        }
    }

    private fun systemClasspath(key: String): List<Path> {
        val raw = System.getProperty(key)
            ?: error("$key system property not set — check :ic/build.gradle.kts test task config")
        return raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { Path.of(it) }
    }
}
