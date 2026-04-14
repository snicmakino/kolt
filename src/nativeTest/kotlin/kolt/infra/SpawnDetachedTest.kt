package kolt.infra

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpawnDetachedTest {

    private val scratch = "/tmp/kolt_spawn_detached_test"

    @AfterTest
    fun cleanup() {
        deleteFile("$scratch.log")
        deleteFile("$scratch.marker")
    }

    @Test
    fun emptyArgsIsRejectedBeforeFork() {
        val err = spawnDetached(emptyList()).getError()
        assertEquals(ProcessError.EmptyArgs, err)
    }

    @Test
    fun spawnRunsGrandchildAndReturnsOkImmediately() {
        // /bin/sh -c "echo ok > $scratch.marker" writes a file and
        // exits. The parent reaps only the intermediate child, so the
        // call returns as soon as the double-fork finishes — the
        // marker file is written by the detached grandchild at some
        // point after that. A short polling loop waits for the
        // grandchild to reach the write.
        val result = spawnDetached(
            listOf("/bin/sh", "-c", "echo ok > $scratch.marker"),
        )
        assertNull(result.getError(), "spawn failed: ${result.getError()}")
        // Poll up to 2 seconds for the detached write. CI jitter on
        // busy runners can push fork+exec latency past a few hundred
        // milliseconds, so a tight budget flakes.
        val deadline = kotlin.time.TimeSource.Monotonic.markNow()
            .plus(kotlin.time.Duration.parse("2s"))
        while (!fileExists("$scratch.marker")) {
            if (deadline.hasPassedNow()) break
            platform.posix.usleep(20_000u)
        }
        assertTrue(fileExists("$scratch.marker"), "grandchild never produced marker file")
    }

    @Test
    fun logPathCapturesStdoutAndStderrOfGrandchild() {
        // sh echoes the same marker to stdout and stderr; both must
        // land in the log file because dup2 redirects fd 1 and fd 2
        // onto the log fd.
        val logPath = "$scratch.log"
        deleteFile(logPath)
        val result = spawnDetached(
            listOf(
                "/bin/sh",
                "-c",
                "echo stdout-mark; echo stderr-mark 1>&2",
            ),
            logPath = logPath,
        )
        assertNull(result.getError(), "spawn failed: ${result.getError()}")
        val deadline = kotlin.time.TimeSource.Monotonic.markNow()
            .plus(kotlin.time.Duration.parse("2s"))
        while (true) {
            if (fileExists(logPath)) {
                val content = readFileAsString(logPath).get() ?: ""
                if (content.contains("stdout-mark") && content.contains("stderr-mark")) return
            }
            if (deadline.hasPassedNow()) break
            platform.posix.usleep(20_000u)
        }
        val content = readFileAsString(logPath).get() ?: "<missing>"
        error("log capture failed. content so far:\n$content")
    }
}
