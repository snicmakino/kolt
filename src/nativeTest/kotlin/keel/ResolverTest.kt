package keel

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ResolverTest {

    private fun configWithDeps(deps: Map<String, String>) = testConfig().copy(
        dependencies = deps
    )

    @Test
    fun resolveWithNoDependencies() {
        val config = configWithDeps(emptyMap())
        val result = resolve(config, null, "/tmp/cache", fakeDeps())
        val resolved = assertNotNull(result.get())
        assertTrue(resolved.deps.isEmpty())
        assertFalse(resolved.lockChanged)
    }

    @Test
    fun resolveDownloadsAndComputesHash() {
        val config = configWithDeps(mapOf("com.example:lib" to "1.0.0"))
        val deps = fakeDeps(
            cachedFiles = mutableSetOf(),
            downloadedFiles = mutableMapOf(),
            sha256Results = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "abc123"
            )
        )
        val result = resolve(config, null, "/tmp/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(1, resolved.deps.size)
        assertEquals("com.example:lib", resolved.deps[0].groupArtifact)
        assertEquals("1.0.0", resolved.deps[0].version)
        assertEquals("abc123", resolved.deps[0].sha256)
        assertTrue(resolved.lockChanged)
    }

    @Test
    fun resolveUsesExistingCacheAndMatchingLock() {
        val config = configWithDeps(mapOf("com.example:lib" to "1.0.0"))
        val lock = Lockfile(
            version = 1,
            kotlin = "2.1.0",
            jvmTarget = "17",
            dependencies = mapOf(
                "com.example:lib" to LockEntry("1.0.0", "abc123")
            )
        )
        val downloaded = mutableMapOf<String, String>()
        val deps = fakeDeps(
            cachedFiles = mutableSetOf("/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar"),
            downloadedFiles = downloaded,
            sha256Results = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "abc123"
            )
        )
        val result = resolve(config, lock, "/tmp/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(1, resolved.deps.size)
        assertFalse(resolved.lockChanged)
        // Verify no downloads occurred
        assertTrue(downloaded.isEmpty())
    }

    @Test
    fun resolveSha256MismatchReturnsErr() {
        val config = configWithDeps(mapOf("com.example:lib" to "1.0.0"))
        val lock = Lockfile(
            version = 1,
            kotlin = "2.1.0",
            jvmTarget = "17",
            dependencies = mapOf(
                "com.example:lib" to LockEntry("1.0.0", "expected_hash")
            )
        )
        val deps = fakeDeps(
            cachedFiles = mutableSetOf("/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar"),
            sha256Results = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "actual_different_hash"
            )
        )
        val result = resolve(config, lock, "/tmp/cache", deps)
        assertIs<ResolveError.Sha256Mismatch>(result.getError())
    }

    @Test
    fun resolveDetectsRemovedDependency() {
        val config = configWithDeps(mapOf("com.example:lib" to "1.0.0"))
        val lock = Lockfile(
            version = 1,
            kotlin = "2.1.0",
            jvmTarget = "17",
            dependencies = mapOf(
                "com.example:lib" to LockEntry("1.0.0", "abc123"),
                "com.example:removed" to LockEntry("2.0.0", "def456")
            )
        )
        val deps = fakeDeps(
            cachedFiles = mutableSetOf("/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar"),
            sha256Results = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "abc123"
            )
        )
        val result = resolve(config, lock, "/tmp/cache", deps)
        val resolved = assertNotNull(result.get())
        assertTrue(resolved.lockChanged)
    }

    @Test
    fun resolveDetectsVersionChange() {
        val config = configWithDeps(mapOf("com.example:lib" to "2.0.0"))
        val lock = Lockfile(
            version = 1,
            kotlin = "2.1.0",
            jvmTarget = "17",
            dependencies = mapOf(
                "com.example:lib" to LockEntry("1.0.0", "old_hash")
            )
        )
        val deps = fakeDeps(
            cachedFiles = mutableSetOf(),
            sha256Results = mapOf(
                "/tmp/cache/com/example/lib/2.0.0/lib-2.0.0.jar" to "new_hash"
            )
        )
        val result = resolve(config, lock, "/tmp/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals("2.0.0", resolved.deps[0].version)
        assertEquals("new_hash", resolved.deps[0].sha256)
        assertTrue(resolved.lockChanged)
    }

    @Test
    fun resolveInvalidCoordinateReturnsErr() {
        val config = configWithDeps(mapOf("invalid-no-colon" to "1.0.0"))
        val result = resolve(config, null, "/tmp/cache", fakeDeps())
        assertIs<ResolveError.InvalidDependency>(result.getError())
    }

    @Test
    fun resolveDetectsKotlinVersionChange() {
        val config = configWithDeps(mapOf("com.example:lib" to "1.0.0")).copy(kotlin = "2.2.0")
        val lock = Lockfile(
            version = 1,
            kotlin = "2.1.0",
            jvmTarget = "17",
            dependencies = mapOf(
                "com.example:lib" to LockEntry("1.0.0", "abc123")
            )
        )
        val deps = fakeDeps(
            cachedFiles = mutableSetOf("/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar"),
            sha256Results = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "abc123"
            )
        )
        val result = resolve(config, lock, "/tmp/cache", deps)
        val resolved = assertNotNull(result.get())
        assertTrue(resolved.lockChanged)
    }

    @Test
    fun resolveDetectsJvmTargetChange() {
        val config = configWithDeps(mapOf("com.example:lib" to "1.0.0")).copy(jvmTarget = "21")
        val lock = Lockfile(
            version = 1,
            kotlin = "2.1.0",
            jvmTarget = "17",
            dependencies = mapOf(
                "com.example:lib" to LockEntry("1.0.0", "abc123")
            )
        )
        val deps = fakeDeps(
            cachedFiles = mutableSetOf("/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar"),
            sha256Results = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "abc123"
            )
        )
        val result = resolve(config, lock, "/tmp/cache", deps)
        val resolved = assertNotNull(result.get())
        assertTrue(resolved.lockChanged)
    }

    @Test
    fun resolveDownloadFailureReturnsErr() {
        val config = configWithDeps(mapOf("com.example:lib" to "1.0.0"))
        val deps = object : ResolverDeps {
            override fun fileExists(path: String) = false
            override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)
            override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> =
                Err(DownloadError.HttpFailed(url, 404))
            override fun computeSha256(filePath: String): Result<String, Sha256Error> =
                Err(Sha256Error(filePath))
        }
        val result = resolve(config, null, "/tmp/cache", deps)
        val error = assertIs<ResolveError.DownloadFailed>(result.getError())
        assertEquals("com.example:lib", error.groupArtifact)
    }

    @Test
    fun buildLockfileFromResolvedDeps() {
        val config = testConfig().copy(kotlin = "2.1.0", jvmTarget = "17")
        val deps = listOf(
            ResolvedDep("com.example:lib", "1.0.0", "abc123", "/cache/lib.jar"),
            ResolvedDep("org.example:other", "2.0.0", "def456", "/cache/other.jar")
        )
        val lockfile = buildLockfileFromResolved(config, deps)
        assertEquals(1, lockfile.version)
        assertEquals("2.1.0", lockfile.kotlin)
        assertEquals("17", lockfile.jvmTarget)
        assertEquals(2, lockfile.dependencies.size)
        assertEquals(LockEntry("1.0.0", "abc123"), lockfile.dependencies["com.example:lib"])
    }

    // Fake side-effect injection for testing
    private fun fakeDeps(
        cachedFiles: MutableSet<String> = mutableSetOf(),
        downloadedFiles: MutableMap<String, String> = mutableMapOf(),
        sha256Results: Map<String, String> = emptyMap()
    ): ResolverDeps {
        return object : ResolverDeps {
            val downloadedFiles = downloadedFiles

            override fun fileExists(path: String): Boolean = path in cachedFiles

            override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> = Ok(Unit)

            override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
                downloadedFiles[destPath] = url
                cachedFiles.add(destPath)
                return Ok(Unit)
            }

            override fun computeSha256(filePath: String): Result<String, Sha256Error> {
                val hash = sha256Results[filePath]
                    ?: return Err(Sha256Error(filePath))
                return Ok(hash)
            }
        }
    }
}
