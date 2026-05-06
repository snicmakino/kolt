package kolt.daemon.ic

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

// Global content-keyed cache for BTA `shrunk-classpath-snapshot.bin`
// files. Keyed by the ordered classpath's `(path|mtime|size)` tuple set
// so that two compiles with the same effective classpath share the
// shrunk snapshot file across scopes and projects. See ADR 0019 §5 and
// the 380-share-classpath-snapshots design.
//
// Placement uses `Files.copy` (NOT hardlink). BTA writes the per-scope
// shrunk file in-place after compile (Spike T1 Q3, OQ-3 closed
// permanently); a hardlink would let BTA's per-scope write corrupt the
// shared cache file. The cost of copying a few-KB shrunk file is
// negligible compared to BTA snapshot computation.
//
// Concurrency: per-key `Any` mutex acquired via
// `ConcurrentHashMap.computeIfAbsent` serializes stores against the same
// key in-process. Cross-process safety relies on POSIX `rename(2)`
// atomicity: each writer stages to `<key>.<pid>-<uuid>.bin.tmp` (unique
// per writer so two PIDs never share the same staging file), then atomic
// rename to `<key>.bin`. Last-writer-wins is correctness-safe because
// BTA's shrunk file content is a "subset of classes referenced so far" —
// any valid prior cache entry is a sound starting point and BTA extends
// in place as needed. A reader hitting the rename window observes either
// the pre-rename or post-rename inode; both are valid starting points.
class ShrunkClasspathSnapshotCache(
  private val cacheDir: Path,
  private val metrics: IcMetricsSink = NoopIcMetricsSink,
) {

  // Key derivation digests `(absolutePath|mtime|size)` per classpath
  // entry, accumulates the per-entry digests in classpath order, and
  // returns the first 16 bytes of the accumulator digest as 32-char
  // lowercase hex. Mirrors `ClasspathSnapshotCache.keyFor` so the two
  // cache layers share the same identity story.
  @JvmInline value class ClasspathKey(val hex: String)

  sealed interface PlacementOutcome {
    data object Empty : PlacementOutcome

    data class Placed(val sourceKey: ClasspathKey) : PlacementOutcome

    data class PlacedPrefix(val sourceKey: ClasspathKey, val prefixLen: Int) : PlacementOutcome
  }

  sealed interface CacheError {
    data class IoFailure(val cause: IOException) : CacheError
  }

  // Per-key in-process mutex. Re-entrancy is not required; the cache
  // never calls itself recursively.
  private val keyLocks: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

  fun classpathKey(classpath: List<Path>): ClasspathKey {
    val accumulator = MessageDigest.getInstance("SHA-256")
    val perEntry = MessageDigest.getInstance("SHA-256")
    for (entry in classpath) {
      perEntry.reset()
      val abs = entry.toAbsolutePath().toString()
      val (mtime, size) =
        if (Files.exists(entry)) {
          val attrs =
            Files.readAttributes(entry, java.nio.file.attribute.BasicFileAttributes::class.java)
          attrs.lastModifiedTime().toMillis() to attrs.size()
        } else {
          // Stable sentinel for missing entries: keys for missing files
          // do not collide with present-file keys at the same path.
          0L to -1L
        }
      perEntry.update("$abs|$mtime|$size".toByteArray(Charsets.UTF_8))
      accumulator.update(perEntry.digest())
    }
    val finalDigest = accumulator.digest()
    val hex = finalDigest.take(16).joinToString("") { "%02x".format(it) }
    return ClasspathKey(hex)
  }

  fun lookupAndPlace(
    classpath: List<Path>,
    destination: Path,
  ): Result<PlacementOutcome, CacheError> {
    return try {
      // Exact match first.
      val exactKey = classpathKey(classpath)
      val exactPath = cachePathFor(exactKey)
      if (Files.exists(exactPath)) {
        copyToDestination(exactPath, destination)
        recordLookup("hit", exactKey.hex)
        return Ok(PlacementOutcome.Placed(exactKey))
      }

      // Longest-prefix match. Bound the search depth so cache lookup
      // stays O(PREFIX_DEPTH) digest computations regardless of N.
      val maxPrefix = classpath.size - 1
      val minPrefix = maxOf(1, classpath.size - PREFIX_DEPTH)
      var prefixLen = maxPrefix
      while (prefixLen >= minPrefix) {
        val prefixKey = classpathKey(classpath.subList(0, prefixLen))
        val prefixPath = cachePathFor(prefixKey)
        if (Files.exists(prefixPath)) {
          copyToDestination(prefixPath, destination)
          recordLookup("prefix_hit", prefixKey.hex, prefixLen)
          return Ok(PlacementOutcome.PlacedPrefix(prefixKey, prefixLen))
        }
        prefixLen--
      }

      recordLookup("miss", exactKey.hex)
      Ok(PlacementOutcome.Empty)
    } catch (vme: VirtualMachineError) {
      throw vme
    } catch (ioe: IOException) {
      recordLookupFailure()
      Err(CacheError.IoFailure(ioe))
    }
  }

  fun storeIfNew(classpath: List<Path>, producedSnapshot: Path): Result<Unit, CacheError> {
    return try {
      val key = classpathKey(classpath)
      val target = cachePathFor(key)

      val producedAttrs =
        Files.readAttributes(
          producedSnapshot,
          java.nio.file.attribute.BasicFileAttributes::class.java,
        )
      val producedSize = producedAttrs.size()
      val producedMtime = producedAttrs.lastModifiedTime().toMillis()

      // Per-key mutex serializes concurrent stores against the same
      // cache file. The work itself (size+mtime check, atomic rename)
      // is short — sub-millisecond — so blocking unrelated keys hashed
      // to the same bin is acceptable.
      val lock = keyLocks.computeIfAbsent(key.hex) { Any() }
      synchronized(lock) {
        if (Files.exists(target)) {
          val existing =
            Files.readAttributes(target, java.nio.file.attribute.BasicFileAttributes::class.java)
          if (
            existing.size() == producedSize &&
              existing.lastModifiedTime().toMillis() == producedMtime
          ) {
            recordStore("skip", key.hex)
            return Ok(Unit)
          }
        }

        Files.createDirectories(cacheDir)
        // Unique per writer (PID + UUID) so two daemon processes racing
        // on the same key never share the same staging file. The
        // synchronized(lock) above only serializes within one JVM.
        val tmp =
          cacheDir.resolve(
            "${key.hex}.${ProcessHandle.current().pid()}-${java.util.UUID.randomUUID()}.bin.tmp"
          )
        try {
          // COPY_ATTRIBUTES carries the source mtime through `tmp` and
          // (via atomic_move) into `target`, so a later storeIfNew call
          // with an unchanged producedSnapshot sees `existing.mtime ==
          // producedSnapshot.mtime` and short-circuits via the size+mtime
          // skip branch above. Without it, target inherits wall-clock
          // mtime and the skip never fires (the per-jar cache works
          // because its key already includes mtime).
          Files.copy(
            producedSnapshot,
            tmp,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
          )
          Files.move(
            tmp,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
          )
        } finally {
          // Clean up tmp if the move did not consume it (e.g. on error
          // partway through). `deleteIfExists` is safe to call even
          // after a successful atomic move.
          try {
            Files.deleteIfExists(tmp)
          } catch (_: IOException) {
            // best-effort cleanup; nothing actionable
          }
        }
      }

      recordStore("success", key.hex)
      Ok(Unit)
    } catch (vme: VirtualMachineError) {
      throw vme
    } catch (ioe: IOException) {
      recordStoreFailure()
      Err(CacheError.IoFailure(ioe))
    }
  }

  private fun cachePathFor(key: ClasspathKey): Path = cacheDir.resolve("${key.hex}.bin")

  private fun copyToDestination(source: Path, destination: Path) {
    destination.parent?.let { Files.createDirectories(it) }
    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
  }

  private fun recordLookup(outcome: String, keyHex: String, prefixLen: Int? = null) {
    metrics.record("shrunk_cache.lookup.$outcome")
    val suffix = if (prefixLen != null) " prefixLen=$prefixLen" else ""
    System.err.println("kolt-ic-info shrunk_cache.lookup=$outcome key=$keyHex$suffix")
  }

  private fun recordLookupFailure() {
    metrics.record("shrunk_cache.lookup.failure")
    System.err.println("kolt-ic-info shrunk_cache.lookup=failure")
  }

  private fun recordStore(outcome: String, keyHex: String) {
    metrics.record("shrunk_cache.store.$outcome")
    System.err.println("kolt-ic-info shrunk_cache.store=$outcome key=$keyHex")
  }

  private fun recordStoreFailure() {
    metrics.record("shrunk_cache.store.failure")
    System.err.println("kolt-ic-info shrunk_cache.store=failure")
  }

  companion object {
    // Max number of trailing entries to drop when searching for a
    // longest-prefix match. Keeps lookup work O(PREFIX_DEPTH) in the
    // miss case. Empirically sized for typical kolt classpaths
    // (kotlin-stdlib + serialization runtime + a handful of local
    // jars); revisit if multi-spec classpaths grow far beyond this.
    private const val PREFIX_DEPTH: Int = 8
  }
}
