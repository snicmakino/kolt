package kolt.cli

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kolt.infra.ListFilesFailed
import kotlin.test.Test
import kotlin.test.assertEquals

// Pins the `stopProjectDaemons` enumeration shape. Each version directory
// under `<daemonBaseDir>/<projectHash>/` can contain both a JVM daemon
// socket (`jvm-compiler-daemon.sock` / `daemon-<fp>.sock`, ADR 0016 + #138 plugin
// fingerprint) AND a native daemon socket (`native-compiler-daemon.sock`,
// ADR 0024 §3). `daemon stop` must signal all of them.
class DaemonStopEnumerationTest {

    private class FakeFs(
        private val existing: Set<String> = emptySet(),
        private val subdirs: Map<String, List<String>> = emptyMap(),
        private val files: Map<String, List<String>> = emptyMap(),
    ) {
        fun exists(path: String): Boolean = path in existing
        fun list(path: String): Result<List<String>, ListFilesFailed> =
            subdirs[path]?.let(::Ok) ?: Err(ListFilesFailed(path))
        fun listFiles(path: String): Result<List<String>, ListFilesFailed> =
            files[path]?.let(::Ok) ?: Err(ListFilesFailed(path))
    }

    @Test
    fun enumeratesBothJvmAndNativeSocketsUnderEachVersionDir() {
        val projectDir = "/home/u/.kolt/daemon/abc123"
        val fs = FakeFs(
            existing = setOf(projectDir),
            subdirs = mapOf(projectDir to listOf("2.3.20")),
            files = mapOf("$projectDir/2.3.20" to listOf("jvm-compiler-daemon.sock", "native-compiler-daemon.sock")),
        )
        val jvmSockets = mutableListOf<String>()
        val nativeSockets = mutableListOf<String>()

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            listFiles = fs::listFiles,
            sendJvmShutdown = { jvmSockets += it; true },
            sendNativeShutdown = { nativeSockets += it; true },
        )

