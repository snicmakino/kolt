package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kolt.infra.ListFilesFailed
import kotlin.test.Test
import kotlin.test.assertEquals

// Pins the `stopProjectDaemons` enumeration shape. Each version directory
// under `<daemonBaseDir>/<projectHash>/` can contain both a JVM daemon
// socket (`daemon.sock`, ADR 0016) AND a native daemon socket
// (`native-daemon.sock`, ADR 0024 §3). `daemon stop` must signal both.
// The legacy pre-#138 socket (`<projectHash>/daemon.sock`, no version
// segment) only ever held a JVM daemon — the native daemon did not exist
// before #170 — so only the JVM shutdown helper probes it.
class DaemonStopEnumerationTest {

    private class FakeFs(
        private val existing: Set<String> = emptySet(),
        private val subdirs: Map<String, List<String>> = emptyMap(),
    ) {
        fun exists(path: String): Boolean = path in existing
        fun list(path: String): Result<List<String>, ListFilesFailed> =
            subdirs[path]?.let(::Ok) ?: Err(ListFilesFailed(path))
    }

    @Test
    fun enumeratesBothJvmAndNativeSocketsUnderEachVersionDir() {
        val projectDir = "/home/u/.kolt/daemon/abc123"
        val fs = FakeFs(
            existing = setOf(
                "$projectDir",
                "$projectDir/2.3.20/daemon.sock",
                "$projectDir/2.3.20/native-daemon.sock",
            ),
            subdirs = mapOf(projectDir to listOf("2.3.20")),
        )
        val jvmSockets = mutableListOf<String>()
        val nativeSockets = mutableListOf<String>()

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            sendJvmShutdown = { jvmSockets += it; true },
            sendNativeShutdown = { nativeSockets += it; true },
        )

        assertEquals(2, stopped, "both sockets should be counted")
        assertEquals(listOf("$projectDir/2.3.20/daemon.sock"), jvmSockets)
        assertEquals(listOf("$projectDir/2.3.20/native-daemon.sock"), nativeSockets)
    }

    @Test
    fun versionDirWithOnlyJvmSocketCountsOnlyJvm() {
        val projectDir = "/home/u/.kolt/daemon/hash"
        val fs = FakeFs(
            existing = setOf("$projectDir", "$projectDir/2.3.20/daemon.sock"),
            subdirs = mapOf(projectDir to listOf("2.3.20")),
        )
        var nativeCalls = 0

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            sendJvmShutdown = { true },
            sendNativeShutdown = { nativeCalls++; true },
        )

        assertEquals(1, stopped)
        assertEquals(0, nativeCalls, "native send must not fire when the socket is absent")
    }

    @Test
    fun versionDirWithOnlyNativeSocketCountsOnlyNative() {
        val projectDir = "/home/u/.kolt/daemon/hash"
        val fs = FakeFs(
            existing = setOf("$projectDir", "$projectDir/2.3.20/native-daemon.sock"),
            subdirs = mapOf(projectDir to listOf("2.3.20")),
        )
        var jvmCalls = 0

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            sendJvmShutdown = { jvmCalls++; true },
            sendNativeShutdown = { true },
        )

        assertEquals(1, stopped)
        assertEquals(0, jvmCalls, "jvm send must not fire when the socket is absent")
    }

    @Test
    fun multipleVersionDirsEnumerateAllSockets() {
        val projectDir = "/home/u/.kolt/daemon/hash"
        val fs = FakeFs(
            existing = setOf(
                "$projectDir",
                "$projectDir/2.3.20/daemon.sock",
                "$projectDir/2.3.20/native-daemon.sock",
                "$projectDir/2.4.0/daemon.sock",
            ),
            subdirs = mapOf(projectDir to listOf("2.3.20", "2.4.0")),
        )

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            sendJvmShutdown = { true },
            sendNativeShutdown = { true },
        )

        assertEquals(3, stopped, "three sockets across two version dirs")
    }

    @Test
    fun legacyDaemonSockIsJvmOnly() {
        // Pre-#138 layout: a single `<projectHash>/daemon.sock`, no version
        // subdirectory. Native daemon was introduced in #170 and never
        // lived under the legacy layout, so only the JVM shutdown helper
        // is tried at this path.
        val projectDir = "/home/u/.kolt/daemon/hash"
        val fs = FakeFs(
            existing = setOf("$projectDir", "$projectDir/daemon.sock"),
            subdirs = mapOf(projectDir to emptyList()),
        )
        val jvmSockets = mutableListOf<String>()
        var nativeCalls = 0

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            sendJvmShutdown = { jvmSockets += it; true },
            sendNativeShutdown = { nativeCalls++; true },
        )

        assertEquals(1, stopped)
        assertEquals(listOf("$projectDir/daemon.sock"), jvmSockets)
        assertEquals(0, nativeCalls, "native shutdown must not probe the legacy layout")
    }

    @Test
    fun failingSendDoesNotCount() {
        val projectDir = "/home/u/.kolt/daemon/hash"
        val fs = FakeFs(
            existing = setOf(
                "$projectDir",
                "$projectDir/2.3.20/daemon.sock",
                "$projectDir/2.3.20/native-daemon.sock",
            ),
            subdirs = mapOf(projectDir to listOf("2.3.20")),
        )

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            sendJvmShutdown = { false },
            sendNativeShutdown = { true },
        )

        assertEquals(1, stopped, "only successful sends should count")
    }

    @Test
    fun jvmSuccessDoesNotCoverForNativeFailureInSameVersionDir() {
        // Each arm reports independently — a half-dead daemon pair should
        // count as 1, not 2. Mirrors `failingSendDoesNotCount` but with
        // the opposite failure half so the pair together pin "count only
        // the arm that actually sent" on both legs.
        val projectDir = "/home/u/.kolt/daemon/hash"
        val fs = FakeFs(
            existing = setOf(
                "$projectDir",
                "$projectDir/2.3.20/daemon.sock",
                "$projectDir/2.3.20/native-daemon.sock",
            ),
            subdirs = mapOf(projectDir to listOf("2.3.20")),
        )
        val jvmSockets = mutableListOf<String>()
        val nativeSockets = mutableListOf<String>()

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            sendJvmShutdown = { jvmSockets += it; true },
            sendNativeShutdown = { nativeSockets += it; false },
        )

        assertEquals(1, stopped)
        assertEquals(listOf("$projectDir/2.3.20/daemon.sock"), jvmSockets)
        assertEquals(listOf("$projectDir/2.3.20/native-daemon.sock"), nativeSockets)
    }

    @Test
    fun nonExistentProjectDirReturnsZero() {
        val fs = FakeFs(existing = emptySet())

        val stopped = stopProjectDaemons(
            projectDir = "/nowhere",
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            sendJvmShutdown = { error("must not send") },
            sendNativeShutdown = { error("must not send") },
        )

        assertEquals(0, stopped)
    }
}
