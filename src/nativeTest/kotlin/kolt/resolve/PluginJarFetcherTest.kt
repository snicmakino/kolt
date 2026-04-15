package kolt.resolve

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kolt.infra.DownloadError
import kolt.infra.MkdirFailed
import kolt.infra.OpenFailed
import kolt.infra.Sha256Error
import kolt.infra.WriteFailed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// FakeDeps records every seam call so each test can assert exactly which
// I/O the fetcher performed. Callers pre-populate `existingFiles` (cache
// contents before the call), `sha256Results` (hash that `computeSha256`
// will return for a given path), and `stampContents` (what
// `readFileAsString` will return for a given stamp path). All writes —
// downloads and stamp updates — are recorded on the same maps so a
// second lookup of the same path sees the updated view.
private class FakeFetcherDeps(
    val existingFiles: MutableSet<String> = mutableSetOf(),
    val sha256Results: MutableMap<String, String> = mutableMapOf(),
    val stampContents: MutableMap<String, String> = mutableMapOf(),
    val downloadResult: (url: String, dest: String) -> Result<Unit, DownloadError> = { _, _ -> Ok(Unit) },
) : PluginFetcherDeps {
    val downloadCalls = mutableListOf<Pair<String, String>>()
    val mkdirCalls = mutableListOf<String>()
    val stampWrites = mutableListOf<Pair<String, String>>()
    val warnings = mutableListOf<String>()

    override fun fileExists(path: String): Boolean = path in existingFiles

    override fun ensureDirectoryRecursive(path: String): Result<Unit, MkdirFailed> {
        mkdirCalls += path
        return Ok(Unit)
    }

    override fun downloadFile(url: String, destPath: String): Result<Unit, DownloadError> {
        downloadCalls += url to destPath
        val outcome = downloadResult(url, destPath)
        if (outcome.get() != null) {
            existingFiles += destPath
        }
        return outcome
    }

    override fun computeSha256(filePath: String): Result<String, Sha256Error> {
        val hash = sha256Results[filePath] ?: return Err(Sha256Error(filePath))
        return Ok(hash)
    }

    override fun readFileAsString(path: String): Result<String, OpenFailed> {
        val content = stampContents[path] ?: return Err(OpenFailed(path))
        return Ok(content)
    }

    override fun writeFileAsString(path: String, content: String): Result<Unit, WriteFailed> {
        stampWrites += path to content
        stampContents[path] = content
        existingFiles += path
        return Ok(Unit)
    }

    override fun warn(message: String) {
        warnings += message
    }
}

class PluginJarFetcherTest {

    private val cacheBase = "/home/u/.kolt/cache"
    private val kotlinVersion = "2.3.20"

    // Cache layout mirrors the existing Maven-shaped dependency cache so
    // a future `kolt clean --cache` sweep does not need to special-case
    // plugin jars. See issue #65 + discussion on coordinate shape.
    private val serializationJar =
        "$cacheBase/org/jetbrains/kotlin/kotlin-serialization-compiler-plugin/2.3.20/" +
            "kotlin-serialization-compiler-plugin-2.3.20.jar"
    private val serializationStamp = "$serializationJar.sha256"
    private val serializationUrl =
        "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-serialization-compiler-plugin/" +
            "2.3.20/kotlin-serialization-compiler-plugin-2.3.20.jar"

    @Test
    fun unknownAliasReturnsUnknownPluginError() {
        val deps = FakeFetcherDeps()
        val result = fetchPluginJar("fictitious", kotlinVersion, cacheBase, deps)

        val err = assertNotNull(result.getError())
        val unknown = assertIs<PluginFetchError.UnknownPlugin>(err)
        assertEquals("fictitious", unknown.alias)
        assertTrue(deps.downloadCalls.isEmpty(), "must not download on unknown alias")
    }

