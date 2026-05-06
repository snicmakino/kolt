package kolt.daemon.ic

import com.github.michaelbull.result.getError
import com.github.michaelbull.result.getOrThrow
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ShrunkClasspathSnapshotCacheTest {

  @Test
  fun `classpathKey is deterministic on same inputs and differs on any change`() {
    val workDir = Files.createTempDirectory("shrunk-key-determinism-")
    val cacheDir = workDir.resolve("cache").apply { createDirectories() }
    val a = workDir.resolve("a.jar").apply { Files.write(this, byteArrayOf(1, 2, 3)) }
    val b = workDir.resolve("b.jar").apply { Files.write(this, byteArrayOf(4, 5, 6)) }

    val cache = ShrunkClasspathSnapshotCache(cacheDir)

    val key1 = cache.classpathKey(listOf(a, b))
    val key2 = cache.classpathKey(listOf(a, b))
    assertEquals(key1, key2, "same input must produce same key")

    // Order-significance
    val keyReversed = cache.classpathKey(listOf(b, a))
    assertNotEquals(key1, keyReversed, "order change must change key")

    // Content (size) change
    Files.write(a, byteArrayOf(1, 2, 3, 9))
    val keyAfterContent = cache.classpathKey(listOf(a, b))
    assertNotEquals(key1, keyAfterContent, "size change must change key")

    // mtime change (different from above; reset content first)
    Files.write(a, byteArrayOf(1, 2, 3))
    val baseline = cache.classpathKey(listOf(a, b))
    val newMtime =
      java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(a).toMillis() + 5000)
    Files.setLastModifiedTime(a, newMtime)
    val keyAfterMtime = cache.classpathKey(listOf(a, b))
    assertNotEquals(baseline, keyAfterMtime, "mtime change must change key")

    // Hex format
    assertEquals(32, key1.hex.length, "key must be 32-char hex (16 bytes)")
    assertTrue(key1.hex.all { it.isDigit() || it in 'a'..'f' }, "key must be lowercase hex")
  }

  @Test
  fun `lookupAndPlace returns Empty when cache is cold`() {
    val workDir = Files.createTempDirectory("shrunk-empty-")
    val cacheDir = workDir.resolve("cache").apply { createDirectories() }
    val a = workDir.resolve("a.jar").apply { Files.write(this, byteArrayOf(1)) }
    val destDir = workDir.resolve("dest").apply { createDirectories() }
    val destination = destDir.resolve("shrunk.bin")

    val metrics = RecordingMetricsSink()
    val cache = ShrunkClasspathSnapshotCache(cacheDir, metrics)

    val outcome =
      cache.lookupAndPlace(listOf(a), destination).getOrThrow { error("unexpected: $it") }
    assertEquals(ShrunkClasspathSnapshotCache.PlacementOutcome.Empty, outcome)
    assertTrue(!Files.exists(destination), "destination must be unchanged on Empty")

    val events = metrics.snapshot()
    assertEquals(1, events.count { it.first == "shrunk_cache.lookup.miss" })
  }

  @Test
  fun `lookupAndPlace returns Placed and creates fresh inode on exact match`() {
    val workDir = Files.createTempDirectory("shrunk-placed-")
    val cacheDir = workDir.resolve("cache").apply { createDirectories() }
    val a = workDir.resolve("a.jar").apply { Files.write(this, byteArrayOf(1, 2)) }
    val destDir = workDir.resolve("dest").apply { createDirectories() }
    val destination = destDir.resolve("shrunk.bin")
    val producedSnapshot =
      destDir.resolve("source.bin").apply {
        Files.write(this, byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()))
      }

    val metrics = RecordingMetricsSink()
    val cache = ShrunkClasspathSnapshotCache(cacheDir, metrics)

    // Seed the cache
    cache.storeIfNew(listOf(a), producedSnapshot).getOrThrow { error("store failed: $it") }
    metrics.reset()

    val outcome =
      cache.lookupAndPlace(listOf(a), destination).getOrThrow { error("unexpected: $it") }
    assertTrue(
      outcome is ShrunkClasspathSnapshotCache.PlacementOutcome.Placed,
      "expected Placed, got $outcome",
    )
    assertTrue(Files.exists(destination), "destination must exist after Placed")

    // Content equality
    assertTrue(
      producedSnapshot.toFile().readBytes().contentEquals(destination.toFile().readBytes())
    )

    // Verify destination is a fresh inode (not a hardlink to the cache file)
    val cacheFile =
      cacheDir.resolve(
        "${(outcome as ShrunkClasspathSnapshotCache.PlacementOutcome.Placed).sourceKey.hex}.bin"
      )
    val cacheIno = Files.readAttributes(cacheFile, BasicFileAttributes::class.java).fileKey()
    val destIno = Files.readAttributes(destination, BasicFileAttributes::class.java).fileKey()
    assertNotEquals(cacheIno, destIno, "destination must be a fresh inode (copy, not hardlink)")

    val events = metrics.snapshot()
    assertEquals(1, events.count { it.first == "shrunk_cache.lookup.hit" })
  }

  @Test
  fun `lookupAndPlace returns PlacedPrefix on longest prefix match`() {
    val workDir = Files.createTempDirectory("shrunk-prefix-")
    val cacheDir = workDir.resolve("cache").apply { createDirectories() }
    val j1 = workDir.resolve("j1.jar").apply { Files.write(this, byteArrayOf(1)) }
    val j2 = workDir.resolve("j2.jar").apply { Files.write(this, byteArrayOf(2)) }
    val j3 = workDir.resolve("j3.jar").apply { Files.write(this, byteArrayOf(3)) }
    val j4 = workDir.resolve("j4.jar").apply { Files.write(this, byteArrayOf(4)) }
    val destDir = workDir.resolve("dest").apply { createDirectories() }

    val metrics = RecordingMetricsSink()
    val cache = ShrunkClasspathSnapshotCache(cacheDir, metrics)

    // Seed cache with prefixes of lengths 1 and 2 (shorter and longer)
    val snap1 = destDir.resolve("snap1.bin").apply { Files.write(this, byteArrayOf(11)) }
    val snap2 = destDir.resolve("snap2.bin").apply { Files.write(this, byteArrayOf(22)) }
    cache.storeIfNew(listOf(j1), snap1).getOrThrow { error("store1 failed: $it") }
    cache.storeIfNew(listOf(j1, j2), snap2).getOrThrow { error("store2 failed: $it") }
    metrics.reset()

    // Look up [j1, j2, j3, j4] — exact miss; longest prefix is [j1, j2] (length 2)
    val destination = destDir.resolve("placed.bin")
    val outcome =
      cache.lookupAndPlace(listOf(j1, j2, j3, j4), destination).getOrThrow {
        error("unexpected: $it")
      }

    assertTrue(
      outcome is ShrunkClasspathSnapshotCache.PlacementOutcome.PlacedPrefix,
      "expected PlacedPrefix, got $outcome",
    )
    val placed = outcome as ShrunkClasspathSnapshotCache.PlacementOutcome.PlacedPrefix
    assertEquals(2, placed.prefixLen, "longest matching prefix should be length 2")

    assertTrue(Files.exists(destination))
    assertTrue(snap2.toFile().readBytes().contentEquals(destination.toFile().readBytes()))

    val events = metrics.snapshot()
    assertEquals(1, events.count { it.first == "shrunk_cache.lookup.prefix_hit" })
  }

  @Test
  fun `lookupAndPlace bounds prefix search depth`() {
    val workDir = Files.createTempDirectory("shrunk-prefix-depth-")
    val cacheDir = workDir.resolve("cache").apply { createDirectories() }
    val destDir = workDir.resolve("dest").apply { createDirectories() }

    // Build a 12-entry classpath
    val entries =
      (1..12).map { i ->
        workDir.resolve("e$i.jar").apply { Files.write(this, byteArrayOf(i.toByte())) }
      }

    val metrics = RecordingMetricsSink()
    val cache = ShrunkClasspathSnapshotCache(cacheDir, metrics)

    // Seed with only the very-short prefix (length 1) — outside the bounded
    // depth window of 8. The lookup should NOT find it.
    val snap1 = destDir.resolve("snap1.bin").apply { Files.write(this, byteArrayOf(99)) }
    cache.storeIfNew(listOf(entries[0]), snap1).getOrThrow { error("seed failed: $it") }
    metrics.reset()

    val destination = destDir.resolve("placed.bin")
    val outcome = cache.lookupAndPlace(entries, destination).getOrThrow { error("unexpected: $it") }

    assertEquals(
      ShrunkClasspathSnapshotCache.PlacementOutcome.Empty,
      outcome,
      "prefix search must not find prefixes beyond the bounded depth (8)",
    )
    assertTrue(!Files.exists(destination))

    val events = metrics.snapshot()
    assertEquals(1, events.count { it.first == "shrunk_cache.lookup.miss" })
  }

  @Test
  fun `storeIfNew writes atomically and is idempotent`() {
    val workDir = Files.createTempDirectory("shrunk-store-idem-")
    val cacheDir = workDir.resolve("cache").apply { createDirectories() }
    val a = workDir.resolve("a.jar").apply { Files.write(this, byteArrayOf(1)) }
    val producedSnapshot =
      workDir.resolve("produced.bin").apply { Files.write(this, byteArrayOf(7, 8, 9)) }

    val metrics = RecordingMetricsSink()
    val cache = ShrunkClasspathSnapshotCache(cacheDir, metrics)

    cache.storeIfNew(listOf(a), producedSnapshot).getOrThrow { error("first store failed: $it") }
    val key = cache.classpathKey(listOf(a))
    val cached = cacheDir.resolve("${key.hex}.bin")
    assertTrue(Files.exists(cached), "cache file must exist after first store")

    val firstAttrs = Files.readAttributes(cached, BasicFileAttributes::class.java)
    val firstIno = firstAttrs.fileKey()
    val firstMtime = firstAttrs.lastModifiedTime().toMillis()
    val firstSize = firstAttrs.size()

    metrics.reset()

    // Second call: target exists with identical size+mtime; should skip.
    cache.storeIfNew(listOf(a), producedSnapshot).getOrThrow { error("second store failed: $it") }
    val secondAttrs = Files.readAttributes(cached, BasicFileAttributes::class.java)
    assertEquals(firstSize, secondAttrs.size(), "size must be unchanged after idempotent skip")
    assertEquals(
      firstMtime,
      secondAttrs.lastModifiedTime().toMillis(),
      "mtime must be unchanged on skip",
    )
    assertEquals(firstIno, secondAttrs.fileKey(), "inode must be the same on skip")

    // No tmp leftover
    val leftovers =
      Files.list(cacheDir).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".tmp") }.toList()
      }
    assertEquals(emptyList<Path>(), leftovers, "no .tmp leftovers after store")

    val events = metrics.snapshot()
    assertEquals(1, events.count { it.first == "shrunk_cache.store.skip" })
    assertEquals(0, events.count { it.first == "shrunk_cache.store.success" })
    assertEquals(0, events.count { it.first == "shrunk_cache.store.failure" })
  }

  @Test
  fun `storeIfNew records success metric on first write`() {
    val workDir = Files.createTempDirectory("shrunk-store-success-")
    val cacheDir = workDir.resolve("cache").apply { createDirectories() }
    val a = workDir.resolve("a.jar").apply { Files.write(this, byteArrayOf(1)) }
    val producedSnapshot =
      workDir.resolve("produced.bin").apply { Files.write(this, byteArrayOf(7, 8, 9)) }

    val metrics = RecordingMetricsSink()
    val cache = ShrunkClasspathSnapshotCache(cacheDir, metrics)

    cache.storeIfNew(listOf(a), producedSnapshot).getOrThrow { error("store failed: $it") }

    val events = metrics.snapshot()
    assertEquals(1, events.count { it.first == "shrunk_cache.store.success" })
    assertEquals(0, events.count { it.first == "shrunk_cache.store.skip" })
    assertEquals(0, events.count { it.first == "shrunk_cache.store.failure" })
  }

  @Test
  fun `cacheError returns Result Err and does not throw on store IO failure`() {
    val workDir = Files.createTempDirectory("shrunk-err-store-")
    val a = workDir.resolve("a.jar").apply { Files.write(this, byteArrayOf(1)) }
    val producedSnapshot =
      workDir.resolve("produced.bin").apply { Files.write(this, byteArrayOf(7)) }

    // Use a regular file as the "cache dir" so directory creation/file
    // operations underneath fail with IOException.
    val notADir = workDir.resolve("not-a-dir").apply { Files.write(this, byteArrayOf(0)) }

    val metrics = RecordingMetricsSink()
    val cache = ShrunkClasspathSnapshotCache(notADir, metrics)

    val result = cache.storeIfNew(listOf(a), producedSnapshot)
    val err = result.getError() ?: fail("expected Err, got Ok")
    assertTrue(
      err is ShrunkClasspathSnapshotCache.CacheError.IoFailure,
      "expected IoFailure, got $err",
    )

    val events = metrics.snapshot()
    assertEquals(1, events.count { it.first == "shrunk_cache.store.failure" })
  }

  @Test
  fun `lookupAndPlace tolerates missing cache dir without throwing`() {
    val workDir = Files.createTempDirectory("shrunk-missing-cachedir-")
    val a = workDir.resolve("a.jar").apply { Files.write(this, byteArrayOf(1)) }
    // Cache dir does not exist on disk yet — common at daemon startup.
    val cacheDir = workDir.resolve("never-created")
    val destDir = workDir.resolve("dest").apply { createDirectories() }
    val destination = destDir.resolve("placed.bin")

    val cache = ShrunkClasspathSnapshotCache(cacheDir)
    // Must NOT throw and must report Empty (treat missing dir as cold).
    val outcome =
      cache.lookupAndPlace(listOf(a), destination).getOrThrow { error("unexpected: $it") }
    assertEquals(ShrunkClasspathSnapshotCache.PlacementOutcome.Empty, outcome)
  }

  @Test
  fun `lookupAndPlace logs info-level outcomes for IT log scrape`() {
    val workDir = Files.createTempDirectory("shrunk-log-lookup-")
    val cacheDir = workDir.resolve("cache").apply { createDirectories() }
    val a = workDir.resolve("a.jar").apply { Files.write(this, byteArrayOf(1)) }
    val destDir = workDir.resolve("dest").apply { createDirectories() }
    val destination = destDir.resolve("placed.bin")

    val captured = java.io.ByteArrayOutputStream()
    val origErr = System.err
    System.setErr(PrintStream(captured, true, Charsets.UTF_8))
    try {
      val cache = ShrunkClasspathSnapshotCache(cacheDir)
      cache.lookupAndPlace(listOf(a), destination).getOrThrow { error("unexpected: $it") }
    } finally {
      System.setErr(origErr)
    }

    val output = captured.toString(Charsets.UTF_8)
    assertTrue(
      output.contains("shrunk_cache.lookup=miss"),
      "expected info-level log line for lookup outcome, got: $output",
    )
  }

  @Test
  fun `storeIfNew logs info-level outcomes for IT log scrape`() {
    val workDir = Files.createTempDirectory("shrunk-log-store-")
    val cacheDir = workDir.resolve("cache").apply { createDirectories() }
    val a = workDir.resolve("a.jar").apply { Files.write(this, byteArrayOf(1)) }
    val producedSnapshot =
      workDir.resolve("produced.bin").apply { Files.write(this, byteArrayOf(7)) }

    val captured = java.io.ByteArrayOutputStream()
    val origErr = System.err
    System.setErr(PrintStream(captured, true, Charsets.UTF_8))
    try {
      val cache = ShrunkClasspathSnapshotCache(cacheDir)
      cache.storeIfNew(listOf(a), producedSnapshot).getOrThrow { error("store failed: $it") }
    } finally {
      System.setErr(origErr)
    }

    val output = captured.toString(Charsets.UTF_8)
    assertTrue(
      output.contains("shrunk_cache.store=success"),
      "expected info-level log line for store outcome, got: $output",
    )
  }

  private class RecordingMetricsSink : IcMetricsSink {
    private val events: MutableList<Pair<String, Long>> = mutableListOf()

    override fun record(name: String, value: Long) {
      events += name to value
    }

    fun snapshot(): List<Pair<String, Long>> = events.toList()

    fun reset() {
      events.clear()
    }
  }
}