        assertEquals(2, stopped, "both sockets should be counted")
        assertEquals(listOf("$projectDir/2.3.20/jvm-compiler-daemon.sock"), jvmSockets)
        assertEquals(listOf("$projectDir/2.3.20/native-compiler-daemon.sock"), nativeSockets)
    }

    @Test
    fun enumeratesFingerprintedJvmSocket() {
        // #138 plugin fingerprint: `applyPluginsFingerprintToFile` rewrites
        // `jvm-compiler-daemon.sock` → `jvm-compiler-daemon-noplugins.sock` (no plugins) or
        // `jvm-compiler-daemon-<8hex>.sock`. Before #181 these were invisible to `stop`.
        val projectDir = "/home/u/.kolt/daemon/hash"
        val fs = FakeFs(
            existing = setOf(projectDir),
            subdirs = mapOf(projectDir to listOf("2.3.20")),
            files = mapOf("$projectDir/2.3.20" to listOf("jvm-compiler-daemon-noplugins.sock")),
        )
        val jvmSockets = mutableListOf<String>()

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            listFiles = fs::listFiles,
            sendJvmShutdown = { jvmSockets += it; true },
            sendNativeShutdown = { error("must not send") },
        )

        assertEquals(1, stopped)
        assertEquals(listOf("$projectDir/2.3.20/jvm-compiler-daemon-noplugins.sock"), jvmSockets)
    }

    @Test
    fun enumeratesMultipleFingerprintedJvmSocketsInOneVersionDir() {
        // A project can have several plugin configurations coexisting
        // (e.g. test compile vs main compile) → multiple fingerprinted
        // daemons under the same version dir. Stop must hit all of them.
        val projectDir = "/home/u/.kolt/daemon/hash"
        val fs = FakeFs(
            existing = setOf(projectDir),
            subdirs = mapOf(projectDir to listOf("2.3.20")),
            files = mapOf(
                "$projectDir/2.3.20" to listOf(
                    "jvm-compiler-daemon-abcd1234.sock",
                    "jvm-compiler-daemon-noplugins.sock",
                ),
            ),
        )
        val jvmSockets = mutableListOf<String>()

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            listFiles = fs::listFiles,
            sendJvmShutdown = { jvmSockets += it; true },
            sendNativeShutdown = { error("must not send") },
        )

        assertEquals(2, stopped)
        assertEquals(
            listOf(
                "$projectDir/2.3.20/jvm-compiler-daemon-abcd1234.sock",
                "$projectDir/2.3.20/jvm-compiler-daemon-noplugins.sock",
            ),
            jvmSockets,
        )
    }

    @Test
    fun nativeDaemonSockIsNotMatchedByJvmEnumeration() {
        // `native-compiler-daemon.sock` starts with `native-`, not `daemon-`, so the
        // JVM fingerprint pattern must not pick it up. A native-only
        // version dir counts as one native shutdown, zero JVM.
        val projectDir = "/home/u/.kolt/daemon/hash"
        val fs = FakeFs(
            existing = setOf(projectDir),
            subdirs = mapOf(projectDir to listOf("2.3.20")),
            files = mapOf("$projectDir/2.3.20" to listOf("native-compiler-daemon.sock")),
        )
        val nativeSockets = mutableListOf<String>()

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            listFiles = fs::listFiles,
            sendJvmShutdown = { error("must not send — `native-compiler-daemon.sock` is not a JVM daemon") },
            sendNativeShutdown = { nativeSockets += it; true },
        )

        assertEquals(1, stopped)
        assertEquals(listOf("$projectDir/2.3.20/native-compiler-daemon.sock"), nativeSockets)
    }

    @Test
    fun versionDirWithOnlyJvmSocketCountsOnlyJvm() {
        val projectDir = "/home/u/.kolt/daemon/hash"
        val fs = FakeFs(
            existing = setOf(projectDir),
            subdirs = mapOf(projectDir to listOf("2.3.20")),
            files = mapOf("$projectDir/2.3.20" to listOf("jvm-compiler-daemon.sock")),
        )

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            listFiles = fs::listFiles,
            sendJvmShutdown = { true },
            sendNativeShutdown = { error("must not send") },
        )

        assertEquals(1, stopped)
    }

    @Test
    fun versionDirWithOnlyNativeSocketCountsOnlyNative() {
        val projectDir = "/home/u/.kolt/daemon/hash"
        val fs = FakeFs(
            existing = setOf(projectDir),
            subdirs = mapOf(projectDir to listOf("2.3.20")),
            files = mapOf("$projectDir/2.3.20" to listOf("native-compiler-daemon.sock")),
        )

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            listFiles = fs::listFiles,
            sendJvmShutdown = { error("must not send") },
            sendNativeShutdown = { true },
        )

        assertEquals(1, stopped)
    }

    @Test
    fun multipleVersionDirsEnumerateAllSockets() {
        val projectDir = "/home/u/.kolt/daemon/hash"
        val fs = FakeFs(
            existing = setOf(projectDir),
            subdirs = mapOf(projectDir to listOf("2.3.20", "2.4.0")),
            files = mapOf(
                "$projectDir/2.3.20" to listOf("jvm-compiler-daemon.sock", "native-compiler-daemon.sock"),
                "$projectDir/2.4.0" to listOf("jvm-compiler-daemon-noplugins.sock"),
            ),
        )

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            listFiles = fs::listFiles,
            sendJvmShutdown = { true },
            sendNativeShutdown = { true },
        )

        assertEquals(3, stopped, "three sockets across two version dirs")
    }

    @Test
    fun failingSendDoesNotCount() {
        val projectDir = "/home/u/.kolt/daemon/hash"
        val fs = FakeFs(
            existing = setOf(projectDir),
            subdirs = mapOf(projectDir to listOf("2.3.20")),
            files = mapOf("$projectDir/2.3.20" to listOf("jvm-compiler-daemon.sock", "native-compiler-daemon.sock")),
        )

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            listFiles = fs::listFiles,
            sendJvmShutdown = { false },
            sendNativeShutdown = { true },
        )

        assertEquals(1, stopped, "only successful sends should count")
    }

    @Test
    fun jvmSuccessDoesNotCoverForNativeFailureInSameVersionDir() {
        val projectDir = "/home/u/.kolt/daemon/hash"
        val fs = FakeFs(
            existing = setOf(projectDir),
            subdirs = mapOf(projectDir to listOf("2.3.20")),
            files = mapOf("$projectDir/2.3.20" to listOf("jvm-compiler-daemon.sock", "native-compiler-daemon.sock")),
        )
        val jvmSockets = mutableListOf<String>()
        val nativeSockets = mutableListOf<String>()

        val stopped = stopProjectDaemons(
            projectDir = projectDir,
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            listFiles = fs::listFiles,
            sendJvmShutdown = { jvmSockets += it; true },
            sendNativeShutdown = { nativeSockets += it; false },
        )

        assertEquals(1, stopped)
        assertEquals(listOf("$projectDir/2.3.20/jvm-compiler-daemon.sock"), jvmSockets)
        assertEquals(listOf("$projectDir/2.3.20/native-compiler-daemon.sock"), nativeSockets)
    }

    @Test
    fun isJvmDaemonSocketPredicateShape() {
        // `applyPluginsFingerprintToFile` never emits an empty fingerprint
        // (`pluginsFingerprint` returns "noplugins" or 8 hex chars), so
        // `jvm-compiler-daemon-.sock` is not a real on-disk name — but the predicate
        // stays strict to survive a future refactor of the fingerprint
        // source.
        assertEquals(true, isJvmDaemonSocket("jvm-compiler-daemon.sock"))
        assertEquals(true, isJvmDaemonSocket("jvm-compiler-daemon-noplugins.sock"))
        assertEquals(true, isJvmDaemonSocket("jvm-compiler-daemon-abcd1234.sock"))
        assertEquals(false, isJvmDaemonSocket("jvm-compiler-daemon-.sock"))
        assertEquals(false, isJvmDaemonSocket("jvm-compiler-daemon.log"))
        assertEquals(false, isJvmDaemonSocket("jvm-compiler-daemon-noplugins.log"))
        assertEquals(false, isJvmDaemonSocket("native-compiler-daemon.sock"))
        assertEquals(false, isJvmDaemonSocket("Daemon.sock"))
    }

    @Test
    fun nonExistentProjectDirReturnsZero() {
        val fs = FakeFs(existing = emptySet())

        val stopped = stopProjectDaemons(
            projectDir = "/nowhere",
            fileExists = fs::exists,
            listSubdirectories = fs::list,
            listFiles = fs::listFiles,
            sendJvmShutdown = { error("must not send") },
            sendNativeShutdown = { error("must not send") },
        )

        assertEquals(0, stopped)
    }
}