    @Test
    fun coldCacheDownloadsComputesHashAndWritesStamp() {
        val deps = FakeFetcherDeps(
            sha256Results = mutableMapOf(serializationJar to "hash-abc"),
        )

        val result = fetchPluginJar("serialization", kotlinVersion, cacheBase, deps)

        assertEquals(serializationJar, assertNotNull(result.get()))
        assertEquals(1, deps.downloadCalls.size, "expected exactly one download")
        assertEquals(serializationUrl to serializationJar, deps.downloadCalls.single())
        assertEquals(serializationStamp to "hash-abc", deps.stampWrites.single())
    }

    @Test
    fun urlIsConstructedAgainstMavenCentralWithExactCoordinateShape() {
        val deps = FakeFetcherDeps(
            sha256Results = mutableMapOf(serializationJar to "hash-abc"),
        )

        fetchPluginJar("serialization", kotlinVersion, cacheBase, deps)

        // Pin the canonical Maven Central URL shape. A typo or an accidental
        // "embeddable" suffix would desync from the artifacts JetBrains
        // actually publishes and the 404 would only show up in an online
        // build.
        assertEquals(
            "https://repo1.maven.org/maven2/org/jetbrains/kotlin/" +
                "kotlin-serialization-compiler-plugin/2.3.20/" +
                "kotlin-serialization-compiler-plugin-2.3.20.jar",
            deps.downloadCalls.single().first,
        )
    }

    @Test
    fun allopenAndNoargAliasesResolveToDistinctArtifacts() {
        val deps = FakeFetcherDeps(
            sha256Results = mutableMapOf(
                "$cacheBase/org/jetbrains/kotlin/kotlin-allopen-compiler-plugin/2.3.20/" +
                    "kotlin-allopen-compiler-plugin-2.3.20.jar" to "ha",
                "$cacheBase/org/jetbrains/kotlin/kotlin-noarg-compiler-plugin/2.3.20/" +
                    "kotlin-noarg-compiler-plugin-2.3.20.jar" to "hn",
            ),
        )

        val allopen = fetchPluginJar("allopen", kotlinVersion, cacheBase, deps)
        val noarg = fetchPluginJar("noarg", kotlinVersion, cacheBase, deps)

        assertTrue(assertNotNull(allopen.get()).endsWith("kotlin-allopen-compiler-plugin-2.3.20.jar"))
        assertTrue(assertNotNull(noarg.get()).endsWith("kotlin-noarg-compiler-plugin-2.3.20.jar"))
    }

    @Test
    fun warmCacheWithMatchingStampSkipsDownload() {
        val deps = FakeFetcherDeps(
            existingFiles = mutableSetOf(serializationJar, serializationStamp),
            sha256Results = mutableMapOf(serializationJar to "hash-abc"),
            stampContents = mutableMapOf(serializationStamp to "hash-abc"),
        )

        val result = fetchPluginJar("serialization", kotlinVersion, cacheBase, deps)

        assertEquals(serializationJar, assertNotNull(result.get()))
        assertTrue(deps.downloadCalls.isEmpty(), "cache hit must not download")
        assertTrue(deps.stampWrites.isEmpty(), "cache hit must not rewrite stamp")
    }

    @Test
    fun cacheHitWithStampMismatchReDownloadsAndRewritesStampAndWarns() {
        // TOFU self-heal: a corrupt stamp or corrupt jar means one of
        // the two drifted. Re-download + re-stamp is the only safe move —
        // silently trusting either side would let bit rot survive forever.
        // #65 review finding #4: emit a single observable warning so a
        // user staring at "why is every build slow" can grep stderr.
        val deps = FakeFetcherDeps(
            existingFiles = mutableSetOf(serializationJar, serializationStamp),
            sha256Results = mutableMapOf(serializationJar to "hash-actual"),
            stampContents = mutableMapOf(serializationStamp to "hash-stale"),
        )
        // After the re-download the freshly-downloaded jar hashes to
        // "hash-fresh". The fake keeps `sha256Results` and updates it
        // alongside the "successful" download.
        deps.sha256Results[serializationJar] = "hash-fresh"

        val result = fetchPluginJar("serialization", kotlinVersion, cacheBase, deps)

        assertEquals(serializationJar, assertNotNull(result.get()))
        assertEquals(1, deps.downloadCalls.size)
        assertEquals(serializationStamp to "hash-fresh", deps.stampWrites.single())
        assertEquals(1, deps.warnings.size)
        assertTrue(
            deps.warnings.single().contains("sha256 mismatch"),
            "mismatch warning should say so, got: ${deps.warnings.single()}",
        )
    }

