@file:OptIn(ExperimentalBuildToolsApi::class)

package kolt.daemon.ic

import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain.Companion.jvm
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

// Caches ClasspathEntrySnapshot files keyed by (path, mtime, size).
// Snapshot files are persisted under a shared, version-stamped directory
// so they survive per-project self-heal wipes and benefit all projects
// that share the same dependency jars. See ADR 0019 §Negative follow-up.
//
// Thread safety: `ConcurrentHashMap.compute` serialises concurrent
// requests for the same cache key. The BTA snapshot call (~310ms for
// kotlin-stdlib) runs inside the lock, which can block unrelated keys
// that hash to the same bin. Acceptable because DaemonServer currently
// serialises compile traffic; if a future parallel dispatch materialises,
// replace with a `computeIfAbsent(key) { CompletableFuture }` pattern.
class ClasspathSnapshotCache(
    private val toolchain: KotlinToolchains,
    private val snapshotsDir: Path,
    private val metrics: IcMetricsSink = NoopIcMetricsSink,
) {

    // In-memory index: key hash → snapshot file path. Daemon lifetime.
    // On restart the in-memory map is empty but the on-disk files remain;
    // `getOrComputeSnapshots` checks `Files.exists` before declaring a hit.
    private val cache: ConcurrentHashMap<String, Path> = ConcurrentHashMap()

    // Returns snapshot file paths in classpath order on success, or an
    // empty list if any entry fails. All-or-nothing: BTA's behaviour on
    // partial `dependenciesSnapshotFiles` is unspecified, so a partial
    // list risks worse precision than no list at all. An empty return
    // matches the pre-#127 behaviour (`emptyList()`).
    fun getOrComputeSnapshots(classpath: List<Path>): List<Path> {
        Files.createDirectories(snapshotsDir)
        val start = TimeSource.Monotonic.markNow()
        val result = mutableListOf<Path>()
        for (entry in classpath) {
            val snapshot = try {
                getOrCompute(entry)
            } catch (vme: VirtualMachineError) {
                throw vme
            } catch (_: Throwable) {
                metrics.record(METRIC_ERROR)
                null
            }
            if (snapshot == null) {
                val wallMs = start.elapsedNow().toLong(DurationUnit.MILLISECONDS)
                metrics.record(METRIC_WALL_MS, wallMs)
                return emptyList()
            }
            result.add(snapshot)
        }
        val wallMs = start.elapsedNow().toLong(DurationUnit.MILLISECONDS)
        metrics.record(METRIC_WALL_MS, wallMs)
        return result
    }

    private fun getOrCompute(entry: Path): Path? {
        if (!Files.exists(entry)) return null
        val attrs = Files.readAttributes(entry, java.nio.file.attribute.BasicFileAttributes::class.java)
        val key = keyFor(entry, attrs.lastModifiedTime().toMillis(), attrs.size())
        // Include jar basename for debuggability: `ls classpath-snapshots/`
        // shows `kotlin-stdlib-2.3.20-a3f2e9b1.snapshot` instead of a bare hash.
        val basename = entry.fileName?.toString()?.removeSuffix(".jar") ?: key
        val snapshotPath = snapshotsDir.resolve("$basename-$key.snapshot")

        // Fast path: in-memory cache hit + file still exists on disk
        val cached = cache[key]
        if (cached != null && Files.exists(cached)) {
            metrics.record(METRIC_HIT)
            return cached
        }

        // Check if on-disk file exists (daemon restart recovery)
        if (Files.exists(snapshotPath)) {
            cache[key] = snapshotPath
            metrics.record(METRIC_HIT)
            return snapshotPath
        }

        // Cache miss: compute snapshot via BTA API
        return cache.compute(key) { _, existing ->
            // Double-check inside compute() for concurrent access
            if (existing != null && Files.exists(existing)) {
                metrics.record(METRIC_HIT)
                return@compute existing
            }
            val snapshot = toolchain.createBuildSession().use { session ->
                val op = toolchain.jvm.classpathSnapshottingOperationBuilder(entry).build()
                session.executeOperation(op)
            }
            snapshot.saveSnapshot(snapshotPath)
            metrics.record(METRIC_MISS)
            snapshotPath
        }
    }

    private fun keyFor(path: Path, mtime: Long, size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("${path.toAbsolutePath()}|$mtime|$size".toByteArray(Charsets.UTF_8))
        return md.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    companion object {
        internal const val METRIC_HIT: String = "ic.classpath_snapshot_hit"
        internal const val METRIC_MISS: String = "ic.classpath_snapshot_miss"
        internal const val METRIC_WALL_MS: String = "ic.classpath_snapshot_ms"
        internal const val METRIC_ERROR: String = "ic.classpath_snapshot_error"
    }
}
