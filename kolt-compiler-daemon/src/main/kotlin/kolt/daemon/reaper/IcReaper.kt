package kolt.daemon.reaper

import kolt.daemon.ic.IcMetricsSink
import kolt.daemon.ic.IcStateLayout
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// ADR 0019 §5 / §Negative follow-up: prune stale state under
// `~/.kolt/daemon/ic/`. The layout written by IcStateLayout is
// `<icRoot>/<kotlinVersion>/<projectIdHash>/…`. Two leak sources are
// handled:
//
//  A. Whole `<kotlinVersion>` segments other than the daemon's current
//     pin, left behind when `KOLT_DAEMON_KOTLIN_VERSION` moved forward.
//     ADR 0019 §5 explicitly defers this cleanup to a future reaper.
//
//  B. `<projectIdHash>` directories inside the current-version segment
//     whose source project has been moved or deleted. Since
//     `IcStateLayout.projectIdFor` is a one-way SHA-256, we cannot
//     recover the project path from the hash — so the adapter drops a
//     `project.path` breadcrumb at cold path and the reaper reads it
//     back. Anything that does not resolve to an existing on-disk path
//     (missing breadcrumb, empty content, unparseable path, vanished
//     projectRoot) is dropped into `tryDelete`.
//
// Exclusion: an advisory `tryLock` probe on `<projectIdDir>/LOCK`.
// `BtaIncrementalCompiler` holds this lock for the daemon's lifetime,
// so a dir whose LOCK is held cannot be the current daemon's active
// working directory — even across hash collisions. Rules A+B are
// already safe by construction (A never touches the current version;
// B only removes dangling projectRoots that a live daemon cannot have
// spawned from), so the lock probe is belt-and-suspenders insurance.
//
// Known gap (#199): `BtaIncrementalCompiler.compile` creates the
// working dir and writes the breadcrumb before calling `ensureLock`,
// so a concurrent-daemon-boot reaper can unlink a live dir mid-setup.
// Symptom is a cold recompile, not corruption. Fix lives on the writer
// side (reorder to lock-first), tracked separately.
object IcReaper {

    data class Report(
        val scanned: Int,
        val removed: Int,
        val skippedLocked: Int,
        val errors: List<String>,
    )

    fun run(icRoot: Path, currentKotlinVersion: String, metrics: IcMetricsSink): Report {
        if (!Files.isDirectory(icRoot)) return EMPTY_REPORT

        var scanned = 0
        var removed = 0
        var skippedLocked = 0
        val errors = mutableListOf<String>()

        directoryChildren(icRoot).forEach { versionDir ->
            try {
                if (versionDir.fileName.toString() != currentKotlinVersion) {
                    directoryChildren(versionDir).forEach { projectIdDir ->
                        scanned++
                        when (val outcome = tryDelete(projectIdDir)) {
                            DeleteOutcome.Removed -> removed++
                            DeleteOutcome.Locked -> skippedLocked++
                            is DeleteOutcome.Failed -> errors += outcome.message
                        }
                    }
                    // Best-effort removal of the now-empty version segment.
                    runCatching { Files.deleteIfExists(versionDir) }
                } else {
                    directoryChildren(versionDir).forEach { projectIdDir ->
                        scanned++
                        if (breadcrumbPointsToExistingPath(projectIdDir)) return@forEach
                        when (val outcome = tryDelete(projectIdDir)) {
                            DeleteOutcome.Removed -> removed++
                            DeleteOutcome.Locked -> skippedLocked++
                            is DeleteOutcome.Failed -> errors += outcome.message
                        }
                    }
                }
            } catch (e: IOException) {
                errors += "scan $versionDir: ${e.message}"
            } catch (e: UncheckedIOException) {
                errors += "scan $versionDir: ${e.message}"
            }
        }

        metrics.record("reaper.scanned", scanned.toLong())
        metrics.record("reaper.removed", removed.toLong())
        metrics.record("reaper.skipped_locked", skippedLocked.toLong())
        if (errors.isNotEmpty()) metrics.record("reaper.error", errors.size.toLong())

        return Report(scanned, removed, skippedLocked, errors.toList())
    }