    @Test
    fun cacheHitWithUnreadableStampWarnsAndReDownloads() {
        // Stamp file exists but cannot be read (fs error, race, etc.).
        // The self-heal path still falls through to re-download, but it
        // must not do so silently — that would let a systematic fs
        // failure (disk full, fs bug) drain the network on every build
        // with no user-visible signal.
        val deps = FakeFetcherDeps(
            existingFiles = mutableSetOf(serializationJar, serializationStamp),
            sha256Results = mutableMapOf(serializationJar to "hash-fresh"),
            // stampContents deliberately empty → readFileAsString returns OpenFailed
        )

        fetchPluginJar("serialization", kotlinVersion, cacheBase, deps)

        assertEquals(1, deps.downloadCalls.size)
        assertTrue(deps.warnings.any { it.contains("could not read stamp") })
    }

    @Test
    fun cacheHitWithHashFailureWarnsBeforeReDownload() {
        // computeSha256 fails on the existing jar (fs error, io race).
        // The cache-hit branch logs a warning and falls through to the
        // download path. `sha256Results` stays empty for this test so
        // the re-download's own `computeSha256` call also fails — the
        // whole fetch then returns `HashComputationFailed`. That is
        // fine for this assertion: we only care that the *cache-hit*
        // failure emitted a warning, because the silent-self-heal
        // regression the warning defends against happens on that path.
        val deps = FakeFetcherDeps(
            existingFiles = mutableSetOf(serializationJar, serializationStamp),
            stampContents = mutableMapOf(serializationStamp to "hash-x"),
            // sha256Results intentionally empty
        )

        val result = fetchPluginJar("serialization", kotlinVersion, cacheBase, deps)

        assertIs<PluginFetchError.HashComputationFailed>(assertNotNull(result.getError()))
        assertEquals(1, deps.downloadCalls.size, "must attempt re-download after cache-hit hash failure")
        assertTrue(
            deps.warnings.any { it.contains("could not hash") },
            "cache-hit hash failure must emit a `could not hash` warning, got: ${deps.warnings}",
        )
    }

    @Test
    fun cacheHitWithMissingStampReDownloads() {
        // Legacy cache state: jar is present from before the fetcher
        // switch (previously written by `ensureKotlincBin` in-place from
        // the kotlinc sidecar). No stamp means no TOFU anchor, so we
        // must not trust the cached jar — re-download to establish one.
        val deps = FakeFetcherDeps(
            existingFiles = mutableSetOf(serializationJar),
            sha256Results = mutableMapOf(serializationJar to "hash-fresh"),
        )

        fetchPluginJar("serialization", kotlinVersion, cacheBase, deps)

        assertEquals(1, deps.downloadCalls.size, "missing stamp should force a re-download")
        assertEquals(serializationStamp to "hash-fresh", deps.stampWrites.single())
    }

    @Test
    fun downloadFailurePropagatesAsPluginFetchError() {
        val deps = FakeFetcherDeps(
            downloadResult = { url, _ -> Err(DownloadError.HttpFailed(url, 500)) },
        )

        val result = fetchPluginJar("serialization", kotlinVersion, cacheBase, deps)

        val err = assertNotNull(result.getError())
        val failed = assertIs<PluginFetchError.DownloadFailed>(err)
        assertEquals("serialization", failed.alias)
        assertEquals(serializationUrl, failed.url)
    }

