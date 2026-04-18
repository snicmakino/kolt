package kolt.build.daemon

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.config.KoltConfig
import kolt.config.MAVEN_CENTRAL_BASE
import kolt.infra.DownloadError
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kolt.resolve.Lockfile
import kolt.resolve.ResolveError
import kolt.resolve.ResolveResult
import kolt.resolve.ResolvedDep
import kolt.resolve.ResolverDeps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class BtaImplFetcherTest {

    private val cacheBase = "/fake/home/.kolt/cache"
    private val unusedDeps = object : ResolverDeps {
        override fun fileExists(path: String): Boolean = error("must not be called")
        override fun ensureDirectoryRecursive(path: String) = error("must not be called")
        override fun downloadFile(url: String, destPath: String) = error("must not be called")
        override fun computeSha256(filePath: String) = error("must not be called")
        override fun readFileContent(path: String) = error("must not be called")
    }

    @Test
    fun returnsResolvedJarPathsOnSuccess() {
        val resolved = listOf(
            ResolvedDep("org.jetbrains.kotlin:kotlin-build-tools-impl", "2.3.10", "h1", "$cacheBase/a.jar"),
            ResolvedDep("org.jetbrains.kotlin:kotlin-build-tools-api", "2.3.10", "h2", "$cacheBase/b.jar", transitive = true),
        )
        val result = ensureBtaImplJars(
            version = "2.3.10",
            cacheBase = cacheBase,
            deps = unusedDeps,
            resolver = { _, _, _, _ -> Ok(ResolveResult(resolved, lockChanged = true)) },
        )
        assertEquals(listOf("$cacheBase/a.jar", "$cacheBase/b.jar"), assertNotNull(result.get()))
    }

    @Test
    fun synthesizedConfigHasKotlinBuildToolsImplDep() {
        var captured: KoltConfig? = null
        ensureBtaImplJars(
            version = "2.3.10",
            cacheBase = cacheBase,
            deps = unusedDeps,
            resolver = { config, _, _, _ ->
                captured = config
                Ok(ResolveResult(emptyList(), lockChanged = false))
            },
        )
        val config = assertNotNull(captured)
        assertEquals(
            mapOf("org.jetbrains.kotlin:kotlin-build-tools-impl" to "2.3.10"),
            config.dependencies,
        )
        assertEquals("jvm", config.build.target)
        assertEquals(MAVEN_CENTRAL_BASE, config.repositories["central"])
    }

    @Test
    fun synthesizedConfigPassesRequestedVersionAsKotlinField() {
        var captured: KoltConfig? = null
        ensureBtaImplJars(
            version = "2.3.0",
            cacheBase = cacheBase,
            deps = unusedDeps,
            resolver = { config, _, _, _ ->
                captured = config
                Ok(ResolveResult(emptyList(), lockChanged = false))
            },
        )
        assertEquals("2.3.0", assertNotNull(captured).kotlin.version)
    }

    @Test
    fun resolverErrorMapsToFetchError() {
        val cause = ResolveError.DownloadFailed(
            "org.jetbrains.kotlin:kotlin-build-tools-impl",
            DownloadError.HttpFailed("http://example/", 503),
        )
        val result = ensureBtaImplJars(
            version = "2.3.10",
            cacheBase = cacheBase,
            deps = unusedDeps,
            resolver = { _, _, _, _ -> Err(cause) },
        )
        val err = assertIs<BtaImplFetchError.ResolveFailed>(result.getError())
        assertEquals("2.3.10", err.version)
        assertEquals(cause, err.cause)
    }

    @Test
    fun emptyResolverResultMapsToResolvedEmpty() {
        val result = ensureBtaImplJars(
            version = "2.3.10",
            cacheBase = cacheBase,
            deps = unusedDeps,
            resolver = { _, _, _, _ -> Ok(ResolveResult(emptyList(), lockChanged = false)) },
        )
        val err = assertIs<BtaImplFetchError.ResolvedEmpty>(result.getError())
        assertEquals("2.3.10", err.version)
    }

    @Test
    fun resolverInvokedWithoutExistingLockfile() {
        var capturedLock: Lockfile? = Lockfile(0, "x", "x", emptyMap())
        ensureBtaImplJars(
            version = "2.3.10",
            cacheBase = cacheBase,
            deps = unusedDeps,
            resolver = { _, lock, _, _ ->
                capturedLock = lock
                Ok(ResolveResult(emptyList(), lockChanged = false))
            },
        )
        assertEquals(null, capturedLock)
    }

    @Test
    fun resolverInvokedWithProvidedCacheBaseAndDeps() {
        var capturedCache: String? = null
        var capturedDeps: ResolverDeps? = null
        val realDeps = stubDeps()
        ensureBtaImplJars(
            version = "2.3.10",
            cacheBase = cacheBase,
            deps = realDeps,
            resolver = { _, _, c, d ->
                capturedCache = c
                capturedDeps = d
                Ok(ResolveResult(emptyList(), lockChanged = false))
            },
        )
        assertEquals(cacheBase, capturedCache)
        assertEquals(realDeps, capturedDeps)
    }

    private fun stubDeps() = object : ResolverDeps {
        override fun fileExists(path: String): Boolean = false
        override fun ensureDirectoryRecursive(path: String) = Ok(Unit)
        override fun downloadFile(url: String, destPath: String) =
            Err(DownloadError.NetworkError(url, "stub"))
        override fun computeSha256(filePath: String) =
            Err(Sha256Error(filePath))
        override fun readFileContent(path: String) =
            Err(OpenFailed(path))
    }
}