    // Breadcrumb is live only when it resolves to an on-disk path that still
    // exists. Anything else — missing file, empty content, unparseable path —
    // falls through to `tryDelete`, where the LOCK probe is the last line of
    // defence against clobbering an in-flight compile.
    private fun breadcrumbPointsToExistingPath(projectIdDir: Path): Boolean {
        val breadcrumb = projectIdDir.resolve(BREADCRUMB)
        val projectRoot = runCatching { Files.readString(breadcrumb).trim() }.getOrNull()
        if (projectRoot.isNullOrEmpty()) return false
        val resolvedProjectRoot = try {
            Path.of(projectRoot)
        } catch (_: InvalidPathException) {
            return false
        }
        return Files.exists(resolvedProjectRoot)
    }

    fun runDetached(
        icRoot: Path,
        currentKotlinVersion: String,
        metrics: IcMetricsSink,
    ): Thread =
        Thread({
            runCatching { run(icRoot, currentKotlinVersion, metrics) }
                .onFailure { metrics.record("reaper.error") }
        }, "ic-reaper").apply {
            isDaemon = true
            start()
        }

    private fun tryDelete(dir: Path): DeleteOutcome {
        val lockPath = dir.resolve(LOCK_FILE)
        if (Files.isRegularFile(lockPath)) {
            val probe = runCatching {
                FileChannel.open(
                    lockPath,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                )
            }.getOrElse { return DeleteOutcome.Failed("open LOCK for ${dir}: ${it.message}") }
            probe.use { channel ->
                // `tryLock` returns `null` when another JVM holds the lock
                // and throws `OverlappingFileLockException` when the same
                // JVM already holds an overlapping lock through a
                // different `FileChannel`. Both outcomes mean "someone
                // else is using this dir" as far as the reaper is
                // concerned.
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    return DeleteOutcome.Locked
                } catch (e: IOException) {
                    return DeleteOutcome.Failed("tryLock LOCK for ${dir}: ${e.message}")
                }
                if (lock == null) return DeleteOutcome.Locked
                lock.release()
            }
        }
        return try {
            deletePostOrder(dir)
            DeleteOutcome.Removed
        } catch (e: IOException) {
            DeleteOutcome.Failed("delete $dir: ${e.message}")
        } catch (e: UncheckedIOException) {
            // `Files.walk(...).forEach { ... }` wraps IO failures from
            // inside the stream pipeline. Catch both shapes so a
            // directory mutated mid-walk by another actor cannot crash
            // the reaper.
            DeleteOutcome.Failed("delete $dir: ${e.message}")
        }
    }

    // Post-order walk: children before parents. Mirrors the shape used
    // by `SelfHealingIncrementalCompiler.defaultWipe` — reverse-sorted
    // `Files.walk` works because lexicographic order over paths places
    // every descendant after its parent string, so reversing visits
    // deepest entries first.
    private fun deletePostOrder(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                Files.deleteIfExists(path)
            }
        }
    }

    private fun directoryChildren(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { stream ->
            stream.filter { Files.isDirectory(it) }.sortedBy { it.fileName.toString() }
        }
    }

    private sealed interface DeleteOutcome {
        data object Removed : DeleteOutcome
        data object Locked : DeleteOutcome
        data class Failed(val message: String) : DeleteOutcome
    }

    private val EMPTY_REPORT = Report(
        scanned = 0,
        removed = 0,
        skippedLocked = 0,
        errors = emptyList(),
    )

    private const val BREADCRUMB: String = IcStateLayout.BREADCRUMB_FILE
    private const val LOCK_FILE: String = IcStateLayout.LOCK_FILE
}