    @Test
    fun hashComputationFailureAfterDownloadMapsToFetchError() {
        // downloadFile succeeded, so the jar exists on disk, but
        // computeSha256 is unable to open it (disk error, race with
        // another kolt process, etc.). This is rare but must not
        // corrupt the cache — returning HashComputationFailed leaves
        // the stamp absent so the next build re-downloads.
        val deps = FakeFetcherDeps()

        val result = fetchPluginJar("serialization", kotlinVersion, cacheBase, deps)

        val err = assertNotNull(result.getError())
        assertIs<PluginFetchError.HashComputationFailed>(err)
        assertTrue(deps.stampWrites.isEmpty())
    }

    @Test
    fun fetchEnabledPluginJarsSkipsDisabledEntries() {
        val deps = FakeFetcherDeps(
            sha256Results = mutableMapOf(
                "$cacheBase/org/jetbrains/kotlin/kotlin-serialization-compiler-plugin/2.3.20/" +
                    "kotlin-serialization-compiler-plugin-2.3.20.jar" to "hs",
            ),
        )

        val result = fetchEnabledPluginJars(
            plugins = linkedMapOf("serialization" to true, "allopen" to false),
            kotlinVersion = kotlinVersion,
            cacheBase = cacheBase,
            deps = deps,
        )

        val map = assertNotNull(result.get())
        assertEquals(setOf("serialization"), map.keys)
        assertEquals(1, deps.downloadCalls.size)
    }

    @Test
    fun fetchEnabledPluginJarsAbortsOnFirstError() {
        // A missing jar must surface as a build-breaking error, not a
        // silently half-plugged compile. We seed only allopen with a
        // hash so serialization's computeSha256 call fails after the
        // stubbed download, and the iterator stops before even
        // attempting allopen.
        val deps = FakeFetcherDeps()

        val result = fetchEnabledPluginJars(
            plugins = linkedMapOf("serialization" to true, "allopen" to true),
            kotlinVersion = kotlinVersion,
            cacheBase = cacheBase,
            deps = deps,
        )

        val err = assertNotNull(result.getError())
        assertIs<PluginFetchError.HashComputationFailed>(err)
    }

    @Test
    fun fetchEnabledPluginJarsPreservesDeclarationOrder() {
        val deps = FakeFetcherDeps(
            sha256Results = mutableMapOf(
                "$cacheBase/org/jetbrains/kotlin/kotlin-allopen-compiler-plugin/2.3.20/" +
                    "kotlin-allopen-compiler-plugin-2.3.20.jar" to "ha",
                "$cacheBase/org/jetbrains/kotlin/kotlin-noarg-compiler-plugin/2.3.20/" +
                    "kotlin-noarg-compiler-plugin-2.3.20.jar" to "hn",
            ),
        )

        val result = fetchEnabledPluginJars(
            plugins = linkedMapOf("allopen" to true, "noarg" to true),
            kotlinVersion = kotlinVersion,
            cacheBase = cacheBase,
            deps = deps,
        )

        val map = assertNotNull(result.get())
        assertEquals(listOf("allopen", "noarg"), map.keys.toList())
    }

    @Test
    fun mkdirRunsOnColdCachePath() {
        // Regression guard: the fetcher must create the cache directory
        // tree before delegating to `downloadFile`. `downloadFile` itself
        // opens the destination with O_CREAT but not the enclosing
        // directory — same rationale as the daemon dir setup path in
        // DaemonPreconditions.
        val deps = FakeFetcherDeps(
            sha256Results = mutableMapOf(serializationJar to "h"),
        )

        fetchPluginJar("serialization", kotlinVersion, cacheBase, deps)

        assertEquals(1, deps.mkdirCalls.size)
        assertTrue(
            deps.mkdirCalls.single()
                .endsWith("kotlin-serialization-compiler-plugin/2.3.20"),
            "mkdir should be the enclosing version dir, got: ${deps.mkdirCalls.single()}",
        )
    }
}
