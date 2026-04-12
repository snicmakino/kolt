package kolt.resolve

import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kolt.testConfig
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
        val pomXml = simplePom("com.example", "lib", "1.0.0")
        val deps = fakeDeps(
            cachedFiles = mutableSetOf(),
            downloadedFiles = mutableMapOf(),
            sha256Results = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "abc123"
            ),
            fileContents = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to pomXml
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
        val pomXml = """
            <project><groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0.0</version></project>
        """.trimIndent()
        val downloaded = mutableMapOf<String, String>()
        val deps = fakeDeps(
            cachedFiles = mutableSetOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar",
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.pom"
            ),
            downloadedFiles = downloaded,
            sha256Results = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "abc123"
            ),
            fileContents = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to pomXml
            )
        )
        val result = resolve(config, lock, "/tmp/cache", deps)
        val resolved = assertNotNull(result.get())
        assertEquals(1, resolved.deps.size)
        assertFalse(resolved.lockChanged)
        // Verify no JAR downloads occurred (module metadata downloads are expected)
        val jarDownloads = downloaded.keys.filter { it.endsWith(".jar") }
        assertTrue(jarDownloads.isEmpty())
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
        val pomXml = simplePom("com.example", "lib", "1.0.0")
        val deps = fakeDeps(
            cachedFiles = mutableSetOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar",
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.pom"
            ),
            sha256Results = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "actual_different_hash"
            ),
            fileContents = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to pomXml
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
        val pomXml = simplePom("com.example", "lib", "1.0.0")
        val deps = fakeDeps(
            cachedFiles = mutableSetOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar",
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.pom"
            ),
            sha256Results = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "abc123"
            ),
            fileContents = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to pomXml
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
        val pomXml = simplePom("com.example", "lib", "2.0.0")
        val deps = fakeDeps(
            cachedFiles = mutableSetOf(),
            sha256Results = mapOf(
                "/tmp/cache/com/example/lib/2.0.0/lib-2.0.0.jar" to "new_hash"
            ),
            fileContents = mapOf(
                "/tmp/cache/com/example/lib/2.0.0/lib-2.0.0.pom" to pomXml
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
        val pomXml = simplePom("com.example", "lib", "1.0.0")
        val deps = fakeDeps(
            cachedFiles = mutableSetOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar",
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.pom"
            ),
            sha256Results = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "abc123"
            ),
            fileContents = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to pomXml
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
        val pomXml = simplePom("com.example", "lib", "1.0.0")
        val deps = fakeDeps(
            cachedFiles = mutableSetOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar",
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.pom"
            ),
            sha256Results = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.jar" to "abc123"
            ),
            fileContents = mapOf(
                "/tmp/cache/com/example/lib/1.0.0/lib-1.0.0.pom" to pomXml
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
            override fun readFileContent(path: String): Result<String, OpenFailed> =
                Err(OpenFailed(path))
        }
        val result = resolve(config, null, "/tmp/cache", deps)
        val error = assertIs<ResolveError.DownloadFailed>(result.getError())
        assertEquals("com.example:lib", error.groupArtifact)
    }

    @Test
    fun buildLockfileFromResolvedDeps() {
        val config = testConfig().copy(kotlin = "2.1.0", jvmTarget = "17")
        val deps = listOf(
            ResolvedDep("com.example:lib", "1.0.0", "abc123", "/cache/lib.jar", transitive = false),
            ResolvedDep("org.example:other", "2.0.0", "def456", "/cache/other.jar", transitive = true)
        )
        val lockfile = buildLockfileFromResolved(config, deps)
        assertEquals(2, lockfile.version)
        assertEquals("2.1.0", lockfile.kotlin)
        assertEquals("17", lockfile.jvmTarget)
        assertEquals(2, lockfile.dependencies.size)
        assertEquals(LockEntry("1.0.0", "abc123", transitive = false), lockfile.dependencies["com.example:lib"])
        assertEquals(LockEntry("2.0.0", "def456", transitive = true), lockfile.dependencies["org.example:other"])
    }

    private fun simplePom(group: String, artifact: String, version: String): String =
        "<project><groupId>$group</groupId><artifactId>$artifact</artifactId><version>$version</version></project>"

    // Fake side-effect injection for testing
    private fun fakeDeps(
        cachedFiles: MutableSet<String> = mutableSetOf(),
        downloadedFiles: MutableMap<String, String> = mutableMapOf(),
        sha256Results: Map<String, String> = emptyMap(),
        fileContents: Map<String, String> = emptyMap()
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

            override fun readFileContent(path: String): Result<String, OpenFailed> {
                val content = fileContents[path]
                    ?: return Err(OpenFailed(path))
                return Ok(content)
            }
        }
    }
}
