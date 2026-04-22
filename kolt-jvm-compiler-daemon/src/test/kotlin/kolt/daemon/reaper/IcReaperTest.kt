package kolt.daemon.reaper

import kolt.daemon.ic.IcMetricsSink
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IcReaperTest {

    private lateinit var icRoot: Path
    private lateinit var otherTmp: Path
    private lateinit var metrics: RecordingMetricsSink

    @BeforeTest
    fun setUp() {
        icRoot = Files.createTempDirectory("kolt-ic-reaper-root-")
        otherTmp = Files.createTempDirectory("kolt-ic-reaper-project-")
        metrics = RecordingMetricsSink()
    }

    @AfterTest
    fun tearDown() {
        runCatching { icRoot.toFile().deleteRecursively() }
        runCatching { otherTmp.toFile().deleteRecursively() }
    }

    @Test
    fun `empty icRoot returns an empty report`() {
        val report = IcReaper.run(icRoot, "2.3.20", metrics)

        assertEquals(0, report.scanned)
        assertEquals(0, report.removed)
        assertEquals(0, report.skippedLocked)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun `missing icRoot returns an empty report`() {
        val nonexistent = icRoot.resolve("does-not-exist")
        val report = IcReaper.run(nonexistent, "2.3.20", metrics)

        assertEquals(0, report.scanned)
        assertEquals(0, report.removed)
    }

    @Test
    fun `Rule A removes stale version segments wholesale`() {
        val stale = icRoot.resolve("2.3.19").resolve("aaaaaaaaaaaaaaaa").createDirectories()
        stale.resolve("state.bin").writeText("garbage")
        val current = icRoot.resolve("2.3.20").resolve("bbbbbbbbbbbbbbbb").createDirectories()
        current.resolve("project.path").writeText(otherTmp.toString())

        val report = IcReaper.run(icRoot, "2.3.20", metrics)

        assertFalse(Files.exists(icRoot.resolve("2.3.19")), "stale version segment must be gone")
        assertTrue(Files.exists(current), "current-version projectId dir must survive")
        assertEquals(1, report.removed)
    }

    @Test
    fun `Rule B removes projectId when breadcrumb points to a missing path`() {
        val missing = otherTmp.resolve("deleted-project")
        val projectId = icRoot.resolve("2.3.20").resolve("cccccccccccccccc").createDirectories()
        projectId.resolve("project.path").writeText(missing.toString())
        projectId.resolve("state.bin").writeText("garbage")

        val report = IcReaper.run(icRoot, "2.3.20", metrics)

        assertFalse(Files.exists(projectId), "projectId with dangling breadcrumb must be removed")
        assertEquals(1, report.removed)
    }

    @Test
    fun `Rule B keeps projectId when breadcrumb points to an existing path`() {
        val projectId = icRoot.resolve("2.3.20").resolve("dddddddddddddddd").createDirectories()
        projectId.resolve("project.path").writeText(otherTmp.toString())

        val report = IcReaper.run(icRoot, "2.3.20", metrics)

        assertTrue(Files.exists(projectId), "projectId with live breadcrumb must survive")
        assertEquals(0, report.removed)
    }

    @Test
    fun `Rule B removes projectId when breadcrumb is missing entirely`() {
        val orphan = icRoot.resolve("2.3.20").resolve("eeeeeeeeeeeeeeee").createDirectories()
        orphan.resolve("state.bin").writeText("orphan")

        val report = IcReaper.run(icRoot, "2.3.20", metrics)

        assertFalse(Files.exists(orphan), "projectId without breadcrumb must be removed")
        assertEquals(1, report.removed)
    }

    @Test
    fun `Rule B removes projectId when breadcrumb content is empty`() {
        val corrupt = icRoot.resolve("2.3.20").resolve("7777777777777777").createDirectories()
        corrupt.resolve("project.path").writeText("")

        val report = IcReaper.run(icRoot, "2.3.20", metrics)

        assertFalse(Files.exists(corrupt), "projectId with empty breadcrumb must be removed")
        assertEquals(1, report.removed)
    }

    @Test
    fun `Rule B skips projectId when LOCK is held by another channel`() {
        val missing = otherTmp.resolve("deleted-project")
        val projectId = icRoot.resolve("2.3.20").resolve("ffffffffffffffff").createDirectories()
        projectId.resolve("project.path").writeText(missing.toString())
        val lockPath = projectId.resolve("LOCK")
        Files.createFile(lockPath)

        val channel = FileChannel.open(
            lockPath,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        )
        val lock = channel.tryLock() ?: error("test could not acquire LOCK on $lockPath")
        try {
            val report = IcReaper.run(icRoot, "2.3.20", metrics)

            assertTrue(Files.exists(projectId), "locked projectId must not be removed")
            assertEquals(1, report.skippedLocked)
            assertEquals(0, report.removed)
        } finally {
            lock.release()
            channel.close()
        }
    }

    @Test
    fun `regular files directly under icRoot are ignored without crashing the scan`() {
        Files.writeString(icRoot.resolve("README.txt"), "accidentally placed here")
        icRoot.resolve("2.3.20").resolve("aaaaaaaaaaaaaaaa").createDirectories()
            .resolve("project.path").writeText(otherTmp.toString())

        val report = IcReaper.run(icRoot, "2.3.20", metrics)

        assertTrue(Files.exists(icRoot.resolve("README.txt")), "stray file must survive")
        assertEquals(1, report.scanned)
        assertEquals(0, report.removed)
    }

    @Test
    fun `run emits all counter names through the metrics sink on a mixed fixture`() {
        val stale = icRoot.resolve("2.3.19").resolve("1111111111111111").createDirectories()
        stale.resolve("state.bin").writeText("x")

        val keep = icRoot.resolve("2.3.20").resolve("2222222222222222").createDirectories()
        keep.resolve("project.path").writeText(otherTmp.toString())

        val drop = icRoot.resolve("2.3.20").resolve("3333333333333333").createDirectories()
        drop.resolve("project.path").writeText(otherTmp.resolve("gone").toString())

        val noBreadcrumb = icRoot.resolve("2.3.20").resolve("4444444444444444").createDirectories()
        noBreadcrumb.resolve("state.bin").writeText("orphan")

        val lockedDangling = icRoot.resolve("2.3.20").resolve("5555555555555555").createDirectories()
        lockedDangling.resolve("project.path").writeText(otherTmp.resolve("also-gone").toString())
        val lockedFile = lockedDangling.resolve("LOCK")
        Files.createFile(lockedFile)
        val lockChannel = FileChannel.open(lockedFile, StandardOpenOption.READ, StandardOpenOption.WRITE)
        val heldLock = lockChannel.tryLock() ?: error("test setup: failed to hold LOCK on $lockedFile")
        try {
            val report = IcReaper.run(icRoot, "2.3.20", metrics)

            assertEquals(3, report.removed, "stale version + dangling breadcrumb + missing breadcrumb")
            assertEquals(1, report.skippedLocked, "locked dangling projectId must stay")
            assertTrue(Files.exists(keep))
            assertFalse(Files.exists(noBreadcrumb), "projectId without breadcrumb must be removed")
            assertTrue(Files.exists(lockedDangling), "locked projectId must not be removed even when breadcrumb is stale")
            assertFalse(Files.exists(stale.parent))

            assertTrue(metrics.records.any { it.name == "reaper.scanned" })
            assertTrue(metrics.records.any { it.name == "reaper.removed" && it.value == 3L })
            assertTrue(metrics.records.any { it.name == "reaper.skipped_locked" && it.value == 1L })
        } finally {
            heldLock.release()
            lockChannel.close()
        }
    }

    private class RecordingMetricsSink : IcMetricsSink {
        data class Record(val name: String, val value: Long)
        val records = mutableListOf<Record>()
        override fun record(name: String, value: Long) {
            records += Record(name, value)
        }
    }
}
